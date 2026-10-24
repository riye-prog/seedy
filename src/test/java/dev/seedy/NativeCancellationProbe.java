package dev.seedy;

import bedrock.generated.BedrockNative;
import dev.seedy.recovery.RecoveryBudget;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

public final class NativeCancellationProbe {
    private static void set(String name, Object value) throws ReflectiveOperationException {
        var field = BedrockRecovery.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(BedrockRecovery.INSTANCE,value);
    }

    public static void main(String[] args) throws Exception {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        Path library = Path.of(args[1]).toAbsolutePath();
        System.load(library.resolveSibling(System.getProperty("os.name").startsWith("Windows") ? "libz3.dll" : "libz3.dylib").toString());
        System.load(library.toString());
        if (args.length > 3) {
            var nativeBudget = java.lang.foreign.Linker.nativeLinker().downcallHandle(java.lang.foreign.SymbolLookup.loaderLookup().findOrThrow("seedy_recovery_budget"),java.lang.foreign.FunctionDescriptor.ofVoid(ValueLayout.JAVA_INT,ValueLayout.JAVA_INT));
            try { nativeBudget.invokeExact(Integer.parseInt(args[3]),dev.seedy.recovery.RecoveryWorkload.workerCount()); }
            catch (Throwable error) { throw new IllegalStateException(error); }
        }
        byte[] data = Files.readAllBytes(Path.of(args[0]));
        ByteBuffer source = ByteBuffer.wrap(data);
        int[] entries = new int[data.length / 4];
        for (int index = 0; index < entries.length; index++) entries[index] = source.getInt();
        var instance = BedrockRecovery.INSTANCE;
        var budgetField = BedrockRecovery.class.getDeclaredField("budget");
        budgetField.setAccessible(true);
        var budget = (RecoveryBudget)budgetField.get(instance);
        var generationField = BedrockRecovery.class.getDeclaredField("generation");
        generationField.setAccessible(true);
        try (var arena = Arena.ofConfined()) {
            var blocks = arena.allocateFrom(ValueLayout.JAVA_INT,entries);
            for (String delay : args[2].split(":")) {
                set("runningGeneration",generationField.get(instance));
                set("cancelRequested",false);
                set("running",true);
                budget.start(System.nanoTime());
                var stopTime = new AtomicLong();
                var stopper = Thread.ofPlatform().start(() -> {
                    try { Thread.sleep(Long.parseLong(delay)); }
                    catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                    stopTime.set(System.nanoTime());
                    instance.stopSearch();
                });
                BedrockNative.bedrockseed$crack(entries.length / 4,blocks,63);
                long stopped = System.nanoTime();
                stopper.join();
                long latency = Math.max(0,(stopped - stopTime.get()) / 1_000_000);
                System.out.println("Native stop delay=" + delay + "ms latency=" + latency + "ms observations=" + entries.length / 4);
                if (stopTime.get() == 0 || latency > 1000) throw new AssertionError("Native workers exceeded the stop deadline");
                long idleStarted = System.nanoTime();
                BedrockNative.bedrockseed$crack(entries.length / 4,blocks,63);
                if (System.nanoTime() - idleStarted > 100_000_000) throw new AssertionError("A stopped search restarted");
                set("running",false);
                budget.finish();
            }
        } finally {
            instance.stopSearch();
            set("running",false);
            budget.finish();
        }
    }
}
