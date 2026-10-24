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
    private int[] colors, heights;
    private LevelChunk current;
    private volatile long epoch;
    private final java.util.concurrent.atomic.AtomicInteger encoding = new java.util.concurrent.atomic.AtomicInteger();

    void tick(Minecraft client, boolean enabled, double blocksPerPixel, int size, boolean recovering) {
        if (!enabled || client.level == null || client.player == null) return;
        int nextSlice = client.level.dimension().equals(net.minecraft.world.level.Level.NETHER) ? Math.floorDiv(client.player.getBlockY(),8) * 8 + 7 : Integer.MAX_VALUE;
        if (level != client.level || slice != nextSlice) {
            level = client.level; slice = nextSlice; epoch++; updates.clear(); scanned.clear(); current = null; centerX = Integer.MIN_VALUE;
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
                colors = new int[256]; heights = new int[256]; column = 0;
            }
            for (int limit = Math.min(256,column + Math.min(8,budget)); column < limit; ++column, --budget) sample();
            if (column == 256) {
                var chunk = current.getPos(); var pixels = colors; var elevations = heights; long token = epoch; String dimension = dimension(level);
                scanned.put(chunk.pack(),(long)ticks);
                while (scanned.size() > 1024) scanned.remove(scanned.keySet().iterator().next());
                current = null;
                encoding.incrementAndGet();
                worker.execute(() -> {
                    try {
                    if (token != epoch) return;
                    var data = encode(chunk.x(),chunk.z(),pixels,elevations);
                    data.addProperty("epoch",token); data.addProperty("dimension",dimension);
                    if (token == epoch) updates.offer(data.toString());
                    } finally { encoding.decrementAndGet(); }
                });
            }
        }
    }

    private void sample() {
        int x = current.getPos().getMinBlockX() + column % 16, z = current.getPos().getMinBlockZ() + column / 16;
        int top = Math.min(slice,current.getHeight(Heightmap.Types.WORLD_SURFACE,column % 16,column / 16));
        int bottom = Math.max(level.getMinY(),top - 48);
        int color = 0x19202a;
        for (int y = top; y >= bottom; --y) {
            position.set(x,y,z);
            var block = current.getBlockState(position);
            var mapColor = block.getMapColor(level,position);
            if (mapColor == MapColor.NONE) continue;
            color = mapColor.col;
            var biome = level.getBiome(position).value();
            if (mapColor == MapColor.WATER) color = biome.getWaterColor();
            else if (mapColor == MapColor.GRASS) color = biome.getGrassColor(x,z);
            else if (block.getBlock() instanceof LeavesBlock) color = biome.getFoliageColor();
            top = y;
            break;
        }
        colors[column] = color; heights[column] = top;
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
    @Override public void close() { epoch++; updates.clear(); worker.shutdownNow(); }
}
