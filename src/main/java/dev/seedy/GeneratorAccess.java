package dev.seedy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;

final class GeneratorAccess {
    static Method method(String name, Class<?>... parameters) {
        try {
            var method = NoiseBasedChunkGenerator.class.getDeclaredMethod(name,parameters);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Seed terrain is unavailable for this Minecraft version",failure); }
    }

    static Object invoke(Method method, Object instance, Object... arguments) {
        try { return method.invoke(instance,arguments); }
        catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw new IllegalStateException("Terrain generation failed",failure.getCause());
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Terrain generation failed",failure); }
    }

    static void checkpoint() {
        if (!dev.seedy.recovery.RecoveryWorkload.checkpoint(() -> false)) throw new java.util.concurrent.CancellationException();
    }
}
