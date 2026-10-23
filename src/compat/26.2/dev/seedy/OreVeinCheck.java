package dev.seedy;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.OreVeinifier;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class OreVeinCheck {
    private static final Class<?> MARKER;
    private static final Method MARKER_TYPE, MARKER_WRAPPED, ORE_FACTORY;
    static {
        try {
            MARKER = Class.forName("net.minecraft.world.level.levelgen.DensityFunctions$Marker");
            MARKER_TYPE = MARKER.getDeclaredMethod("type");
            MARKER_WRAPPED = MARKER.getDeclaredMethod("wrapped");
            ORE_FACTORY = OreVeinifier.class.getDeclaredMethod("create", DensityFunction.class, DensityFunction.class, DensityFunction.class, PositionalRandomFactory.class);
            MARKER_TYPE.setAccessible(true);
            MARKER_WRAPPED.setAccessible(true);
            ORE_FACTORY.setAccessible(true);
        } catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private final List<BlockPos> positions;
    private final NoiseGeneratorSettings settings;

    public OreVeinCheck(List<BlockPos> positions) {
        this(positions, "26.2");
    }

    public OreVeinCheck(List<BlockPos> positions, String generationVersion) {
        if (!generationVersion.equals("26.2")) throw new IllegalArgumentException("Use the 26.3 Seedy jar to check 26.3 ore veins");
        this.positions = List.copyOf(positions);
        settings = WorldgenAdapter.registries().lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
    }

    public static OreVeinCheck fromEntries(long[] entries, String generationVersion) {
        var positions = new ArrayList<BlockPos>();
        for (long entry : entries) {
            if ((entry & 15) != 1) continue;
            positions.add(new BlockPos((int)(entry >> 38), (int)(entry >> 4 & 255) - 64, (int)(entry << 26 >> 38)));
        }
        return new OreVeinCheck(positions, generationVersion);
    }

    public boolean matches(long low, long high) {
        if (positions.isEmpty()) return true;
        var random = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(low, high);
        var noiseCache = new HashMap<String, NormalNoise>();
        int width = settings.noiseSettings().getCellWidth();
        int height = settings.noiseSettings().getCellHeight();
        var visitor = new DensityFunction.Visitor() {
            @Override public DensityFunction apply(DensityFunction function) {
                if (!MARKER.isInstance(function)) return function;
                try {
                    if (!((Enum<?>)MARKER_TYPE.invoke(function)).name().equals("Interpolated")) return function;
                    return new Interpolated((DensityFunction)MARKER_WRAPPED.invoke(function), width, height);
                } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot prepare ore vein interpolation", error); }
            }

            @Override public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
                String name = holder.noiseData().unwrapKey().orElseThrow().identifier().toString();
                var noise = noiseCache.computeIfAbsent(name, key -> NormalNoise.create(random.fromHashOf(key), holder.noiseData().value()));
                return new DensityFunction.NoiseHolder(holder.noiseData(), noise);
            }
        };
        var router = settings.noiseRouter();
        try {
            var filler = (NoiseChunk.BlockStateFiller)ORE_FACTORY.invoke(null, router.veinToggle().mapAll(visitor), router.veinRidged().mapAll(visitor), router.veinGap().mapAll(visitor), random.fromHashOf("minecraft:ore").forkPositional());
            for (BlockPos position : positions) {
                var block = filler.calculate(new DensityFunction.SinglePointContext(position.getX(), position.getY(), position.getZ()));
                if (block == null || !block.is(position.getY() < 0 ? Blocks.RAW_IRON_BLOCK : Blocks.RAW_COPPER_BLOCK)) return false;
            }
            return true;
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("Cannot verify ore vein placement", error); }
    }

    private record Interpolated(DensityFunction wrapped, int width, int height) implements DensityFunction.SimpleFunction {
        @Override public double compute(DensityFunction.FunctionContext point) {
            int x = Math.floorDiv(point.blockX(), width) * width;
            int y = Math.floorDiv(point.blockY(), height) * height;
            int z = Math.floorDiv(point.blockZ(), width) * width;
            double dx = (double)(point.blockX() - x) / width;
            double dy = (double)(point.blockY() - y) / height;
            double dz = (double)(point.blockZ() - z) / width;
            double a = Mth.lerp(dy, sample(x, y, z), sample(x, y + height, z));
            double b = Mth.lerp(dy, sample(x + width, y, z), sample(x + width, y + height, z));
            double c = Mth.lerp(dy, sample(x, y, z + width), sample(x, y + height, z + width));
            double d = Mth.lerp(dy, sample(x + width, y, z + width), sample(x + width, y + height, z + width));
            return Mth.lerp(dz, Mth.lerp(dx, a, b), Mth.lerp(dx, c, d));
        }
        private double sample(int x, int y, int z) { return wrapped.compute(new DensityFunction.SinglePointContext(x, y, z)); }
        @Override public double minValue() { return wrapped.minValue(); }
        @Override public double maxValue() { return wrapped.maxValue(); }
        @Override public KeyDispatchDataCodec<? extends DensityFunction> codec() { return wrapped.codec(); }
    }
}
