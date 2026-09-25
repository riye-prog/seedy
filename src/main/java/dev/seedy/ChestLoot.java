package dev.seedy;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

public record ChestLoot(List<Chest> chests, Map<String, Integer> totals) {
    public record Item(String id, String name, int count, String details) { }
    public record Chest(int x, Integer y, int z, String table, long lootSeed, List<Item> items, boolean wanted, String warning) { }
    public static ChestLoot of(List<Chest> chests) {
        var totals = new LinkedHashMap<String, Integer>();
        for (var chest : chests) for (var item : chest.items()) totals.merge(item.id(), item.count(), Integer::sum);
        return new ChestLoot(List.copyOf(chests), Map.copyOf(totals));
    }
}
