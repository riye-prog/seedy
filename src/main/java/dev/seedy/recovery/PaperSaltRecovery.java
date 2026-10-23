package dev.seedy.recovery;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;

public final class PaperSaltRecovery implements AutoCloseable {
    private static final long SALT_SPACE = 1L << 32;
    private final int workerCount = Math.max(1, Math.min(6, Runtime.getRuntime().availableProcessors() - 2));
    private final ExecutorService coordinator = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    private final ExecutorService workers = Executors.newFixedThreadPool(workerCount, Thread.ofPlatform().daemon().factory());
    private final AtomicLong generation = new AtomicLong();
    private final AtomicLong checked = new AtomicLong();
    private volatile boolean running;
    private final AtomicInteger activeTasks = new AtomicInteger();
    private volatile Future<?> currentTask;
    private volatile String status = "Idle";

    public boolean running() { return running || activeTasks.get() > 0; }
    public String status() { return !running && activeTasks.get() > 0 ? "Stopping search" : status; }
    public double progress() { return Math.min(1, (double)checked.get() / SALT_SPACE); }

    public synchronized void start(long worldSeed, PlacementRule rule, List<Observation> observations, IntConsumer recovered) {
        if (rule.bound() <= 1) throw new IllegalArgumentException("This structure uses a different Paper placement rule.");
        if (observations.isEmpty()) throw new IllegalArgumentException("Select at least one start chunk for this structure.");
        if (observations.stream().anyMatch(observation -> !observation.structure().equals(rule.id()))) throw new IllegalArgumentException("Use one structure set at a time.");
        if (running()) throw new IllegalArgumentException("Wait for the current search to stop.");
        cancel();
        long task = generation.get();
        checked.set(0);
        running = true;
        status = "Checking 32-bit Paper placement salts";
        var starts = List.copyOf(observations);
        currentTask = coordinator.submit(() -> {
            activeTasks.incrementAndGet();
            var hits = new AtomicInteger();
            var first = new AtomicInteger();
            var jobs = new ArrayList<Future<?>>();
            try {
                if (generation.get() != task || Thread.currentThread().isInterrupted()) return;
                for (int worker = 0; worker < workerCount; worker++) {
                    long begin = SALT_SPACE * worker / workerCount;
                    long end = SALT_SPACE * (worker + 1) / workerCount;
                    jobs.add(workers.submit(() -> {
                        activeTasks.incrementAndGet();
                        try {
                            for (long value = begin; value < end; value++) {
                                if ((value & 8191) == 0) {
                                    if (generation.get() != task || hits.get() > 1 || Thread.currentThread().isInterrupted()) return;
                                    checked.addAndGet(Math.min(8192, end - value));
                                }
                                int salt = (int)value;
                                boolean matches = true;
                                for (var observation : starts) {
                                    if (!rule.matchesWithSalt(worldSeed, salt, observation.chunkX(), observation.chunkZ())) { matches = false; break; }
                                }
                                if (matches) {
                                    if (hits.getAndIncrement() == 0) first.set(salt);
                                    else return;
                                }
                            }
                        } finally { activeTasks.decrementAndGet(); }
                    }));
                }
                for (var job : jobs) job.get();
                if (generation.get() != task) return;
                if (hits.get() == 0) status = "No Paper salt matches these start chunks; check version and observations";
                else if (hits.get() > 1) status = "Multiple salts match; select more starts of this structure";
                else {
                    status = "Paper placement salt recovered";
                    recovered.accept(first.get());
                }
            } catch (Exception error) {
                if (generation.get() == task) status = "Paper salt search failed: " + error.getClass().getSimpleName();
            } finally {
                for (var job : jobs) job.cancel(true);
                if (generation.get() == task) running = false;
                activeTasks.decrementAndGet();
            }
        });
    }

    public synchronized void cancel() {
        generation.incrementAndGet();
        Future<?> task = currentTask;
        if (task != null) task.cancel(true);
        running = false;
        status = "Idle";
        checked.set(0);
    }

    @Override public void close() { cancel(); coordinator.shutdownNow(); workers.shutdownNow(); }
}
