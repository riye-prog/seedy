package dev.seedy;

import java.util.Random;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class LegacyOreVeinCheckTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void portableNoiseMatchesVanillaAcrossSeedsAndCoordinates() {
        var points = new Random(42);
        for (int seed = 0; seed < 8; seed++) {
            for (int octave : new int[]{-8, -7, -5}) {
                var vanilla = NormalNoise.create(new XoroshiroRandomSource(seed), new NormalNoise.NoiseParameters(octave, 1.0));
                var portable = new LegacyOreVeinCheck.SingleOctaveNoise(new XoroshiroRandomSource(seed), octave);
                for (int index = 0; index < 512; index++) {
                    double x = points.nextInt(60_000_000) - 30_000_000;
                    double y = points.nextInt(384) - 64;
                    double z = points.nextInt(60_000_000) - 30_000_000;
                    assertEquals(vanilla.getValue(x, y, z), portable.sample(x, y, z), 1.0e-12, "seed " + seed + ", octave " + octave + ", point " + index);
                }
            }
        }
    }
}
