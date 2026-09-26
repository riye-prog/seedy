package dev.seedy;

import net.minecraft.client.Minecraft;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.storage.loot.LootTable;
import java.util.List;

final class LootSmokeCheck {
    static void run(Minecraft client) {
        Thread.ofPlatform().daemon().name("Seedy-loot-smoke").start(() -> {
            var logger = org.slf4j.LoggerFactory.getLogger("Seedy");
            if (Boolean.getBoolean("seedy.smoke")) client.execute(() -> Workbench.INSTANCE.action("{\"action\":\"seed\",\"seed\":\"2589511696370800371\"}"));
            try (var structures = new VanillaStructures((RegistryAccess)WorldgenAdapter.registries()); var resources = WorldgenAdapter.resources(); var locator = new Locator()) {
                if (java.util.Arrays.stream(LootTable.class.getInterfaces()).noneMatch(type -> type.getName().equals("net.fabricmc.fabric.impl.loot.FabricLootTable"))) throw new IllegalStateException("Fabric loot mixin is not active");
                net.fabricmc.fabric.api.loot.v3.LootTableEvents.MODIFY_DROPS.register((table, context, stacks) -> { throw new AssertionError("Offline prediction invoked server loot events"); });
                net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, structures.registries()).forEach(net.minecraft.core.Registry.PendingTags::apply);
                BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(structures.registries()).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
                var evaluator = new LootEvaluator(structures.registries());
                for (String table : List.of("ancient_city", "ancient_city_ice_box", "desert_pyramid")) for (long seed = 1; seed <= 128; seed++) evaluator.evaluate("minecraft:chests/" + table, seed);
                var results = locator.search(2589511696370800371L, "structure", "minecraft:ancient_cities", -600, -37, -200, 768, new LootQuery(true, List.of(new LootQuery.Requirement("minecraft:enchanted_golden_apple",3))));
                if (results.isEmpty() || results.stream().anyMatch(result -> result.loot().totals().getOrDefault("minecraft:enchanted_golden_apple",0) < 3)) throw new IllegalStateException("Chest loot filter returned incorrect results");
                var ships = locator.search(2589511696370800371L, "structure", "minecraft:end_cities", 3200, 72, 3200, 2048, LootQuery.NONE, "the_end", "with_elytra");
                var noShips = locator.search(2589511696370800371L, "structure", "minecraft:end_cities", 3200, 72, 3200, 2048, LootQuery.NONE, "the_end", "without_elytra");
                if (ships.isEmpty() || noShips.isEmpty() || ships.stream().anyMatch(result -> result.elytra() == null) || noShips.stream().anyMatch(result -> result.elytra() != null)) throw new IllegalStateException("End city ship filtering failed");
                for (String dimension : List.of("the_nether", "the_end")) {
                    var sampler = WorldgenAdapter.structureWorld(WorldgenAdapter.registries(), 2589511696370800371L, dimension).biomes();
                    var biome = sampler.at(3200,72,3200);
                    if (locator.search(2589511696370800371L,"biome",biome,3200,72,3200,128,LootQuery.NONE,dimension,"any").isEmpty()) throw new IllegalStateException("Dimension biome search failed");
                }
                logger.info("SEEDY_DIMENSION_SMOKE_PASS version={} withShip={} withoutShip={}", net.minecraft.SharedConstants.getCurrentVersion().name(), ships.size(), noShips.size());
                try (var map = new SeedMap()) {
                    long id = 0;
                    for (String dimension : List.of("overworld", "the_nether", "the_end")) {
                        String target = dimension.equals("overworld") ? "minecraft:villages" : dimension.equals("the_nether") ? "minecraft:nether_complexes" : "minecraft:end_cities";
                        int min = dimension.equals("the_end") ? 3072 : 0;
                        map.request(new SeedMap.Request(++id,2589511696370800371L,dimension,64,16,min,min,min + 1023,min + 1023,target,"any"));
                        long deadline = System.nanoTime() + 30_000_000_000L;
                        while (true) {
                            var snapshot = map.snapshot();
                            if (snapshot.has("id") && snapshot.get("id").getAsLong() == id && snapshot.get("remaining").getAsInt() == 0) {
                                if (!snapshot.get("error").getAsString().isEmpty() || snapshot.getAsJsonArray("tiles").size() != 4) throw new IllegalStateException("Map tiles failed: " + snapshot.get("error"));
                                break;
                            }
                            if (System.nanoTime() > deadline) throw new IllegalStateException("Map generation timed out in " + dimension);
                            Thread.sleep(20);
                        }
                    }
                    logger.info("SEEDY_MAP_SMOKE_PASS version={} dimensions=3", net.minecraft.SharedConstants.getCurrentVersion().name());
                }
                if (Boolean.getBoolean("seedy.smoke")) {
                    long deadline = System.nanoTime() + 20_000_000_000L;
                    while (true) {
                        var snapshot = com.google.gson.JsonParser.parseString(Workbench.INSTANCE.snapshot()).getAsJsonObject();
                        if (snapshot.has("map") && snapshot.getAsJsonObject("map").has("tiles") && !snapshot.getAsJsonObject("map").getAsJsonArray("tiles").isEmpty()) {
                            logger.info("SEEDY_MAP_UI_SMOKE_PASS version={}",net.minecraft.SharedConstants.getCurrentVersion().name());
                            break;
                        }
                        if (System.nanoTime() > deadline) throw new IllegalStateException("Native map did not request and receive biome tiles");
                        Thread.sleep(50);
                    }
                }
                logger.info("SEEDY_LOOT_SMOKE_PASS version={} lootCases=384 matchingCities={}", net.minecraft.SharedConstants.getCurrentVersion().name(), results.size());
            } catch (Throwable failure) {
                logger.error("SEEDY_LOOT_SMOKE_FAIL", failure);
            } finally {
                client.execute(client::stop);
            }
        });
    }
}
