package dev.seedy.recovery;

public record Observation(String id, String structure, int chunkX, int chunkZ, String source, PlacementRule rule) {
    public static Observation create(PlacementRule rule, int x, int z, String source) {
        if (!rule.possibleStart(x, z)) throw new IllegalArgumentException("The start chunk does not fit this structure's placement rule.");
        return new Observation(rule.id() + ":" + x + ":" + z, rule.id(), x, z, source, rule);
    }
}
