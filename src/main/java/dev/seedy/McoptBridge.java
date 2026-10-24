package dev.seedy;

import java.lang.foreign.SymbolLookup;

public final class McoptBridge {
    private static long[] graphics;

    public static long[] graphics(long encoder, long texture) throws ReflectiveOperationException {
        if (graphics == null) {
            var library = Class.forName("mcopt.metal.Native").getDeclaredField("LIB");
            if (!library.trySetAccessible()) throw new IllegalStateException("mcopt's native library is inaccessible");
            var symbols = (SymbolLookup)library.get(null);
            long frame = symbols.find("mc_frame_cmd").orElseThrow(() -> new IllegalStateException("This mcopt build does not expose mc_frame_cmd")).address();
            graphics = new long[]{frame,0,0};
        }
        graphics[1] = encoder;
        graphics[2] = texture;
        return graphics;
    }
}
