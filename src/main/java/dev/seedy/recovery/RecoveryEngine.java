package dev.seedy.recovery;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;

public final class RecoveryEngine implements AutoCloseable {
    private final int workerCount = RecoveryWorkload.workerCount();
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(RecoveryWorkload.threads("seedy-structure-coordinator"));
    private final ExecutorService workers = Executors.newFixedThreadPool(workerCount, RecoveryWorkload.threads("seedy-structure-worker"));
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong checked = new AtomicLong();
    private volatile long total;
    private volatile String stage = "Idle";
    private volatile boolean running;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private volatile Future<?> currentTask;

    public boolean running() { return running || activeTasks.get() > 0; }
    public String stage() { return !running && activeTasks.get() > 0 ? "Stopping search" : stage; }
    public double progress() { return total == 0 ? 0 : Math.min(1, (double)checked.get() / total); }
    public static double evidenceBits(List<Observation> observations) { return observations.stream().mapToDouble(o -> 2 * Math.log(o.rule().bound()) / Math.log(2)).sum(); }

    public synchronized void start(List<Observation> observations, long hash, LongConsumer recovered) {
        if (observations.size() < 5 || evidenceBits(observations) < 40) throw new IllegalArgumentException("Collect at least five independent structures and 40 evidence bits.");
        if (running()) throw new IllegalArgumentException("Wait for the current search to stop.");
        cancel();
        long task = generation.get();
        running = true;
        stage = "Checking rejected random draws";
        var constraints = observations.stream().map(o -> o.rule().constrain(o.chunkX(), o.chunkZ())).toList();
        var lifting = constraints.stream().sorted(java.util.Comparator.comparingInt((PlacementRule.Constraint c) -> Integer.lowestOneBit(c.bound())).reversed()).limit(16).toList();
        currentTask = coordinator.submit(() -> {
            activeTasks.incrementAndGet();
            var found = new AtomicReference<Long>();
            BooleanSupplier stopped = () -> generation.get() != task || found.get() != null || Thread.currentThread().isInterrupted();
            BooleanSupplier cancelled = () -> !RecoveryWorkload.checkpoint(stopped);
            var jobs = new ArrayList<Future<?>>();
            try {
                if (cancelled.getAsBoolean()) return;
                scanRejectedDraws(lifting, cancelled, candidate -> {
                    if (!matches(candidate, constraints)) return false;
                    Long seed = SeedHash.recover(candidate, hash, cancelled);
                    if (seed == null) return false;
                    found.set(seed);
                    return true;
                });
                if (generation.get() != task) return;
                if (found.get() != null) { stage = "Seed verified"; recovered.accept(found.get()); return; }
                stage = "Filtering lower bits";
                var lowerSeeds = lowerCandidates(lifting, cancelled);
                if (cancelled.getAsBoolean()) return;
                if (lowerSeeds.isEmpty()) { stage = "No match; check observations, world generation version, or Paper placement salts"; return; }
                if (lowerSeeds.size() > 16) { stage = "Collect more structures to narrow the search. Trial chambers need more observations."; return; }
                total = lowerSeeds.size() * (1L << 29);
                checked.set(0);
                stage = "Searching structure seeds";
                for (int worker = 0; worker < workerCount; worker++) {
                    long start = (1L << 29) * worker / workerCount;
                    long end = (1L << 29) * (worker + 1) / workerCount;
                    jobs.add(workers.submit(() -> {
                        activeTasks.incrementAndGet();
                        try {
                            for (long lower : lowerSeeds) {
                                for (long upper = start; upper < end; upper++) {
                                    if ((upper & 4095) == 0) {
                                        if (cancelled.getAsBoolean()) return;
                                        checked.addAndGet(Math.min(4096, end - upper));
                                    }
                                    long candidate = (upper << 19) | lower;
                                    if (!matches(candidate, constraints)) continue;
                                    Long seed = SeedHash.recover(candidate, hash, cancelled);
                                    if (seed != null) { found.compareAndSet(null, seed); return; }
                                }
                            }
                        } finally { activeTasks.decrementAndGet(); }
                    }));
                }
                for (var job : jobs) job.get();
                if (generation.get() != task) return;
                if (found.get() == null) stage = "No matching seed; Paper placement salts or changed generation may conflict";
                else { stage = "Seed verified"; recovered.accept(found.get()); }
            } catch (Exception e) {
                if (generation.get() == task) stage = "Search stopped: " + e.getClass().getSimpleName();
            } finally {
                for (var job : jobs) job.cancel(true);
                if (generation.get() == task) running = false;
                activeTasks.decrementAndGet();
            }
        });
    }

    public static List<Long> lowerCandidates(List<PlacementRule.Constraint> constraints, BooleanSupplier cancelled) {
        var seeds = new ArrayList<Long>();
        for (long seed = 0; seed < (1L << 19); seed++) {
            if ((seed & 4095) == 0 && cancelled.getAsBoolean()) break;
            boolean valid = true;
            for (var constraint : constraints) if (!constraint.matchesLower(seed)) { valid = false; break; }
            if (valid) seeds.add(seed);
        }
        return seeds;
    }

    public static boolean matches(long seed, List<PlacementRule.Constraint> constraints) {
        for (var constraint : constraints) if (!constraint.matches(seed)) return false;
        return true;
    }

    public static void scanRejectedDraws(List<PlacementRule.Constraint> constraints, BooleanSupplier cancelled, LongPredicate accept) {
        for (var constraint : constraints) {
            long limit = (1L << 31) - (1L << 31) % constraint.bound();
            for (long bits = limit; bits < (1L << 31); bits++) {
                for (long low = 0; low < (1L << 17); low++) {
                    if ((low & 4095) == 0 && cancelled.getAsBoolean()) return;
                    long previous = PlacementRule.previousState((bits << 17) | low);
                    long candidate = ((previous ^ 0x5DEECE66DL) - constraint.offset()) & PlacementRule.MASK;
                    if (matches(candidate, constraints) && accept.test(candidate)) return;
                    previous = PlacementRule.previousState(previous);
                    candidate = ((previous ^ 0x5DEECE66DL) - constraint.offset()) & PlacementRule.MASK;
                    if (matches(candidate, constraints) && accept.test(candidate)) return;
                }
            }
        }
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        running = false;
        stage = "Idle";
        checked.set(0);
        total = 0;
    }

    @Override public void close() { cancel(); coordinator.shutdownNow(); workers.shutdownNow(); }
}
