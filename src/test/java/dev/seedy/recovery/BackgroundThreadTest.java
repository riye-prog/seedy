package dev.seedy.recovery;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static java.lang.foreign.ValueLayout.*;
import static org.junit.jupiter.api.Assertions.*;

class BackgroundThreadTest {
    private static int[] nativePriority() throws Throwable {
        var linker = Linker.nativeLinker();
        if (System.getProperty("os.name").startsWith("Mac")) {
            var lookup = linker.defaultLookup();
            var self = linker.downcallHandle(lookup.findOrThrow("pthread_self"),FunctionDescriptor.of(ADDRESS));
            var query = linker.downcallHandle(lookup.findOrThrow("pthread_get_qos_class_np"),FunctionDescriptor.of(JAVA_INT,ADDRESS,ADDRESS,ADDRESS));
            try (var arena = Arena.ofConfined()) {
                var qos = arena.allocate(JAVA_INT);
                var relative = arena.allocate(JAVA_INT);
                assertEquals(0,(int)query.invokeExact((MemorySegment)self.invokeExact(),qos,relative));
                return new int[]{qos.get(JAVA_INT,0),relative.get(JAVA_INT,0)};
            }
        }
        var lookup = SymbolLookup.libraryLookup("kernel32.dll",Arena.global());
        var self = linker.downcallHandle(lookup.findOrThrow("GetCurrentThread"),FunctionDescriptor.of(ADDRESS));
        var query = linker.downcallHandle(lookup.findOrThrow("GetThreadPriority"),FunctionDescriptor.of(JAVA_INT,ADDRESS));
        return new int[]{0,(int)query.invokeExact((MemorySegment)self.invokeExact())};
    }

    @Test void lowersOnlyTheWorkerPriorityAndPreservesItsMacSchedulingClass() throws Throwable {
        String os = System.getProperty("os.name");
        org.junit.jupiter.api.Assumptions.assumeTrue(os.startsWith("Mac") || os.startsWith("Windows"));
        int[] caller = nativePriority();
        var failure = new AtomicReference<Throwable>();
        var worker = Thread.ofPlatform().start(() -> {
            try {
                int[] previous = nativePriority();
                assertTrue(BackgroundThread.apply());
                int[] current = nativePriority();
                assertEquals(previous[0],current[0]);
                assertEquals(os.startsWith("Mac") ? -15 : Math.min(-1,previous[1]),current[1]);
            } catch (Throwable error) { failure.set(error); }
        });
        worker.join(2000);
        assertFalse(worker.isAlive());
        if (failure.get() != null) throw failure.get();
        assertArrayEquals(caller,nativePriority());
    }
}
