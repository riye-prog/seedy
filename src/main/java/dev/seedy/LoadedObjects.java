package dev.seedy;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.chunk.LevelChunk;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class LoadedObjects {
    public record ObjectPosition(String kind, int x, int y, int z) { }
    private static final Map<ChunkPos, Map<BlockPos, ObjectPosition>> CHUNKS = new HashMap<>();
    private static volatile List<ObjectPosition> snapshot = List.of();
    private static ClientLevel level;
    public static volatile boolean treasureEnabled = true;
    public static volatile boolean spawnersEnabled = true;
    public static volatile boolean lootEnabled = true;
    private static Map<Long, List<ChestLoot.Chest>> wanted = Map.of();

    public static void predictions(List<Locator.Result> results) {
        wanted = results.stream().filter(result -> result.dimension().equals("overworld") && result.loot() != null).flatMap(result -> result.loot().chests().stream()).filter(ChestLoot.Chest::wanted).collect(java.util.stream.Collectors.groupingBy(chest -> column(chest.x(), chest.z())));
        publish();
    }

    private static long column(int x, int z) { return ((long)x << 32) ^ (z & 0xffffffffL); }

    private static boolean wanted(ObjectPosition object) {
        if (level == null || !level.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) || object.kind().equals("Mob spawner")) return false;
        for (var chest : wanted.getOrDefault(column(object.x(), object.z()), List.of())) {
            if (chest.y() != null && chest.y() == object.y()) return true;
            if (chest.y() == null) {
                var above = new BlockPos(object.x(), object.y() + 1, object.z());
                for (var direction : net.minecraft.core.Direction.Plane.HORIZONTAL) if (level.getBlockState(above.relative(direction)).is(Blocks.CHISELED_SANDSTONE)) return true;
            }
        }
        return false;
    }

    public static List<ObjectPosition> snapshot() { return snapshot; }
    public static boolean active() { return !snapshot.isEmpty() && (treasureEnabled || spawnersEnabled || lootEnabled); }
    public static void clear() { CHUNKS.clear(); snapshot = List.of(); level = null; }
    private static void publish() {
        snapshot = CHUNKS.values().stream().flatMap(entries -> entries.values().stream()).map(object -> wanted(object) ? new ObjectPosition("Matching loot chest", object.x(), object.y(), object.z()) : object).filter(object -> !object.kind().equals("Chest")).toList();
    }
    public static void unload(ClientLevel world, ChunkPos chunk) { if (world == level && CHUNKS.remove(chunk) != null) publish(); }

    public static void loaded(ClientLevel world, LevelChunk chunk) {
        if (world != level) { clear(); level = world; }
        var found = new HashMap<BlockPos, ObjectPosition>();
        var sections = chunk.getSections();
        for (int sectionIndex = 0; sectionIndex < sections.length; sectionIndex++) {
            var section = sections[sectionIndex];
            if (!section.maybeHas(state -> state.is(Blocks.SPAWNER) || state.is(Blocks.CHEST))) continue;
            int bottom = world.getMinY() + sectionIndex * 16;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                var state = section.getBlockState(x, y, z);
                if (!state.is(Blocks.SPAWNER) && !state.is(Blocks.CHEST)) continue;
                var position = new BlockPos(chunk.getPos().x() * 16 + x, bottom + y, chunk.getPos().z() * 16 + z);
                String kind = classify(world, position, state);
                if (kind != null) found.put(position, new ObjectPosition(kind, position.getX(), position.getY(), position.getZ()));
            }
        }
        CHUNKS.put(chunk.getPos(), found);
        publish();
    }

    public static void changed(ClientLevel world, BlockPos position) {
        if (world != level) return;
        boolean updated = false;
        for (var at : List.of(position, position.above())) {
            var entries = CHUNKS.get(new ChunkPos(at.getX() >> 4, at.getZ() >> 4));
            if (entries == null) continue;
            String kind = classify(world, at, world.getBlockState(at));
            if (kind == null) updated |= entries.remove(at) != null;
            else {
                var value = new ObjectPosition(kind, at.getX(), at.getY(), at.getZ());
                updated |= !value.equals(entries.put(at.immutable(), value));
            }
        }
        if (updated) publish();
    }

    static boolean treasureShape(BlockPos position, BlockState chest, BlockState below) {
        if ((position.getX() & 15) != 9 || (position.getZ() & 15) != 9 || !chest.is(Blocks.CHEST) || chest.getValue(ChestBlock.WATERLOGGED) || chest.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return false;
        return below.is(Blocks.SANDSTONE) || below.is(Blocks.STONE) || below.is(Blocks.ANDESITE) || below.is(Blocks.GRANITE) || below.is(Blocks.DIORITE) || below.is(Blocks.COAL_ORE) || below.is(Blocks.IRON_ORE) || below.is(Blocks.GOLD_ORE) || below.is(Blocks.GRAVEL);
    }

    private static String classify(ClientLevel world, BlockPos position, BlockState state) {
        if (state.is(Blocks.SPAWNER)) return "Mob spawner";
        if (!state.is(Blocks.CHEST)) return null;
        if (world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD) && world.getBiome(position).is(BiomeTags.HAS_BURIED_TREASURE) && treasureShape(position, state, world.getBlockState(position.below()))) return "Buried treasure candidate";
        return "Chest";
    }
}
