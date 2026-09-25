package dev.seedy;

import com.google.gson.JsonParser;
import net.minecraft.resources.Identifier;
import java.util.List;
import java.util.TreeSet;

final class LootCatalog {
    private static List<String> cached;
    static synchronized List<String> items() {
        if (cached != null) return cached;
        var result = new TreeSet<String>();
        try (var resources = WorldgenAdapter.resources()) {
            for (String name : List.of("ancient_city", "ancient_city_ice_box", "desert_pyramid")) {
                try (var reader = resources.getResourceOrThrow(Identifier.withDefaultNamespace("loot_table/chests/" + name + ".json")).openAsReader()) {
                    for (var pool : JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("pools")) for (var value : pool.getAsJsonObject().getAsJsonArray("entries")) {
                        var entry = value.getAsJsonObject();
                        if (entry.has("name")) result.add(entry.get("name").getAsString());
                    }
                }
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not read the chest loot item list", e); }
        result.add("minecraft:enchanted_book");
        result.remove("minecraft:enchanted_golden_apple");
        var sorted = new java.util.ArrayList<String>();
        sorted.add("minecraft:enchanted_golden_apple");
        sorted.addAll(result);
        cached = List.copyOf(sorted);
        return cached;
    }
}
