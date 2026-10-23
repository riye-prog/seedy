package dev.seedy;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SearchRegionsTest {
    @Test void nearestFirstRegionsCoverTheCircleWithoutDuplicates() {
        for (int x : new int[]{-129,0,128}) for (int z : new int[]{-145,0,128}) {
            Set<String> actual = new HashSet<>();
            double previous = -1;
            for (var region : new SearchRegions(x,z,128,500)) {
                assertTrue(region.distance() >= previous);
                previous = region.distance();
                assertTrue(actual.add(region.x() + ":" + region.z()));
            }
            Set<String> expected = new HashSet<>();
            for (int rx = -8; rx <= 8; rx++) for (int rz = -8; rz <= 8; rz++) {
                double dx = Math.max(0,Math.max(rx * 128 - x,x - (rx + 1) * 128));
                double dz = Math.max(0,Math.max(rz * 128 - z,z - (rz + 1) * 128));
                if (Math.hypot(dx,dz) <= 500) expected.add(rx + ":" + rz);
            }
            assertEquals(expected,actual);
        }
    }

    @Test void millionBlockSearchDoesNotEnumerateTheWholeAreaBeforeStarting() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var regions = new SearchRegions(-1,1,16,Locator.MAX_RADIUS).iterator();
            for (int index = 0; index < 1000; index++) assertTrue(regions.next().distance() < 1000);
        });
    }
}
