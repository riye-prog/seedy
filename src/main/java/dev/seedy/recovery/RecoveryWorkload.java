package dev.seedy.recovery;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

public final class RecoveryWorkload {
    private static final FrameBudget BUDGET = new FrameBudget();
    private static final ThreadLocal<long[]> SLICE = ThreadLocal.withInitial(() -> new long[]{System.nanoTime()});
    private static long frameStarted;

    public static int workerCount() { return workersFor(Runtime.getRuntime().availableProcessors()); }
    public static int workersFor(int processors) { return Math.clamp(processors / 2,1,4); }
    public static int dutyPercent() { return BUDGET.dutyPercent(System.nanoTime()); }
    public static void active(boolean value) { BUDGET.active(value); }
    public static void frameStarted() { frameStarted = System.nanoTime(); }
    public static void frameFinished() {
        long now = System.nanoTime();
        if (frameStarted != 0) BUDGET.frame(now - frameStarted,now);
    }

    public static ThreadFactory threads(String name) {
        var count = new AtomicInteger();
        return task -> {
            var thread = Thread.ofPlatform().daemon().name(name + "-" + count.incrementAndGet()).unstarted(() -> {
                BackgroundThread.apply();
                task.run();
            });
            thread.setPriority(Math.max(Thread.MIN_PRIORITY,Thread.NORM_PRIORITY - 2));
            return thread;
        };
    }

    public static boolean checkpoint(BooleanSupplier cancelled) {
        if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return false;
        long[] slice = SLICE.get();
        long now = System.nanoTime(), work = now - slice[0];
        if (work < 2_000_000) return true;
        int duty = dutyPercent();
        long rest = Math.min(8_000_000,work * (100 - duty) / duty);
        long end = now + rest;
        while (now < end) {
            LockSupport.parkNanos(Math.min(1_000_000,end - now));
            if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return false;
            now = System.nanoTime();
        }
        slice[0] = now;
        return true;
    }
}
