package dev.seedy;

import bedrock.generated.BedrockNative;
import com.google.gson.JsonObject;
import dev.seedy.recovery.RecoveryBudget;
import dev.seedy.recovery.RecoveryWorkload;
import dev.seedy.recovery.SeedHash;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

public final class RecoveryResponsivenessProbe {
    private static volatile long checksum;

    private static void set(String name, Object value) throws ReflectiveOperationException {
        var field = BedrockRecovery.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(BedrockRecovery.INSTANCE,value);
    }

    private static Object get(String name) throws ReflectiveOperationException {
        var field = BedrockRecovery.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(BedrockRecovery.INSTANCE);
    }

    private static void frame() {
        RecoveryWorkload.frameStarted();
        long until = System.nanoTime() + 1_500_000;
        long value = checksum;
        do {
            for (int i = 0; i < 512; i++) value = Long.rotateLeft(value + i,13) * 0x9e3779b97f4a7c15L;
        } while (System.nanoTime() < until);
        checksum = value;
        RecoveryWorkload.frameFinished();
    }

    public static void main(String[] args) throws Throwable {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Path library = Path.of(args[1]).toAbsolutePath();
        boolean limited = Boolean.parseBoolean(args[4]);
        System.load(library.resolveSibling("libz3.dylib").toString());
        System.load(library.toString());
        var linker = Linker.nativeLinker();
        var symbols = SymbolLookup.loaderLookup();
        var nativeBudget = limited ? linker.downcallHandle(symbols.findOrThrow("seedy_recovery_budget"),FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT)) : null;
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        var input = ByteBuffer.wrap(data);
        int[] entries = new int[data.length / 4];
        var raw = new ArrayList<net.minecraft.core.BlockPos>();
        for (int i = 0; i < entries.length; i += 4) {
            for (int j = 0; j < 4; j++) entries[i + j] = input.getInt();
            if (entries[i + 3] == 1) raw.add(new net.minecraft.core.BlockPos(entries[i],entries[i + 1],entries[i + 2]));
        }
        set("expectedHash",SeedHash.hash(Long.parseLong(args[2])));
        set("oreVeinCheck",new OreVeinCheck(raw,args.length > 6 ? args[6] : "26.2"));
        set("nativeMatched",false);
        set("runningGeneration",get("generation"));
        set("cancelRequested",false);
        set("running",true);
        var budget = (RecoveryBudget)get("budget");
        budget.start(System.nanoTime());
        var failure = new AtomicReference<Throwable>();
        try (var arena = Arena.ofShared()) {
            var blocks = arena.allocateFrom(ValueLayout.JAVA_INT,entries);
            Runnable solve = () -> {
                try { BedrockNative.bedrockseed$crack(entries.length / 4,blocks,63); }
                catch (Throwable error) { failure.set(error); }
            };
            var worker = limited ? RecoveryWorkload.threads("probe-recovery").newThread(solve) : Thread.ofPlatform().name("probe-baseline").unstarted(solve);
            long warmup = System.nanoTime();
            for (int i = 0; i < 100; i++) {
                frame();
                warmup += 8_333_333;
                long pause = warmup - System.nanoTime();
                if (pause > 0) LockSupport.parkNanos(pause);
            }
            RecoveryWorkload.active(limited);
            var bean = ManagementFactory.getThreadMXBean();
            long threadsBefore = bean.getTotalStartedThreadCount();
            long started = System.nanoTime();
            long deadline = started + Long.parseLong(args[3]) * 1_000_000_000L;
            long next = started;
            var delays = new ArrayList<Long>();
            var firstWindow = new ArrayList<Long>();
            long firstWindowThreads = 0;
            int minimumDuty = 100;
            long dutySum = 0, dutySamples = 0, lastLog = started;
            worker.start();
            while (worker.isAlive() && System.nanoTime() < deadline) {
                long now = System.nanoTime();
                if (now < next) { LockSupport.parkNanos(next - now); continue; }
                delays.add(Math.max(0,now - next));
                if (now - started < 30_000_000_000L) firstWindow.add(Math.max(0,now - next));
                else if (firstWindowThreads == 0) firstWindowThreads = bean.getTotalStartedThreadCount() - threadsBefore;
                frame();
                if (limited) {
                    int duty = RecoveryWorkload.dutyPercent();
                    minimumDuty = Math.min(minimumDuty,duty);
                    dutySum += duty;
                    dutySamples++;
                    nativeBudget.invokeExact(duty,RecoveryWorkload.workerCount());
                }
                if (now - lastLog >= 15_000_000_000L) {
                    System.out.println("probe seconds=" + (now - started) / 1_000_000_000L + " averageDuty=" + (dutySamples == 0 ? 100 : dutySum / dutySamples));
                    lastLog = now;
                }
                next += 8_333_333;
                if (System.nanoTime() - next > 50_000_000) next = System.nanoTime();
            }
            long stoppedAt = System.nanoTime();
            boolean timedOut = worker.isAlive();
            if (timedOut) BedrockRecovery.INSTANCE.stopSearch();
            worker.join(2000);
            long stopNanos = System.nanoTime() - stoppedAt;
            if (worker.isAlive()) throw new AssertionError("Native search did not stop");
            if (failure.get() != null) throw failure.get();
            delays.sort(Long::compare);
            firstWindow.sort(Long::compare);
            var result = new JsonObject();
            result.addProperty("limited",limited);
            result.addProperty("seconds",(System.nanoTime() - started) / 1e9);
            result.addProperty("hashVerified",(boolean)get("nativeMatched"));
            result.addProperty("frames",delays.size());
            result.addProperty("p95FrameDelayMs",delays.get((int)(delays.size() * .95)) / 1e6);
            result.addProperty("p99FrameDelayMs",delays.get((int)(delays.size() * .99)) / 1e6);
            result.addProperty("maxFrameDelayMs",delays.getLast() / 1e6);
            result.addProperty("first30sP95DelayMs",firstWindow.get((int)(firstWindow.size() * .95)) / 1e6);
            result.addProperty("first30sP99DelayMs",firstWindow.get((int)(firstWindow.size() * .99)) / 1e6);
            result.addProperty("first30sThreads",firstWindowThreads == 0 ? bean.getTotalStartedThreadCount() - threadsBefore : firstWindowThreads);
            result.addProperty("threadsStarted",bean.getTotalStartedThreadCount() - threadsBefore);
            result.addProperty("minimumDuty",minimumDuty);
            result.addProperty("averageDuty",dutySamples == 0 ? 100 : (double)dutySum / dutySamples);
            result.addProperty("stopMs",stopNanos / 1e6);
            if (limited) {
                var attached = linker.downcallHandle(symbols.findOrThrow("seedy_recovery_attachments"),FunctionDescriptor.of(ValueLayout.JAVA_LONG));
                result.addProperty("nativeAttachments",(long)attached.invokeExact());
                result.addProperty("workers",RecoveryWorkload.workerCount());
            }
            Files.writeString(Path.of(args[5]),result.toString());
            System.out.println(result);
            if (limited && !timedOut && !(boolean)get("nativeMatched")) throw new AssertionError("Search returned without the expected seed");
        } finally {
            BedrockRecovery.INSTANCE.stopSearch();
            set("running",false);
            budget.finish();
            RecoveryWorkload.active(false);
        }
    }
}
