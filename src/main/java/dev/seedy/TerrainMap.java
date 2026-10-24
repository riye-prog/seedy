package dev.seedy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

final class TerrainMap implements AutoCloseable {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(dev.seedy.recovery.RecoveryWorkload.threads("Seedy terrain"));
    private final ArrayBlockingQueue<String> updates = new ArrayBlockingQueue<>(64);
    private final LinkedHashMap<Long, Long> scanned = new LinkedHashMap<>(1024,0.75f,true);
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private List<ChunkPos> nearby = List.of();
    private int centerX = Integer.MIN_VALUE, centerZ, radius, slice, cursor, column, ticks;
    private TerrainSurface surface;
    private final TerrainPalette palette = new TerrainPalette();
    private final LevelChunk[] sampleChunks = new LevelChunk[9];
    private LevelChunk current;
    private volatile long epoch;
    private final java.util.concurrent.atomic.AtomicInteger encoding = new java.util.concurrent.atomic.AtomicInteger();

    void tick(Minecraft client, boolean enabled, double blocksPerPixel, int size, boolean recovering) {
        if (!enabled || client.level == null || client.player == null) return;
        int nextSlice = client.level.dimension().equals(net.minecraft.world.level.Level.NETHER) ? Math.floorDiv(client.player.getBlockY(),8) * 8 + 7 : Integer.MAX_VALUE;
        if (palette.refresh(client) || level != client.level || slice != nextSlice) {
            level = client.level; slice = nextSlice; epoch++; updates.clear(); scanned.clear(); current = null; java.util.Arrays.fill(sampleChunks,null); centerX = Integer.MIN_VALUE;
            updates.offer("{\"reset\":" + epoch + ",\"dimension\":\"" + dimension(level) + "\"}");
        }
        ++ticks;
        int x = client.player.chunkPosition().x(), z = client.player.chunkPosition().z();
        int distance = Math.clamp((int)Math.ceil(blocksPerPixel * size * 0.72 / 16) + 1,2,24);
        if (distance != radius) {
            radius = distance;
            var offsets = new ArrayList<ChunkPos>();
            for (int dz = -radius; dz <= radius; ++dz) for (int dx = -radius; dx <= radius; ++dx) offsets.add(new ChunkPos(dx,dz));
            offsets.sort(Comparator.comparingInt(p -> Math.abs(p.x()) + Math.abs(p.z())));
            nearby = List.copyOf(offsets); cursor = 0;
        }
        if (x != centerX || z != centerZ) { centerX = x; centerZ = z; cursor = 0; }
        if (updates.remainingCapacity() < 4 || encoding.get() >= 4) return;
        long deadline = System.nanoTime() + (recovering ? 300_000 : 650_000);
        int budget = recovering ? 64 : 256, checks = 0;
        while (budget > 0 && System.nanoTime() < deadline) {
            if (current == null) {
                if (++checks > 128 || nearby.isEmpty()) return;
                var next = nearby.get(cursor++ % nearby.size());
                int chunkX = centerX + next.x(), chunkZ = centerZ + next.z();
                Long last = scanned.get(ChunkPos.pack(chunkX,chunkZ));
                if (last != null && ticks - last < 200) continue;
                current = level.getChunkSource().getChunk(chunkX,chunkZ,ChunkStatus.FULL,false);
                if (current == null || current.isEmpty()) { current = null; continue; }
                surface = new TerrainSurface(); column = 0;
                for (int dz = -1; dz <= 1; ++dz) for (int dx = -1; dx <= 1; ++dx) sampleChunks[(dz + 1) * 3 + dx + 1] = level.getChunkSource().getChunk(chunkX + dx,chunkZ + dz,ChunkStatus.FULL,false);
            }
            for (int limit = Math.min(TerrainSurface.COLUMNS,column + Math.min(4,budget)); column < limit; ++column, --budget) sample(client);
            if (column == TerrainSurface.COLUMNS) {
                var chunk = current.getPos(); var captured = surface; long token = epoch; String dimension = dimension(level);
                scanned.put(chunk.pack(),(long)ticks);
                while (scanned.size() > 1024) scanned.remove(scanned.keySet().iterator().next());
                current = null;
                encoding.incrementAndGet();
                worker.execute(() -> {
                    try {
                        if (token != epoch) return;
                        var data = captured.encode(chunk.x(),chunk.z());
                        data.addProperty("epoch",token); data.addProperty("dimension",dimension);
                        if (token == epoch) updates.offer(data.toString());
                    } finally { encoding.decrementAndGet(); }
                });
            }
        }
    }

    private void sample(Minecraft client) {
        int localX = column % TerrainSurface.SIDE - TerrainSurface.BORDER, localZ = column / TerrainSurface.SIDE - TerrainSurface.BORDER;
        var chunk = sampleChunks[(Math.floorDiv(localZ,16) + 1) * 3 + Math.floorDiv(localX,16) + 1];
        if (chunk == null || chunk.isEmpty()) return;
        int x = current.getPos().getMinBlockX() + localX, z = current.getPos().getMinBlockZ() + localZ;
        int top = Math.min(slice,chunk.getHeight(Heightmap.Types.WORLD_SURFACE,Math.floorMod(localX,16),Math.floorMod(localZ,16)));
        int bottom = Math.max(level.getMinY(),top - 48);
        surface.materials[column] = TerrainPalette.VOID; surface.tints[column] = 0xffffff;
        for (int y = top; y >= bottom; --y) {
            position.set(x,y,z);
            var block = chunk.getBlockState(position);
            if (block.isAir()) continue;
            var material = palette.get(client,block);
            if (!material.top() && !block.isSolidRender() && !block.hasBlockEntity() && !(block.getBlock() instanceof LeavesBlock)) continue;
            surface.materials[column] = material;
            surface.tints[column] = palette.tint(client,level,block,position,material);
            surface.heights[column] = y;
            surface.ground[column] = 0x263c29;
            boolean water = (material.flags() & TerrainPalette.WATER) != 0, translucent = (material.flags() & (TerrainPalette.FOLIAGE | TerrainPalette.TRANSLUCENT)) != 0;
            if (water || translucent) for (int depth = 1; depth <= (water ? 24 : 14) && y - depth >= level.getMinY(); ++depth) {
                position.set(x,y - depth,z);
                var under = chunk.getBlockState(position);
                surface.depths[column] = depth;
                if (under.isAir() || under.getBlock() instanceof LeavesBlock || under.getFluidState().is(net.minecraft.tags.FluidTags.WATER)) continue;
                var color = under.getMapColor(level,position);
                if (color == MapColor.NONE) continue;
                surface.ground[column] = color == MapColor.GRASS ? level.getBiome(position).value().getGrassColor(x,z) : color.col;
                break;
            }
            return;
        }
        surface.heights[column] = level.getMinY() - 1;
    }

    static JsonObject encode(int x, int z, int[] colors, int[] heights) {
        if (colors.length != 256 || heights.length != 256) throw new IllegalArgumentException("A terrain tile needs 256 columns.");
        var runs = new JsonArray();
        int previous = -1, count = 0;
        for (int i = 0; i < 256; ++i) {
            double shade = i < 16 ? 1 : Math.clamp(1 + (heights[i - 16] - heights[i]) * 0.055,0.72,1.2);
            int rgb = colors[i];
            int color = (Math.min(255,(int)((rgb >> 16 & 255) * shade)) << 16) | (Math.min(255,(int)((rgb >> 8 & 255) * shade)) << 8) | Math.min(255,(int)((rgb & 255) * shade));
            if (previous != color && count > 0) { runs.add(count); runs.add(previous); count = 0; }
            previous = color; count++;
        }
        runs.add(count); runs.add(previous);
        var result = new JsonObject(); result.addProperty("x",x); result.addProperty("z",z); result.add("runs",runs);
        return result;
    }

    private static String dimension(ClientLevel level) { return level.dimension().identifier().getPath(); }
    String poll() { return updates.poll(); }
    void reset() {
        epoch++; level = null; current = null; surface = null; java.util.Arrays.fill(sampleChunks,null);
        nearby = List.of(); scanned.clear(); centerX = Integer.MIN_VALUE; radius = 0; cursor = 0;
        updates.clear(); updates.offer("{\"reset\":" + epoch + ",\"dimension\":\"\"}");
    }
    @Override public void close() { epoch++; updates.clear(); worker.shutdownNow(); }
}
