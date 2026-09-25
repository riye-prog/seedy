package dev.seedy.recovery;

public record PlacementRule(String id, int spacing, int separation, int salt, boolean triangular) {
    public static final long MASK = (1L << 48) - 1;
    public static long previousState(long state) { return ((state - 11) * 0xDFE05BCB1365L) & MASK; }
    public int bound() { return spacing - separation; }
    public boolean canLift() { return !triangular && bound() > 0 && bound() % 2 == 0 && (bound() & (bound() - 1)) != 0; }
    public long regionOffset(int regionX, int regionZ) { return regionX * 341873128712L + regionZ * 132897987541L + salt; }
    public int[] chunk(long seed, int regionX, int regionZ) {
        var random = new LegacyRandom(seed + regionOffset(regionX, regionZ));
        int x = triangular ? (random.nextInt(bound()) + random.nextInt(bound())) / 2 : random.nextInt(bound());
        int z = triangular ? (random.nextInt(bound()) + random.nextInt(bound())) / 2 : random.nextInt(bound());
        return new int[]{regionX * spacing + x, regionZ * spacing + z};
    }
    public Constraint constrain(int chunkX, int chunkZ) {
        int rx = Math.floorDiv(chunkX, spacing), rz = Math.floorDiv(chunkZ, spacing);
        int x = chunkX - rx * spacing, z = chunkZ - rz * spacing;
        if (!canLift() || x >= bound() || z >= bound()) throw new IllegalArgumentException("The start chunk does not fit this structure's placement rule.");
        return new Constraint(regionOffset(rx, rz), bound(), x, z);
    }
    public record Constraint(long offset, int bound, int x, int z) {
        public boolean matches(long seed) {
            var random = new LegacyRandom(seed + offset);
            return random.nextInt(bound) == x && random.nextInt(bound) == z;
        }
        public boolean matchesLower(long seed) {
            var random = new LegacyRandom(seed + offset);
            int mask = Math.min(3, Integer.lowestOneBit(bound) - 1);
            return (random.nextInt(bound) & mask) == (x & mask) && (random.nextInt(bound) & mask) == (z & mask);
        }
    }
    public static final class LegacyRandom {
        private long state;
        public LegacyRandom(long seed) { state = (seed ^ 0x5DEECE66DL) & MASK; }
        private int next31() { state = (state * 0x5DEECE66DL + 11) & MASK; return (int)(state >>> 17); }
        public int nextInt(int bound) {
            if (bound <= 0) throw new IllegalArgumentException("Invalid random bound");
            int bits = next31();
            if ((bound & -bound) == bound) return (int)((bound * (long)bits) >> 31);
            int value = bits % bound;
            while (bits - value + bound - 1 < 0) { bits = next31(); value = bits % bound; }
            return value;
        }
    }
}
