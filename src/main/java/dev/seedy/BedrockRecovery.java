package dev.seedy;

import bedrock.generated.BedrockNative;
import bedrock.generated.block_entry;
import dev.seedy.recovery.BlockSearchProgress;
import dev.seedy.recovery.RecoveryBudget;
import dev.seedy.recovery.RecoveryDiagnostics;
import dev.seedy.recovery.RecoveryWorkload;
import dev.seedy.recovery.SeedHash;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BedrockRecovery implements AutoCloseable {
    public static final BedrockRecovery INSTANCE = new BedrockRecovery();
    private static final int MIN_EVIDENCE_CHUNKS = 64;
    private static final long COORDINATE_MASK = (1L << 26) - 1;
    private final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
    private final Set<Long> queued = new HashSet<>();
    private final Set<Long> scanned = new HashSet<>();
    private final Map<Long, ChunkSample> samples = new LinkedHashMap<>();
    private final ThreadLocal<String> nativeFailure = new ThreadLocal<>();
    private final BlockSearchProgress progress = new BlockSearchProgress();
    private final RecoveryBudget budget = new RecoveryBudget(Duration.ofMinutes(10));
    private volatile OreVeinCheck oreVeinCheck;
    private static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger("Seedy");
    private final ExecutorService worker = Executors.newSingleThreadExecutor(RecoveryWorkload.threads("seedy-bedrock-recovery"));
    private static volatile MethodHandle schedulingHandle;
    private static int lastDuty = -1;
    private int observationCount;
    private int rawCount;
    private int oreCount;
    private int evidenceChunks;
    private int rawChunks;
    private int conflictingRawCount;
    private int conflictingRawChunks;
    private int ticks;
    private int lastScanTick;
    private int lastAttemptEvidenceChunks;
    private int lastAttemptRawBlocks;
    private int lastAttemptRawChunks;
    private volatile int generation;
    private volatile int runningGeneration = -1;
    private boolean connected;
    private boolean attempted;
    private volatile boolean cancelRequested;
    private volatile boolean running;
    private volatile boolean found;
    private volatile boolean nativeMatched;
    private volatile Long expectedHash;
    private volatile String status = "Waiting for a world";
    private static boolean nativeLoaded;

    private BedrockRecovery() { }

    public void reset(Long hash) {
        generation++;
        expectedHash = hash;
        connected = hash != null;
        queue.clear();
        queued.clear();
        scanned.clear();
        samples.clear();
        observationCount = 0;
        rawCount = 0;
        oreCount = 0;
        evidenceChunks = 0;
        rawChunks = 0;
        conflictingRawCount = conflictingRawChunks = 0;
        ticks = 0;
        lastScanTick = 0;
        lastAttemptEvidenceChunks = 0;
        lastAttemptRawBlocks = 0;
        lastAttemptRawChunks = 0;
        attempted = false;
        cancelRequested = true;
        runningGeneration = -1;
        progress.reset();
        budget.finish();
        found = false;
        oreVeinCheck = null;
        nativeMatched = false;
        status = connected ? "Collecting loaded Overworld chunks" : "Waiting for a world";
    }

    public void loaded(ClientLevel world, ChunkPos position) {
        if (connected && world.dimension().equals(Level.OVERWORLD)) schedule(position, true);
    }

    private void schedule(ChunkPos position) {
        schedule(position, false);
    }

    private void schedule(ChunkPos position, boolean refresh) {
        long key = position.pack();
        if ((refresh || !scanned.contains(key)) && queued.add(key)) queue.add(position);
    }

    public void rescan() {
        Minecraft client = Minecraft.getInstance();
        if (!connected || client.level == null || client.player == null || !client.level.dimension().equals(Level.OVERWORLD)) return;
        int cx = client.player.getBlockX() >> 4;
        int cz = client.player.getBlockZ() >> 4;
        int radius = Math.min(16, client.options.renderDistance().get());
        for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
            if (client.level.hasChunk(x, z)) schedule(new ChunkPos(x, z), true);
        }
        status = "Rescanning loaded chunks";
    }

    public void tick(Minecraft client) {
        if (!Workbench.INSTANCE.collecting()) return;
        if (!connected || found || client.level == null || !client.level.dimension().equals(Level.OVERWORLD)) return;
        if (Workbench.INSTANCE.hasVerifiedSeed()) {
            status = "Seed already verified";
            return;
        }
        if (Workbench.INSTANCE.recovering()) return;
        ticks++;
        if (ticks % 20 == 1 && client.player != null) {
            int cx = client.player.getBlockX() >> 4;
            int cz = client.player.getBlockZ() >> 4;
            int radius = Math.min(16, client.options.renderDistance().get());
            for (int x = cx - radius; x <= cx + radius; x++) for (int z = cz - radius; z <= cz + radius; z++) {
                if (client.level.hasChunk(x, z)) schedule(new ChunkPos(x, z));
            }
        }
        long deadline = System.nanoTime() + 3_000_000;
        while (!running && !queue.isEmpty() && System.nanoTime() < deadline) {
            ChunkPos position = queue.remove();
            queued.remove(position.pack());
            if (client.level.hasChunk(position.x(), position.z())) {
                scan(client.level.getChunk(position.x(), position.z()));
                lastScanTick = ticks;
            }
        }
        if (attempted && !running && (evidenceChunks >= lastAttemptEvidenceChunks + 64 || rawCount >= lastAttemptRawBlocks + 12 && rawChunks >= lastAttemptRawChunks + 4)) attempted = false;
        if (!running && hasConflictingRawData()) {
            status = "Raw block data conflicts with vanilla vein heights; anti-xray or custom generation may be changing chunks";
            return;
        }
        if (!running && !attempted && queue.isEmpty() && ticks - lastScanTick >= 100) {
            if (canTry()) status = "Ready to check loaded block data";
            else if (scanned.size() >= 256) status = rawCount == 0 ? "No raw ore seen; anti-xray or changed ore generation may limit this method" : "Collecting more ore evidence near spawn";
        }
    }

    void scan(ChunkAccess chunk) {
        long[] entries = new long[4096];
        int count = 0;
        int raw = 0;
        int ore = 0;
        int conflictingRaw = 0;
        int minX = chunk.getPos().getMinBlockX();
        int minZ = chunk.getPos().getMinBlockZ();
        BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            int x = minX + dx;
            int z = minZ + dz;
            for (int y = -63; y <= 50; y++) {
                Block block = chunk.getBlockState(position.set(x, y, z)).getBlock();
                if (conflictingRaw(block, y)) conflictingRaw++;
                int type = classify(block, y);
                if (type < 0) continue;
                if (count == entries.length) entries = Arrays.copyOf(entries, entries.length * 2);
                entries[count++] = ((long)x & COORDINATE_MASK) << 38 | ((long)z & COORDINATE_MASK) << 12 | ((long)(y + 64) & 255) << 4 | type;
                if (type == 1) raw++;
                if (type == 2) ore++;
            }
        }
        ChunkSample next = new ChunkSample(Arrays.copyOf(entries, count), raw, ore, conflictingRaw);
        long key = chunk.getPos().pack();
        ChunkSample previous = samples.put(key, next);
        conflictingRawCount += conflictingRaw - (previous == null ? 0 : previous.conflictingRaw());
        conflictingRawChunks += (conflictingRaw > 0 ? 1 : 0) - (previous != null && previous.conflictingRaw() > 0 ? 1 : 0);
        scanned.add(key);
        observationCount += count - (previous == null ? 0 : previous.entries().length);
        rawCount += raw - (previous == null ? 0 : previous.raw());
        oreCount += ore - (previous == null ? 0 : previous.ore());
        evidenceChunks += (count > 0 ? 1 : 0) - (previous != null && previous.entries().length > 0 ? 1 : 0);
        rawChunks += (raw > 0 ? 1 : 0) - (previous != null && previous.raw() > 0 ? 1 : 0);
        if (previous != null && !Arrays.equals(previous.entries(), next.entries())) attempted = false;
        if (!running && !attempted) status = "Scanning loaded chunks";
    }

    static boolean conflictingRaw(Block block, int y) {
        return block == Blocks.RAW_IRON_BLOCK && (y < -60 || y > -8) || block == Blocks.RAW_COPPER_BLOCK && (y < 0 || y > 50);
    }

    static int classify(Block block, int y) {
        if (block == Blocks.BEDROCK) return 0;
        boolean ironHeight = y >= -60 && y <= -8;
        boolean copperHeight = y >= 0 && y <= 50;
        if (block == Blocks.RAW_IRON_BLOCK && ironHeight || block == Blocks.RAW_COPPER_BLOCK && copperHeight) return 1;
        if (block == Blocks.DEEPSLATE_IRON_ORE && ironHeight || block == Blocks.COPPER_ORE && copperHeight) return 2;
        if (block == Blocks.TUFF && ironHeight || block == Blocks.GRANITE && copperHeight) return 3;
        if (y >= -63 && y <= -60 || y >= 1 && y <= 7) {
            if (block == Blocks.DEEPSLATE) return 5;
            if (y >= 1 && block == Blocks.STONE) return 4;
        }
        return -1;
    }

    private static long packedPosition(int x, int y, int z) {
        return ((long)x & COORDINATE_MASK) << 38 | ((long)z & COORDINATE_MASK) << 12 | ((long)(y + 64) & 255) << 4;
    }

    static long[] retainNearbyVeins(long[] entries) {
        return retainNearbyVeins(entries, () -> true);
    }

    static long[] retainNearbyVeins(long[] entries, java.util.function.BooleanSupplier continuing) {
        if (!continuing.getAsBoolean()) throw new java.util.concurrent.CancellationException();
        return compactNearbyVeins(entries.clone(),continuing);
    }

    static long[] compactNearbyVeins(long[] entries, java.util.function.BooleanSupplier continuing) {
        var nearby = new it.unimi.dsi.fastutil.longs.LongOpenHashSet(Math.clamp(entries.length / 128,16,65_536));
        int index = 0;
        for (long entry : entries) {
            if ((index++ & 4095) == 0 && !continuing.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            if ((entry & 15) != 1) continue;
            int x = (int)(entry >> 38);
            int z = (int)(entry << 26 >> 38);
            int y = (int)(entry >> 4 & 255) - 64;
            for (int dx = -4; dx <= 4; dx++) for (int dy = -4; dy <= 4; dy++) for (int dz = -4; dz <= 4; dz++) {
                nearby.add(packedPosition(x + dx, y + dy, z + dz));
            }
        }
        int count = 0;
        index = 0;
        for (long entry : entries) {
            if ((index++ & 4095) == 0 && !continuing.getAsBoolean()) throw new java.util.concurrent.CancellationException();
            int type = (int)(entry & 15);
            if ((type == 2 || type == 3) && !nearby.contains(entry & ~15L)) continue;
            entries[count++] = entry;
        }
        return count == entries.length ? entries : Arrays.copyOf(entries,count);
    }

    static long[] sampleAbundant(long[] entries, int mask) {
        long[] kept = new long[entries.length];
        int count = 0;
        for (long entry : entries) {
            int type = (int)(entry & 15);
            if (type == 0 || type == 4 || type == 5) {
                int x = (int)(entry >> 38);
                int z = (int)(entry << 26 >> 38);
                int y = (int)(entry >> 4 & 255) - 64;
                int hash = x * 0x9e3779b1 ^ z * 0x85ebca77 ^ y * 0xc2b2ae3d;
                hash ^= hash >>> 16;
                hash *= 0x7feb352d;
                hash ^= hash >>> 15;
                if ((hash & mask) != 0) continue;
            }
            kept[count++] = entry;
        }
        return Arrays.copyOf(kept, count);
    }

    private static MemorySegment allocateEntries(long[] entries, Arena arena) {
        MemorySegment blocks = block_entry.allocateArray(entries.length, arena);
        for (int i = 0; i < entries.length; i++) {
            if ((i & 4095) == 0 && !RecoveryWorkload.checkpoint(() -> !shouldContinueSearch())) throw new java.util.concurrent.CancellationException();
            long packed = entries[i];
            long offset = (long)i * block_entry.sizeof();
            blocks.set(ValueLayout.JAVA_INT,offset + block_entry.x$offset(),(int)(packed >> 38));
            blocks.set(ValueLayout.JAVA_INT,offset + block_entry.z$offset(),(int)(packed << 26 >> 38));
            blocks.set(ValueLayout.JAVA_INT,offset + block_entry.y$offset(),(int)(packed >> 4 & 255) - 64);
            blocks.set(ValueLayout.JAVA_INT,offset + block_entry.typ$offset(),(int)(packed & 15));
        }
        return blocks;
    }

    private void startSearch() {
        attempted = true;
        lastAttemptEvidenceChunks = evidenceChunks;
        lastAttemptRawBlocks = rawCount;
        lastAttemptRawChunks = rawChunks;
        running = true;
        budget.start(System.nanoTime());
        status = "Cracking from loaded blocks";
        int runGeneration = ++generation;
        runningGeneration = runGeneration;
        cancelRequested = false;
        progress.start(2, System.nanoTime());
        var evidence = List.copyOf(samples.values());
        LOGGER.info("Starting block recovery: {} chunks, {} raw blocks, {} observations", evidenceChunks, rawCount, observationCount);
        var diagnostic = Workbench.INSTANCE.recoveryContext();
        diagnostic.addProperty("seedHash", expectedHash);
        diagnostic.addProperty("chunks", evidenceChunks);
        diagnostic.addProperty("rawBlocks", rawCount);
        diagnostic.addProperty("sample", "unfiltered collector observations");
        String generationVersion = diagnostic.get("generationVersion").getAsString();
        worker.submit(() -> {
            try {
                nativeFailure.remove();
                nativeMatched = false;
                loadNative();
                if (!shouldContinueSearch()) return;
                int size = evidence.stream().mapToInt(sample -> sample.entries().length).reduce(0,Math::addExact);
                long[] collected = new long[size];
                int offset = 0;
                for (ChunkSample sample : evidence) {
                    if (!shouldContinueSearch()) return;
                    System.arraycopy(sample.entries(),0,collected,offset,sample.entries().length);
                    offset += sample.entries().length;
                }
                long[] fullEntries = compactNearbyVeins(collected,() -> RecoveryWorkload.checkpoint(() -> !shouldContinueSearch()));
                collected = null;
                oreVeinCheck = OreVeinCheck.fromEntries(fullEntries, generationVersion);
                try (Arena arena = Arena.ofConfined()) {
                    MemorySegment blocks = allocateEntries(fullEntries, arena);
                    int[] modes = {63, 31};
                    for (int index = 0; index < modes.length; index++) {
                        if (generation != runGeneration || nativeMatched || cancelRequested || !budget.allows(System.nanoTime()) || Workbench.INSTANCE.hasVerifiedSeed()) break;
                        nativeFailure.remove();
                        String description = "All collected chunks" + ((modes[index] & 32) != 0 ? ", cautious checks" : ", full vein checks");
                        progress.beginPass(index + 1, description, System.nanoTime());
                        LOGGER.info("Block recovery pass {}/{}: {}, {} observations", index + 1, modes.length, description, fullEntries.length);
                        BedrockNative.bedrockseed$crack(fullEntries.length, blocks, modes[index]);
                        var completed = searchProgress();
                        String result = nativeMatched ? "Seed matched the world hash" : cancelRequested ? "Search stopped" : nativeFailure.get() == null ? "No consistent random state" : nativeFailure.get();
                        if (completed.totalCandidates() > 0 && !nativeMatched && !cancelRequested) result = "No seed matched the world hash; the block sample may contain conflicting evidence";
                        progress.passResult(result);
                        LOGGER.info("Block recovery pass {}/{} finished in {}s: {}, {} / {} candidates checked", completed.pass(), completed.passCount(), completed.passSeconds(), result, completed.checkedCandidates(), completed.totalCandidates());
                        if (completed.totalCandidates() > 0 && !nativeMatched) {
                            nativeFailure.set(result);
                            break;
                        }
                    }
                }
                if (generation == runGeneration && !found) {
                    String failure = nativeFailure.get();
                    if (Workbench.INSTANCE.hasVerifiedSeed()) status = "Seed verified from nearby structures";
                    else if (cancelRequested) status = "Search stopped";
                    else if (budget.expired()) status = "Search reached its 10-minute limit; gameplay load, hidden blocks, or mismatched generation data may delay recovery";
                    else if (nativeMatched) status = "Seed found; verifying";
                    else status = failure == null ? "No matching seed from this block sample" : failure;
                }
            } catch (java.util.concurrent.CancellationException ignored) {
                if (generation == runGeneration) status = "Search stopped";
            } catch (Throwable error) {
                if (generation == runGeneration) status = "Block-pattern search failed: " + error.getClass().getSimpleName();
                LOGGER.error("Block-pattern recovery failed", error);
            } finally {
                if (generation == runGeneration && !nativeMatched && !found && !cancelRequested) {
                    try {
                        diagnostic.addProperty("failure", status);
                        diagnostic.addProperty("pass", progress.view(System.nanoTime()).pass());
                        RecoveryDiagnostics.writeChunks(FabricLoader.getInstance().getConfigDir().resolve("seedy/recovery"),evidence.stream().map(ChunkSample::entries).toList(),diagnostic);
                        LOGGER.info("Saved block recovery data to config/seedy/recovery for command-line diagnosis");
                        status += ". Diagnostic saved in config/seedy/recovery";
                    } catch (IOException | RuntimeException error) { LOGGER.warn("Could not save block recovery diagnostic", error); }
                }
                nativeFailure.remove();
                if (generation == runGeneration) progress.finish(System.nanoTime());
                budget.finish();
                if (cancelRequested && generation == runGeneration) attempted = false;
                runningGeneration = -1;
                running = false;
            }
        });
    }

    public void tryCurrentData() {
        if (!connected || expectedHash == null) throw new IllegalArgumentException("Join a world first.");
        if (Workbench.INSTANCE.hasVerifiedSeed()) throw new IllegalArgumentException("The seed is already verified.");
        if (running || Workbench.INSTANCE.collecting() && !queue.isEmpty()) throw new IllegalArgumentException("Wait for loaded chunks to finish scanning.");
        if (hasConflictingRawData()) throw new IllegalArgumentException("Raw blocks appear outside vanilla vein heights across several chunks. Anti-xray, placed blocks, or custom generation may be changing this data.");
        if (attempted) throw new IllegalArgumentException("The loaded block sample was already checked. Load more chunks first.");
        if (evidenceChunks < MIN_EVIDENCE_CHUNKS || rawCount == 0) throw new IllegalArgumentException("There is not enough nearby block data to check yet.");
        startSearch();
    }

    public void stopSearch() {
        cancelRequested = true;
        status = "Stopping block search";
    }

    private static synchronized void loadNative() throws IOException {
        if (nativeLoaded) return;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        String platform = os.contains("mac") ? (arch.equals("aarch64") || arch.equals("arm64") ? "macos-arm64" : "macos-x86_64") : os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64")) ? "windows-x86_64" : null;
        if (platform == null) throw new IOException("This build supports macOS and Windows x64");
        String z3Name = platform.startsWith("macos") ? "libz3.dylib" : "libz3.dll";
        String solverName = platform.startsWith("macos") ? "libseedycrack.dylib" : "seedycrack.dll";
        String[] dependencies = platform.startsWith("macos") ? new String[0] : new String[]{"libgcc_s_seh-1.dll", "libwinpthread-1.dll", "libstdc++-6.dll", "libgmp-10.dll", "libgomp-1.dll"};
        for (String name : dependencies) NativeLibraries.loadIfPresent(platform, name);
        NativeLibraries.load(platform, z3Name);
        NativeLibraries.load(platform, solverName);
        BedrockNative.bedrockseed$crack$address();
        schedulingHandle = Linker.nativeLinker().downcallHandle(SymbolLookup.loaderLookup().findOrThrow("seedy_recovery_budget"),FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
        updateWorkBudget();
        nativeLoaded = true;
    }

    public static void updateWorkBudget() {
        MethodHandle handle = schedulingHandle;
        if (handle == null || !INSTANCE.running) return;
        int duty = RecoveryWorkload.dutyPercent();
        if (duty == lastDuty) return;
        try { handle.invokeExact(duty,RecoveryWorkload.workerCount()); lastDuty = duty; }
        catch (Throwable ignored) { }
    }

    public static void sendUpdate(String key, boolean logOnly, Object... values) {
        if ("commands.bedrockcrack.searchProgress".equals(key)) {
            if (values.length >= 2 && values[0] instanceof Number checked && values[1] instanceof Number total && shouldContinueSearch()) {
                INSTANCE.progress.candidates(checked.longValue(), total.longValue(), System.nanoTime());
            }
            return;
        }
        if (!"commands.bedrockcrack.foundWorldSeed".equals(key)) {
            if (!shouldContinueSearch() || INSTANCE.found) return;
            if ("commands.bedrockcrack.rawEvidence".equals(key) && values.length >= 2) LOGGER.info("Block recovery raw evidence: {} positions, {} initial exact checks", values[0], values[1]);
            if ("commands.bedrockcrack.baseEvidenceMismatch".equals(key) && values.length >= 2) LOGGER.info("Block recovery state rejected: {} mismatches in {} reliable bedrock/deepslate observations", values[0], values[1]);
            if ("commands.bedrockcrack.relaxingOreEvidence".equals(key) || "commands.bedrockcrack.solverTimeLimit".equals(key)) {
                LOGGER.info("Block recovery pass {}: {} ({} ore pattern groups relaxed)", INSTANCE.searchProgress().pass(), key.endsWith("solverTimeLimit") ? "solver time limit reached" : "ordinary ore patterns did not fit", values.length > 0 ? values[0] : 0);
            }
            if ("commands.bedrockcrack.Z3Unsat".equals(key)) INSTANCE.nativeFailure.set("This pass found no consistent random state");
            if ("commands.bedrockcrack.solverBudgetExceeded".equals(key)) INSTANCE.nativeFailure.set("Random-state solver reached its 3-minute limit for this pass");
            if ("commands.bedrockcrack.Z3Failed".equals(key) && INSTANCE.nativeFailure.get() == null) INSTANCE.nativeFailure.set("Could not resolve these block patterns");
            if ("commands.bedrockcrack.stateValidationFailed".equals(key)) INSTANCE.nativeFailure.set("No random state passed the block evidence checks");
            if (INSTANCE.progress.update(key, System.nanoTime())) {
                var view = INSTANCE.searchProgress();
                LOGGER.info("Block recovery pass {}/{}, stage {}/8: {}", view.pass(), view.passCount(), view.stage(), view.phase());
            }
            return;
        }
        if (values.length == 0 || !(values[0] instanceof Number number)) return;
        int runGeneration = INSTANCE.runningGeneration;
        long seed = number.longValue();
        Minecraft.getInstance().execute(() -> {
            if (INSTANCE.generation == runGeneration && !INSTANCE.cancelRequested && Workbench.INSTANCE.acceptBlockSeed(seed)) {
                INSTANCE.found = true;
                INSTANCE.status = "Seed recovered from loaded blocks";
            }
        });
    }

    public static boolean matchesWorldSeed(long seed) {
        if (!shouldContinueSearch()) return false;
        Long expected = INSTANCE.expectedHash;
        boolean matched = expected != null && SeedHash.hash(seed) == expected;
        if (matched) INSTANCE.nativeMatched = true;
        return matched;
    }

    public static boolean matchesRandomState(long low, long high) {
        OreVeinCheck check = INSTANCE.oreVeinCheck;
        return shouldContinueSearch() && check != null && check.matches(low, high);
    }

    public static boolean shouldContinueSearch() {
        return INSTANCE.running && !INSTANCE.cancelRequested && INSTANCE.runningGeneration == INSTANCE.generation && INSTANCE.budget.allows(System.nanoTime()) && !Workbench.INSTANCE.hasVerifiedSeed();
    }

    public int scannedChunks() { return scanned.size(); }
    public int evidenceChunks() { return evidenceChunks; }
    public int rawBlocks() { return rawCount; }
    public int oreCandidates() { return oreCount; }
    public int rawChunks() { return rawChunks; }
    public int conflictingRawBlocks() { return conflictingRawCount; }
    public int conflictingRawChunks() { return conflictingRawChunks; }
    public boolean hasConflictingRawData() { return conflictingRawCount >= 8 && conflictingRawChunks >= 4; }
    private record ChunkSample(long[] entries, int raw, int ore, int conflictingRaw) { }
    public double collectionProgress() { return rawCount == 0 ? 0 : Math.min(1, (double)evidenceChunks / MIN_EVIDENCE_CHUNKS); }
    public boolean canTry() { return connected && !running && !attempted && (!Workbench.INSTANCE.collecting() || queue.isEmpty()) && evidenceChunks >= MIN_EVIDENCE_CHUNKS && rawCount > 0 && !hasConflictingRawData() && !Workbench.INSTANCE.hasVerifiedSeed(); }
    public boolean running() { return running; }
    public BlockSearchProgress.View searchProgress() { return progress.view(System.nanoTime()); }
    public String status() { return running && !cancelRequested ? searchProgress().phase() : status; }
    @Override public void close() { cancelRequested = true; runningGeneration = -1; worker.shutdown(); }
}
