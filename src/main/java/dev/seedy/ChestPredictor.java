package dev.seedy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.NbtOps;
import net.minecraft.resources.RegistryOps;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.PoolElementStructurePiece;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pools.EmptyPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.ListPoolElement;
import net.minecraft.world.level.levelgen.structure.pools.FeaturePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.StructurePoolElement;
import net.minecraft.world.level.levelgen.structure.pools.SinglePoolElement;
import net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class ChestPredictor {
    private final VanillaStructures structures;
    private final LootEvaluator loot;
    private final java.lang.reflect.Method templateMethod;
    private final java.lang.reflect.Method worldPosition;
    private final java.lang.reflect.Field palettes;

    ChestPredictor(VanillaStructures structures) {
        this.structures = structures;
        loot = new LootEvaluator(structures.registries());
        try {
            templateMethod = SinglePoolElement.class.getDeclaredMethod("getTemplate", StructureTemplateManager.class);
            templateMethod.setAccessible(true);
            worldPosition = StructurePiece.class.getDeclaredMethod("getWorldPos", int.class, int.class, int.class);
            worldPosition.setAccessible(true);
            palettes = StructureTemplate.class.getDeclaredField("palettes");
            palettes.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("This version's chest placement API is not supported", e); }
    }

    List<String> items() { return loot.items(); }

    ChestLoot predict(long seed, VanillaStructures.Generated generated, LootQuery query) {
        var chests = new ArrayList<ChestLoot.Chest>();
        var randoms = new HashMap<ChunkPos, WorldgenRandom>();
        var uncertainChunks = new java.util.HashSet<ChunkPos>();
        int step = generated.start().getStructure().step().ordinal();
        int index = decorationIndex(generated.name(), step);
        for (var piece : generated.start().getPieces()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Search cancelled");
            if (piece instanceof DesertPyramidPiece) {
                for (var direction : Direction.Plane.HORIZONTAL) {
                    var pos = transformed(piece, 10 + direction.getStepX() * 2, -11, 10 + direction.getStepZ() * 2);
                    var chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
                    var random = randoms.computeIfAbsent(chunk, key -> {
                        var initialized = decorationRandom(seed, key, index, step);
                        initialized.nextInt(3);
                        return initialized;
                    });
                    long lootSeed = random.nextLong();
                    var items = loot.evaluate("minecraft:chests/desert_pyramid", lootSeed);
                    chests.add(chest(pos, null, "minecraft:chests/desert_pyramid", lootSeed, items, query));
                }
            } else if (piece instanceof PoolElementStructurePiece pool) {
                for (var element : flatten(pool.getElement())) {
                    if (element instanceof EmptyPoolElement) continue;
                    if (element instanceof FeaturePoolElement) {
                        var bounds = element.getBoundingBox(structures.templates(), pool.getPosition(), pool.getRotation());
                        for (int cx = bounds.minX() >> 4; cx <= bounds.maxX() >> 4; cx++) for (int cz = bounds.minZ() >> 4; cz <= bounds.maxZ() >> 4; cz++) uncertainChunks.add(new ChunkPos(cx, cz));
                        continue;
                    }
                    if (!(element instanceof SinglePoolElement single)) throw new IllegalStateException("Unsupported ancient-city piece for chest prediction");
                    var template = template(single);
                    var settings = new StructurePlaceSettings().setRotation(pool.getRotation());
                    for (var block : containers(template, settings, pool.getPosition())) {
                        var pos = StructureTemplate.calculateRelativePosition(settings, block.pos()).offset(pool.getPosition());
                        var chunk = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
                        long lootSeed = randoms.computeIfAbsent(chunk, key -> decorationRandom(seed, key, index, step)).nextLong();
                        if (!block.state().is(Blocks.CHEST)) continue;
                        String table = block.nbt().getStringOr("LootTable", "");
                        if (!table.isEmpty() && uncertainChunks.contains(chunk)) {
                            chests.add(new ChestLoot.Chest(pos.getX(), pos.getY(), pos.getZ(), table, 0, List.of(), false, "Sculk placement changes this chest's loot seed; contents are not included in totals."));
                            continue;
                        }
                        List<ChestLoot.Item> items;
                        if (table.isEmpty()) {
                            var fixed = new ArrayList<ItemStack>();
                            var ops = RegistryOps.create(NbtOps.INSTANCE, structures.registries());
                            for (var item : block.nbt().getListOrEmpty("Items")) fixed.add(ItemStack.CODEC.parse(ops, item).getOrThrow());
                            items = LootEvaluator.describe(fixed);
                        } else items = loot.evaluate(table, lootSeed);
                        chests.add(chest(pos, pos.getY(), table, lootSeed, items, query));
                    }
                }
            } else throw new IllegalStateException("Unsupported structure for chest prediction");
        }
        return ChestLoot.of(chests);
    }

    private List<StructurePoolElement> flatten(StructurePoolElement element) {
        if (element instanceof ListPoolElement list) return list.getElements().stream().flatMap(child -> flatten(child).stream()).toList();
        return List.of(element);
    }

    @SuppressWarnings("unchecked")
    private List<StructureTemplate.StructureBlockInfo> containers(StructureTemplate template, StructurePlaceSettings settings, BlockPos position) {
        try {
            var selected = settings.getRandomPalette((List<StructureTemplate.Palette>)palettes.get(template), position);
            return selected.blocks().stream().filter(block -> block.nbt() != null && block.state().getBlock() instanceof net.minecraft.world.level.block.EntityBlock entity && entity.newBlockEntity(block.pos(), block.state()) instanceof net.minecraft.world.RandomizableContainer).toList();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not read structure containers", e); }
    }

    private ChestLoot.Chest chest(BlockPos position, Integer height, String table, long seed, List<ChestLoot.Item> items, LootQuery query) {
        return new ChestLoot.Chest(position.getX(), height, position.getZ(), table, seed, items, items.stream().anyMatch(item -> query.wanted(item.id())), "");
    }

    private int decorationIndex(String name, int step) {
        int index = 0;
        for (var holder : structures.registries().lookupOrThrow(Registries.STRUCTURE).listElements().toList()) {
            if (holder.value().step().ordinal() != step) continue;
            if (holder.key().identifier().toString().equals(name)) return index;
            index++;
        }
        throw new IllegalStateException("Structure is missing from the decoration order");
    }

    static WorldgenRandom decorationRandom(long seed, ChunkPos chunk, int index, int step) {
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long population = random.setDecorationSeed(seed, chunk.x() * 16, chunk.z() * 16);
        random.setFeatureSeed(population, index, step);
        return random;
    }

    private StructureTemplate template(SinglePoolElement element) {
        try { return (StructureTemplate)templateMethod.invoke(element, structures.templates()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not read the vanilla structure template", e); }
    }

    private BlockPos transformed(StructurePiece piece, int x, int y, int z) {
        try { return ((BlockPos)worldPosition.invoke(piece, x, y, z)).immutable(); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not transform chest coordinates", e); }
    }
}
