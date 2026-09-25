package dev.seedy;

import java.util.List;
import java.util.Map;

public record LootQuery(boolean enabled, List<Requirement> requirements) {
    public static final LootQuery NONE = new LootQuery(false, List.of());
    public record Requirement(String item, int minimum) {
        public Requirement {
            if (item == null || !item.startsWith("minecraft:") || minimum < 1 || minimum > 99999) throw new IllegalArgumentException("Choose an item and an amount from 1 to 99999.");
        }
    }
    public LootQuery {
        requirements = List.copyOf(requirements);
        if (requirements.size() > 8) throw new IllegalArgumentException("Use at most eight item requirements.");
        if (requirements.stream().map(Requirement::item).distinct().count() != requirements.size()) throw new IllegalArgumentException("Use each item once in the filter.");
    }
    public boolean accepts(Map<String, Integer> totals) { return requirements.stream().allMatch(rule -> totals.getOrDefault(rule.item(), 0) >= rule.minimum()); }
    public boolean wanted(String item) { return requirements.stream().anyMatch(rule -> rule.item().equals(item)); }
    public static boolean supports(String target) { return target.equals("minecraft:ancient_cities") || target.equals("minecraft:desert_pyramids"); }
}
