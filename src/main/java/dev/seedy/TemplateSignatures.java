package dev.seedy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class TemplateSignatures {
    interface BlocksView { Block at(BlockPos position); }
    record Sample(BlockPos position, Block block) { }
    record Layout(String name, List<Sample> blocks) { }
    record Pattern(String name, Rotation rotation, BlockPos anchor, List<Sample> samples) {
        boolean matches(BlocksView world, BlockPos origin) {
            for (var sample : samples) if (world.at(origin.offset(sample.position())) != sample.block()) return false;
            return true;
        }
    }
    private static final Map<String, List<Layout>> CACHE = new HashMap<>();
    private final List<Pattern> ships = new ArrayList<>();
    private final List<Pattern> chambers = new ArrayList<>();

    TemplateSignatures() {
        for (String shape : List.of("with_mast", "rightsideup_full", "rightsideup_fronthalf", "rightsideup_backhalf", "sideways_full", "sideways_fronthalf", "sideways_backhalf", "upsidedown_full", "upsidedown_fronthalf", "upsidedown_backhalf")) {
            for (String suffix : List.of("", "_degraded")) for (var layout : loadVariants("shipwreck/" + shape + suffix)) add(layout, new BlockPos(4, 0, 15), true);
        }
        add(load("trial_chambers/corridor/end_1"), BlockPos.ZERO, false);
        add(load("trial_chambers/corridor/end_2"), BlockPos.ZERO, false);
    }

    static Layout load(String name) {
        String[] parts = name.split("#", 2);
        return loadVariants(parts[0]).get(parts.length == 1 ? 0 : Integer.parseInt(parts[1]));
    }

    private static synchronized List<Layout> loadVariants(String name) {
        if (CACHE.containsKey(name)) return CACHE.get(name);
        try (var input = TemplateSignatures.class.getResourceAsStream("/data/minecraft/structure/" + name + ".nbt")) {
            if (input == null) throw new IOException("Missing Minecraft template: " + name);
            var root = NbtIo.readCompressed(input, NbtAccounter.unlimitedHeap());
            var palettes = new ArrayList<net.minecraft.nbt.ListTag>();
            if (root.getList("palette").isPresent()) palettes.add(root.getListOrEmpty("palette"));
            else for (var palette : root.getListOrEmpty("palettes")) palettes.add((net.minecraft.nbt.ListTag)palette);
            var layouts = new ArrayList<Layout>();
            for (int variant = 0; variant < palettes.size(); variant++) {
                var palette = palettes.get(variant);
                var blocks = new ArrayList<Sample>();
                for (var entry : root.getListOrEmpty("blocks")) {
                    var block = (net.minecraft.nbt.CompoundTag)entry;
                    var position = block.getListOrEmpty("pos");
                    var state = palette.getCompoundOrEmpty(block.getIntOr("state", 0));
                    String id = state.getStringOr("id", state.getStringOr("Name", "minecraft:air"));
                    blocks.add(new Sample(new BlockPos(position.getIntOr(0, 0), position.getIntOr(1, 0), position.getIntOr(2, 0)), BuiltInRegistries.BLOCK.getValue(Identifier.parse(id))));
                }
                layouts.add(new Layout(name + "#" + variant, List.copyOf(blocks)));
            }
            if (layouts.isEmpty()) throw new IOException("Template has no palette: " + name);
            CACHE.put(name, List.copyOf(layouts));
            return CACHE.get(name);
        } catch (IOException e) { throw new IllegalStateException("Could not load structure detection templates", e); }
    }

    private void add(Layout layout, BlockPos pivot, boolean ship) {
        var stable = layout.blocks().stream().filter(sample -> stable(sample.block())).toList();
        var frequencies = new HashMap<Block, Integer>();
        stable.forEach(sample -> frequencies.merge(sample.block(), 1, Integer::sum));
        var ordered = stable.stream().sorted(Comparator.comparingInt((Sample sample) -> frequencies.get(sample.block())).thenComparingLong(sample -> scatter(sample.position()))).toList();
        var anchors = ship ? stable.stream().filter(sample -> sample.block() == Blocks.CHEST).toList() : List.of(ordered.getFirst());
        if (anchors.isEmpty()) throw new IllegalStateException("No detection anchor in " + layout.name());
        var selected = ordered.subList(0, Math.min(48, ordered.size()));
        for (var rotation : Rotation.values()) {
            var samples = selected.stream().map(sample -> new Sample(StructureTemplate.transform(sample.position(), Mirror.NONE, rotation, pivot), sample.block())).toList();
            for (var anchor : anchors) {
                var pattern = new Pattern(layout.name(), rotation, StructureTemplate.transform(anchor.position(), Mirror.NONE, rotation, pivot), samples);
                (ship ? ships : chambers).add(pattern);
            }
        }
    }

    private static boolean stable(Block block) {
        String id = BuiltInRegistries.BLOCK.getKey(block).getPath();
        return !block.defaultBlockState().isAir() && block != Blocks.WATER && block != Blocks.LAVA && block != Blocks.STRUCTURE_BLOCK && block != Blocks.JIGSAW && block != Blocks.VAULT && block != Blocks.TRIAL_SPAWNER && !id.contains("copper_bulb");
    }

    private static long scatter(BlockPos position) { return (position.asLong() * 0x9e3779b97f4a7c15L) ^ (position.getY() * 0x85ebca6bL); }
    List<Pattern> shipPatterns() { return List.copyOf(ships); }
    List<Pattern> chamberPatterns() { return List.copyOf(chambers); }

    Set<ChunkPos> shipwrecks(BlocksView world, BlockPos chest) {
        var found = new LinkedHashSet<ChunkPos>();
        for (var pattern : ships) {
            var origin = chest.subtract(pattern.anchor());
            if ((origin.getX() & 15) != 0 || (origin.getZ() & 15) != 0) continue;
            if (pattern.matches(world, origin)) found.add(ChunkPos.containing(origin));
        }
        return found.size() == 1 ? found : Set.of();
    }

    boolean trialChamber(BlocksView world, ChunkPos chunk) {
        for (var pattern : chambers) {
            for (int y = -50; y <= -10; y++) {
                var origin = new BlockPos(chunk.getMinBlockX(), y, chunk.getMinBlockZ());
                if (pattern.matches(world, origin)) return true;
            }
        }
        return false;
    }
}
