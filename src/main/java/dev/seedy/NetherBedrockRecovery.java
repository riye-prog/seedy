package dev.seedy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.LevelChunk;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class NetherBedrockRecovery implements AutoCloseable {
    public static final NetherBedrockRecovery INSTANCE = new NetherBedrockRecovery();
    private static final int MIN_CHUNKS = 4;
    private static final int MAX_CHUNKS = 48;
    private static final int CHUNKS_PER_ATTEMPT = 8;
    private static final int MIN_BEDROCK_PER_SIDE = 80;
    private static final long SEARCH_SPACE = 1L << 48;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(task -> Thread.ofPlatform().daemon().name("seedy-nether-recovery").unstarted(task));
    private static volatile MethodHandle crackHandle;
    private static volatile MethodHandle progressHandle;
    private static volatile MethodHandle cancelHandle;
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Set<Long> scanned = new HashSet<>();
    private int[] observations = new int[4096];
    private int observationCount;
    private int floorCount;
    private int roofCount;
    private int ticks;
    private int lastScanTick;
    private int attemptedChunks;
    private volatile int generation;
    private volatile Long expectedHash;
    private volatile boolean running;
    private volatile boolean cancelRequested;
    private volatile long startedAt;
    private volatile String status = "Waiting for a world";

    private NetherBedrockRecovery() { }

    public void reset(Long hash) {
        generation++;
        cancelSearch();
        expectedHash = hash;
        queue.clear();
        queued.clear();
        scanned.clear();
        observations = new int[4096];
        observationCount = 0;
        floorCount = 0;
        roofCount = 0;
        ticks = 0;
        lastScanTick = 0;
        attemptedChunks = 0;
        status = hash == null ? "Waiting for a world" : "Enter the Nether to scan bedrock";
    }

    public void loaded(ClientLevel world, ChunkPos position) {
        if (expectedHash != null && world.dimension().equals(Level.NETHER)) schedule(position);
    }

    private void schedule(ChunkPos position) {
        long key = position.pack();
        if (scanned.size() < MAX_CHUNKS && !scanned.contains(key) && queued.add(key)) queue.add(position);
    }

    public void tick(Minecraft client) {
        if (expectedHash == null || client.level == null || !client.level.dimension().equals(Level.NETHER)) return;
        if (Workbench.INSTANCE.hasVerifiedSeed()) {
            if (running) cancelSearch();
            else if (!status.startsWith("Seed recovered")) status = "Seed already verified";
            return;
        }
        ticks++;
        if (ticks % 20 == 1 && client.player != null && scanned.size() < MAX_CHUNKS) {
            int cx = client.player.getBlockX() >> 4;
            int cz = client.player.getBlockZ() >> 4;
            int radius = Math.min(4, client.options.renderDistance().get());
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                if (client.level.hasChunk(x, z)) schedule(new ChunkPos(x, z));
            }
        }
        long deadline = System.nanoTime() + 2_000_000;
        int scanLimit = Math.min(MAX_CHUNKS, attemptedChunks + CHUNKS_PER_ATTEMPT);
        while (!running && !queue.isEmpty() && scanned.size() < scanLimit && System.nanoTime() < deadline) {
            ChunkPos position = queue.remove();
            queued.remove(position.pack());
            if (client.level.hasChunk(position.x(), position.z()) && scanned.add(position.pack())) {
                scan(client.level.getChunk(position.x(), position.z()));
                lastScanTick = ticks;
            }
        }
        if (!running && canTry() && ticks - lastScanTick >= 40) startSearch();
    }

    private void scan(LevelChunk chunk) {
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = minX + dx;
            int z = minZ + dz;
            if (chunk.getBlockState(position.set(x, 4, z)).is(Blocks.BEDROCK)) {
                add(x, 4, z);
                floorCount++;
            }
            if (chunk.getBlockState(position.set(x, 123, z)).is(Blocks.BEDROCK)) {
                add(x, 123, z);
                roofCount++;
            }
        }
        if (!running) status = "Scanning loaded Nether bedrock";
    }

    private void add(int x, int y, int z) {
        if (observationCount + 4 > observations.length) observations = Arrays.copyOf(observations, observations.length * 2);
        observations[observationCount++] = x;
        observations[observationCount++] = y;
        observations[observationCount++] = z;
        observations[observationCount++] = 0;
    }

    public boolean canTry() {
        return expectedHash != null && !running && !Workbench.INSTANCE.hasVerifiedSeed() && scanned.size() >= MIN_CHUNKS && (attemptedChunks == 0 || scanned.size() >= attemptedChunks + 4) && floorCount >= MIN_BEDROCK_PER_SIDE && roofCount >= MIN_BEDROCK_PER_SIDE;
    }

    public void tryCurrentData() {
        if (expectedHash == null) throw new IllegalArgumentException("Join a world first.");
        if (Workbench.INSTANCE.hasVerifiedSeed()) throw new IllegalArgumentException("The seed is already verified.");
        if (running) throw new IllegalArgumentException("The Nether block check is already running.");
        if (scanned.size() < MIN_CHUNKS || floorCount < MIN_BEDROCK_PER_SIDE || roofCount < MIN_BEDROCK_PER_SIDE) throw new IllegalArgumentException("Load at least four Nether chunks with bedrock on both sides.");
        startSearch();
    }

    private void startSearch() {
        int[] entries = Arrays.copyOf(observations, observationCount);
        int runGeneration = generation;
        Long hash = expectedHash;
        attemptedChunks = scanned.size();
        running = true;
        cancelRequested = false;
        startedAt = System.nanoTime();
        status = "Cracking Nether bedrock";
        WORKER.submit(() -> {
            try {
                loadNative();
                if (cancelRequested || runGeneration != generation) return;
                int threads = Math.max(1, Math.min(16, Runtime.getRuntime().availableProcessors()));
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment blocks = arena.allocate(ValueLayout.JAVA_INT, entries.length);
                    blocks.copyFrom(MemorySegment.ofArray(entries));
                    MemorySegment results = arena.allocate(ValueLayout.JAVA_LONG);
                    long count = (long)crackHandle.invokeExact(blocks, (long)(entries.length / 4), (long)threads, hash.longValue(), results);
                    if (cancelRequested || runGeneration != generation || Workbench.INSTANCE.hasVerifiedSeed()) return;
                    if (count == -3) {
                        status = "Too many Nether candidates; load more chunks";
                        return;
                    }
                    if (count == -4) {
                        status = "Nether block check stopped";
                        return;
                    }
                    if (count < 0) {
                        status = "Could not read Nether bedrock sample";
                        return;
                    }
                    if (count == 0) status = "Nether bedrock did not match the world seed hash";
                    else if (count == 1) {
                        long worldSeed = results.get(ValueLayout.JAVA_LONG, 0);
                        Minecraft.getInstance().execute(() -> {
                            if (runGeneration == generation && Workbench.INSTANCE.acceptBlockSeed(worldSeed)) status = "Seed recovered from Nether bedrock";
                        });
                    }
                }
            } catch (Throwable error) {
                if (runGeneration == generation) status = "Nether block check failed: " + error.getClass().getSimpleName();
                org.slf4j.LoggerFactory.getLogger("Seedy").error("Nether bedrock recovery failed", error);
            } finally {
                running = false;
            }
        });
    }

    private static synchronized void loadNative() throws IOException {
        if (crackHandle != null) return;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = os.contains("mac") ? (arch.equals("aarch64") || arch.equals("arm64") ? "macos-arm64" : "macos-x86_64") : os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64")) ? "windows-x86_64" : null;
        if (platform == null) throw new IOException("This build supports macOS and Windows x64");
        NativeLibraries.load(platform, platform.startsWith("macos") ? "libseedynether.dylib" : "seedynether.dll");
        SymbolLookup symbols = SymbolLookup.loaderLookup();
        Linker linker = Linker.nativeLinker();
        crackHandle = linker.downcallHandle(symbols.findOrThrow("seedy_nether_crack"), FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        progressHandle = linker.downcallHandle(symbols.findOrThrow("seedy_nether_progress"), FunctionDescriptor.of(ValueLayout.JAVA_LONG));
        cancelHandle = linker.downcallHandle(symbols.findOrThrow("seedy_nether_cancel"), FunctionDescriptor.ofVoid());
    }

    private static void cancelNative() {
        MethodHandle handle = cancelHandle;
        if (handle == null) return;
        try { handle.invokeExact(); }
        catch (Throwable ignored) { }
    }

    public void stopSearch() {
        cancelSearch();
        status = "Stopping Nether block check";
    }

    private void cancelSearch() {
        cancelRequested = true;
        if (running) cancelNative();
    }

    public int scannedChunks() { return scanned.size(); }
    public int floorBlocks() { return floorCount; }
    public int roofBlocks() { return roofCount; }
    public boolean running() { return running; }
    public String status() { return Workbench.INSTANCE.hasVerifiedSeed() && !status.startsWith("Seed recovered") ? "Seed already verified" : status; }
    public long elapsedSeconds() { return running ? (System.nanoTime() - startedAt) / 1_000_000_000L : 0; }
    public double progress() {
        if (!running || progressHandle == null) return -1;
        try { return Math.min(1, (double)(long)progressHandle.invokeExact() / SEARCH_SPACE); }
        catch (Throwable ignored) { return -1; }
    }

    @Override public void close() {
        cancelSearch();
        WORKER.shutdownNow();
    }
}
