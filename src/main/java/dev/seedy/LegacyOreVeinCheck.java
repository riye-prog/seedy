package dev.seedy;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;

final class LegacyOreVeinCheck {
    private final List<BlockPos> positions;

    LegacyOreVeinCheck(List<BlockPos> positions) { this.positions = List.copyOf(positions); }

    boolean matches(long low, long high) {
        var random = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(low, high);
        var toggle = new SingleOctaveNoise(random.fromHashOf("minecraft:ore_veininess"), -8);
        var veinA = new SingleOctaveNoise(random.fromHashOf("minecraft:ore_vein_a"), -7);
        var veinB = new SingleOctaveNoise(random.fromHashOf("minecraft:ore_vein_b"), -7);
        var gap = new SingleOctaveNoise(random.fromHashOf("minecraft:ore_gap"), -5);
        PositionalRandomFactory ore = random.fromHashOf("minecraft:ore").forkPositional();
        for (BlockPos point : positions) {
            int x = point.getX(), y = point.getY(), z = point.getZ();
            double vein = interpolated(toggle, x, y, z, 1.5);
            boolean copper = vein > 0;
            int min = copper ? 0 : -60, max = copper ? 50 : -8;
            if (copper != (y >= 0) || y < min || y > max) return false;
            double magnitude = Math.abs(vein);
            double edge = map(Math.min(max - y, y - min), 0, 20, -0.2, 0);
            if (magnitude + edge < (double)0.4f) return false;
            var rolls = ore.at(x, y, z);
            if (rolls.nextFloat() > 0.7f) return false;
            double ridge = Math.max(Math.abs(interpolated(veinA, x, y, z, 4)), Math.abs(interpolated(veinB, x, y, z, 4))) - (double)0.08f;
            if (ridge >= 0) return false;
            double richness = map(magnitude, (double)0.4f, (double)0.6f, (double)0.1f, (double)0.3f);
            if (rolls.nextFloat() >= richness || gap.sample(x, y, z) <= (double)-0.3f || rolls.nextFloat() >= 0.02f) return false;
        }
        return true;
    }

    private static double map(double value, double min, double max, double low, double high) {
        double amount = Math.max(0, Math.min(1, (value - min) / (max - min)));
        return low + amount * (high - low);
    }

    private static double interpolate(double amount, double low, double high) { return low + amount * (high - low); }

    private static double interpolated(SingleOctaveNoise noise, int x, int y, int z, double scale) {
        int startX = Math.floorDiv(x, 4) * 4, startY = Math.floorDiv(y, 8) * 8, startZ = Math.floorDiv(z, 4) * 4;
        double dx = (x - startX) / 4.0, dy = (y - startY) / 8.0, dz = (z - startZ) / 4.0;
        double a = interpolate(dy, limited(noise, startX, startY, startZ, scale), limited(noise, startX, startY + 8, startZ, scale));
        double b = interpolate(dy, limited(noise, startX + 4, startY, startZ, scale), limited(noise, startX + 4, startY + 8, startZ, scale));
        double c = interpolate(dy, limited(noise, startX, startY, startZ + 4, scale), limited(noise, startX, startY + 8, startZ + 4, scale));
        double d = interpolate(dy, limited(noise, startX + 4, startY, startZ + 4, scale), limited(noise, startX + 4, startY + 8, startZ + 4, scale));
        return interpolate(dz, interpolate(dx, a, b), interpolate(dx, c, d));
    }

    private static double limited(SingleOctaveNoise noise, int x, int y, int z, double scale) {
        return y < -60 || y > 50 ? 0 : noise.sample(x * scale, y * scale, z * scale);
    }

    static final class SingleOctaveNoise {
        private final GradientNoise first, second;
        private final double frequency;
        SingleOctaveNoise(RandomSource random, int octave) {
            first = new GradientNoise(random.forkPositional().fromHashOf("octave_" + octave));
            second = new GradientNoise(random.forkPositional().fromHashOf("octave_" + octave));
            frequency = Math.scalb(1.0, octave);
        }
        double sample(double x, double y, double z) {
            double a = first.sample(x * frequency, y * frequency, z * frequency);
            double scale = 1.0181268882175227;
            double b = second.sample(x * scale * frequency, y * scale * frequency, z * scale * frequency);
            return (a + b) * (0.16666666666666666 / 0.2);
        }
    }

    private static final class GradientNoise {
        private final int[] permutation = new int[256];
        private final double offsetX, offsetY, offsetZ;
        GradientNoise(RandomSource random) {
            offsetX = random.nextDouble() * 256;
            offsetY = random.nextDouble() * 256;
            offsetZ = random.nextDouble() * 256;
            for (int index = 0; index < 256; index++) permutation[index] = index;
            for (int index = 0; index < 256; index++) {
                int other = index + random.nextInt(256 - index);
                int value = permutation[index];
                permutation[index] = permutation[other];
                permutation[other] = value;
            }
        }
        private int at(int index) { return permutation[index & 255]; }
        private static double fade(double value) { return value * value * value * (value * (value * 6 - 15) + 10); }
        private static double gradient(int index, double x, double y, double z) {
            int hash = index & 15;
            double a = hash < 8 ? x : y;
            double b = hash < 4 ? y : hash == 12 || hash == 14 ? x : z;
            return (hash & 1) == 0 ? a + ((hash & 2) == 0 ? b : -b) : -a + ((hash & 2) == 0 ? b : -b);
        }
        double sample(double x, double y, double z) {
            x += offsetX;
            y += offsetY;
            z += offsetZ;
            int ix = (int)Math.floor(x), iy = (int)Math.floor(y), iz = (int)Math.floor(z);
            double dx = x - ix, dy = y - iy, dz = z - iz;
            int a = at(ix) + iy, b = at(ix + 1) + iy;
            double low0 = interpolate(fade(dx), gradient(at(at(a) + iz), dx, dy, dz), gradient(at(at(b) + iz), dx - 1, dy, dz));
            double low1 = interpolate(fade(dx), gradient(at(at(a + 1) + iz), dx, dy - 1, dz), gradient(at(at(b + 1) + iz), dx - 1, dy - 1, dz));
            double high0 = interpolate(fade(dx), gradient(at(at(a) + iz + 1), dx, dy, dz - 1), gradient(at(at(b) + iz + 1), dx - 1, dy, dz - 1));
            double high1 = interpolate(fade(dx), gradient(at(at(a + 1) + iz + 1), dx, dy - 1, dz - 1), gradient(at(at(b + 1) + iz + 1), dx - 1, dy - 1, dz - 1));
            return interpolate(fade(dz), interpolate(fade(dy), low0, low1), interpolate(fade(dy), high0, high1));
        }
    }
}
