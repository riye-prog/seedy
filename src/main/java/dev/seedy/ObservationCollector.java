package dev.seedy;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

final class ObservationCollector {
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<ChunkPos> queued = new HashSet<>();
    private final Set<ChunkPos> loaded = new HashSet<>();
    private TemplateSignatures signatures;
    private ClientLevel level;
    private static final int[][] HUT = {{3,2,6},{4,2,6},{1,3,5},{1,2,1},{5,2,1},{2,3,2},{3,3,7}};
    private static final Block[] HUT_BLOCKS = {Blocks.CRAFTING_TABLE, Blocks.CAULDRON, Blocks.POTTED_RED_MUSHROOM, Blocks.OAK_FENCE, Blocks.OAK_FENCE, Blocks.OAK_FENCE, Blocks.OAK_FENCE};
    void initialize() { signatures = new TemplateSignatures(); }
    void clear() { queue.clear(); queued.clear(); loaded.clear(); level = null; }
    int pending() { return queue.size(); }
    void unload(ChunkPos position) { loaded.remove(position); queued.remove(position); queue.remove(position); }
    void rescan() { for (var position : loaded) schedule(position); }
    void loaded(ClientLevel world, ChunkPos position, Workbench workbench) {
        if (world != level) { clear(); level = world; }
        loaded.add(position);
        if (!workbench.collecting()) return;
        scan(world, position, workbench);
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            if (dx == 0 && dz == 0) continue;
            var neighbor = new ChunkPos(position.x() + dx, position.z() + dz);
            if (loaded.contains(neighbor)) scanTemplates(world, neighbor, workbench);
        }
        workbench.refresh();
    }
    private void schedule(ChunkPos position) { if (queue.size() < 4096 && queued.add(position)) queue.add(position); }
    void tick(ClientLevel world, Workbench workbench) {
        if (queue.isEmpty() || !workbench.collecting() || !world.dimension().identifier().getPath().equals("overworld")) return;
        long deadline = System.nanoTime() + 6_000_000;
        do {
            var chunk = queue.remove();
            queued.remove(chunk);
            if (loaded.contains(chunk)) scan(world, chunk, workbench);
        } while (!queue.isEmpty() && System.nanoTime() < deadline);
        workbench.refresh();
    }
    private void scanTemplates(ClientLevel world, ChunkPos chunk, Workbench workbench) {
        if (!world.hasChunk(chunk.x(), chunk.z())) return;
        if (signatures == null) initialize();
        TemplateSignatures.BlocksView blocks = position -> world.hasChunk(position.getX() >> 4, position.getZ() >> 4) ? world.getBlockState(position).getBlock() : null;
        for (var position : world.getChunk(chunk.x(), chunk.z()).getBlockEntities().keySet()) {
            if (blocks.at(position) == Blocks.CHEST) for (var start : signatures.shipwrecks(blocks, position)) workbench.observe("minecraft:shipwrecks", start.x(), start.z(), "Detected");
        }
        if (signatures.trialChamber(blocks, chunk)) workbench.observe("minecraft:trial_chambers", chunk.x(), chunk.z(), "Detected");
    }
    private void scan(ClientLevel world, ChunkPos chunk, Workbench workbench) {
        if (!world.hasChunk(chunk.x(), chunk.z())) return;
        scanTemplates(world, chunk, workbench);
        int x = chunk.x() * 16, z = chunk.z() * 16;
        for (int y = world.getMinY(); y < world.getMaxY() - 8; y++) {
            if (is(world, x + 10, y, z + 10, Blocks.TNT) && is(world, x + 10, y + 2, z + 10, Blocks.STONE_PRESSURE_PLATE)) {
                boolean valid = true;
                for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) valid &= is(world, x + 10 + dx, y, z + 10 + dz, Blocks.TNT);
                if (valid) workbench.observe("minecraft:desert_pyramids", chunk.x(), chunk.z(), "Detected");
            }
            for (int rotation = 0; rotation < 4; rotation++) {
                boolean valid = true;
                for (int i = 0; i < HUT.length; i++) {
                    int lx = HUT[i][0], lz = HUT[i][2];
                    int px = rotation < 2 ? lx : rotation == 2 ? 8 - lz : lz;
                    int pz = rotation == 0 ? 8 - lz : rotation == 1 ? lz : lx;
                    if (!is(world, x + px, y + HUT[i][1], z + pz, HUT_BLOCKS[i])) { valid = false; break; }
                }
                if (valid) workbench.observe("minecraft:swamp_huts", chunk.x(), chunk.z(), "Detected");
            }
        }
    }
    private static boolean is(ClientLevel world, int x, int y, int z, Block block) { return world.getBlockState(new BlockPos(x, y, z)).is(block); }
}
