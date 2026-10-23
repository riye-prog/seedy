package dev.seedy;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.PriorityQueue;
import java.util.Set;

final class SearchRegions implements Iterable<SearchRegions.Region> {
    record Region(int x, int z, double distance) { }
    private final int x, z, span, radius;

    SearchRegions(int x, int z, int span, int radius) {
        if (span <= 0 || radius < 0) throw new IllegalArgumentException("Invalid search region");
        this.x = x; this.z = z; this.span = span; this.radius = radius;
    }

    @Override public Iterator<Region> iterator() {
        return new Iterator<>() {
            private final PriorityQueue<Region> queue = new PriorityQueue<>(Comparator.comparingDouble(Region::distance).thenComparingInt(Region::x).thenComparingInt(Region::z));
            private final Set<Long> visited = new HashSet<>();
            { add(Math.floorDiv(x, span), Math.floorDiv(z, span)); }

            private void add(int rx, int rz) {
                long key = (long)rx << 32 ^ (rz & 0xffffffffL);
                if (!visited.add(key)) return;
                double dx = Math.max(0, Math.max((long)rx * span - x, (long)x - ((long)rx + 1) * span));
                double dz = Math.max(0, Math.max((long)rz * span - z, (long)z - ((long)rz + 1) * span));
                double distance = Math.hypot(dx, dz);
                if (distance <= radius) queue.add(new Region(rx, rz, distance));
            }

            @Override public boolean hasNext() { return !queue.isEmpty(); }
            @Override public Region next() {
                if (queue.isEmpty()) throw new java.util.NoSuchElementException();
                Region region = queue.remove();
                add(region.x() - 1, region.z()); add(region.x() + 1, region.z());
                add(region.x(), region.z() - 1); add(region.x(), region.z() + 1);
                return region;
            }
        };
    }
}
