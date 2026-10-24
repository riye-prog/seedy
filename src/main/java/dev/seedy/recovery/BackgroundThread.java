package dev.seedy.recovery;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Locale;
import static java.lang.foreign.ValueLayout.*;

final class BackgroundThread {
    private static MethodHandle current, priority, lower;
    private static boolean mac;
    private static boolean available;

    static {
        try {
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            var linker = Linker.nativeLinker();
            var lookup = linker.defaultLookup();
            mac = os.contains("mac");
            if (mac) lower = linker.downcallHandle(lookup.findOrThrow("pthread_set_qos_class_self_np"),FunctionDescriptor.of(JAVA_INT,JAVA_INT,JAVA_INT));
            else if (os.contains("win")) {
                lookup = SymbolLookup.libraryLookup("kernel32.dll",Arena.global());
                current = linker.downcallHandle(lookup.findOrThrow("GetCurrentThread"),FunctionDescriptor.of(JAVA_LONG));
                priority = linker.downcallHandle(lookup.findOrThrow("GetThreadPriority"),FunctionDescriptor.of(JAVA_INT,JAVA_LONG));
                lower = linker.downcallHandle(lookup.findOrThrow("SetThreadPriority"),FunctionDescriptor.of(JAVA_INT,JAVA_LONG,JAVA_INT));
            }
            available = lower != null;
        } catch (Throwable ignored) { available = false; }
    }

    static boolean apply() {
        if (!available) return false;
        try {
            if (mac) return (int)lower.invokeExact(17,0) == 0;
            long thread = (long)current.invokeExact();
            int previous = (int)priority.invokeExact(thread);
            return previous <= -1 || (int)lower.invokeExact(thread,-1) != 0;
        } catch (Throwable ignored) { return false; }
    }
}
