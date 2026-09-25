package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class LootTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        try (var structures = new VanillaStructures((RegistryAccess)WorldgenAdapter.registries()); var resources = WorldgenAdapter.resources()) {
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, structures.registries()).forEach(net.minecraft.core.Registry.PendingTags::apply);
            net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(structures.registries()).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        }
    }

    @Test void ancientCityLootUsesGeneratedPieces() {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals("minecraft:ancient_cities")).findFirst().orElseThrow().value();
        var world = WorldgenAdapter.structureWorld(registries, seed, "overworld");
        try (var structures = new VanillaStructures((RegistryAccess)registries)) {
            var predictor = new ChestPredictor(structures);
            for (var chunk : List.of(new ChunkPos(-37, -15), new ChunkPos(-45,12), new ChunkPos(-16,-10))) {
                var generated = structures.generate(set, world, seed, chunk);
                assertNotNull(generated);
                var result = predictor.predict(seed, generated, new LootQuery(true, List.of(new LootQuery.Requirement("minecraft:enchanted_golden_apple", 3))));
                assertFalse(result.chests().isEmpty());
                System.out.println("City " + chunk + " chests " + result.chests().size() + " uncertain " + result.chests().stream().filter(c -> !c.warning().isEmpty()).count() + " totals " + result.totals());
                assertEquals(result.chests().stream().flatMap(c -> c.items().stream()).filter(i -> i.id().equals("minecraft:enchanted_golden_apple")).mapToInt(ChestLoot.Item::count).sum(), result.totals().getOrDefault("minecraft:enchanted_golden_apple", 0));
            }
        }
    }
    @Test void predictedLootSeedsMatchVanillaChestPlacement() {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var world = WorldgenAdapter.structureWorld(registries, seed, "overworld");
        try (var structures = new VanillaStructures((RegistryAccess)registries)) {
            var predictor = new ChestPredictor(structures);
            var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals("minecraft:ancient_cities")).findFirst().orElseThrow().value();
            var generated = structures.generate(set, world, seed, new ChunkPos(-37,-15));
            var predicted = predictor.predict(seed, generated, new LootQuery(true, List.of()));
            var chunks = predicted.chests().stream().map(chest -> new ChunkPos(chest.x() >> 4, chest.z() >> 4)).distinct().toList();
            int compared = 0;
            for (var chunk : chunks) {
                var level = new ChestPlacementOracle(structures.registries(), seed);
                var bounds = new net.minecraft.world.level.levelgen.structure.BoundingBox(chunk.x() * 16, -64, chunk.z() * 16, chunk.x() * 16 + 15, 319, chunk.z() * 16 + 15);
                generated.start().placeInChunk(level.level, null, world.generator(), ChestPredictor.decorationRandom(seed, chunk, 0, 7), bounds, chunk);
                for (var chest : predicted.chests()) {
                    if (chest.x() >> 4 != chunk.x() || chest.z() >> 4 != chunk.z() || !chest.warning().isEmpty()) continue;
                    var position = new net.minecraft.core.BlockPos(chest.x(), chest.y(), chest.z());
                    var block = level.entities.get(position);
                    assertInstanceOf(net.minecraft.world.RandomizableContainer.class, block, position.toString());
                    assertEquals(chest.lootSeed(), ((net.minecraft.world.RandomizableContainer)block).getLootTableSeed(), position.toString());
                    compared++;
                }
            }
            assertEquals(predicted.chests().stream().filter(chest -> chest.warning().isEmpty()).count(), compared);
        }
    }

    @Test void filtersUseStructureTotalsAndRejectInvalidRequirements() {
        var filter = new LootQuery(true, List.of(new LootQuery.Requirement("minecraft:enchanted_golden_apple", 3), new LootQuery.Requirement("minecraft:diamond", 2)));
        assertTrue(filter.accepts(java.util.Map.of("minecraft:enchanted_golden_apple", 3, "minecraft:diamond", 2)));
        assertFalse(filter.accepts(java.util.Map.of("minecraft:enchanted_golden_apple", 2, "minecraft:diamond", 10)));
        assertFalse(filter.accepts(java.util.Map.of("minecraft:enchanted_golden_apple", 4)));
        assertThrows(IllegalArgumentException.class, () -> new LootQuery.Requirement("minecraft:diamond", 0));
        assertThrows(IllegalArgumentException.class, () -> new LootQuery(true, List.of(new LootQuery.Requirement("minecraft:diamond",1), new LootQuery.Requirement("minecraft:diamond",2))));
        assertTrue(LootCatalog.items().contains("minecraft:enchanted_golden_apple"));
    }

    @Test void chestContentsMatchVanillaLootTableCodecs() throws Exception {
        try (var structures = new VanillaStructures((RegistryAccess)WorldgenAdapter.registries()); var resources = WorldgenAdapter.resources()) {
            var evaluator = new LootEvaluator(structures.registries());
            var ops = net.minecraft.resources.RegistryOps.create(com.mojang.serialization.JsonOps.INSTANCE, structures.registries());
            for (String name : List.of("desert_pyramid", "ancient_city_ice_box")) {
                try (var reader = resources.getResourceOrThrow(net.minecraft.resources.Identifier.withDefaultNamespace("loot_table/chests/" + name + ".json")).openAsReader()) {
                    var definition = com.google.gson.JsonParser.parseReader(reader);
                    var vanilla = net.minecraft.world.level.storage.loot.LootTable.DIRECT_CODEC.parse(ops, definition).getOrThrow();
                    for (long seed = 1; seed <= 64; seed++) {
                        var stacks = new java.util.ArrayList<net.minecraft.world.item.ItemStack>();
                        vanilla.getRandomItemsRaw(evaluator.context(seed), stacks::add);
                        var expected = LootEvaluator.describe(stacks).stream().collect(java.util.stream.Collectors.toMap(item -> item.id() + "|" + item.details(), ChestLoot.Item::count));
                        var actual = evaluator.evaluate("minecraft:chests/" + name, seed).stream().collect(java.util.stream.Collectors.toMap(item -> item.id() + "|" + item.details(), ChestLoot.Item::count));
                        assertEquals(expected, actual, name + " seed " + seed);
                    }
                }
            }
        }
    }

    @Test void templeChestSeedsMatchVanillaAcrossChunkBordersAndRotations() {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var world = WorldgenAdapter.structureWorld(registries, seed, "overworld");
        try (var structures = new VanillaStructures((RegistryAccess)registries)) {
            var structure = registries.lookupOrThrow(Registries.STRUCTURE).listElements().filter(holder -> holder.key().identifier().toString().equals("minecraft:desert_pyramid")).findFirst().orElseThrow().value();
            var sameStep = structures.registries().lookupOrThrow(Registries.STRUCTURE).listElements().filter(holder -> holder.value().step() == structure.step()).map(holder -> holder.key().identifier().toString()).toList();
            int index = sameStep.indexOf("minecraft:desert_pyramid");
            var predictor = new ChestPredictor(structures);
            for (int variant = 0; variant < 8; variant++) {
                var piece = new net.minecraft.world.level.levelgen.structure.structures.DesertPyramidPiece(net.minecraft.util.RandomSource.create(variant), -24 + variant, -24 + variant);
                var start = new net.minecraft.world.level.levelgen.structure.StructureStart(structure, new ChunkPos(-2,-2), 0, new net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer(List.of(piece)));
                var predicted = predictor.predict(seed, new VanillaStructures.Generated("minecraft:desert_pyramid", start), new LootQuery(true, List.of()));
                assertEquals(4, predicted.chests().size());
                var chunks = predicted.chests().stream().map(chest -> new ChunkPos(chest.x() >> 4, chest.z() >> 4)).distinct().toList();
                for (var chunk : chunks) {
                    var level = new ChestPlacementOracle(structures.registries(), seed);
                    var bounds = new net.minecraft.world.level.levelgen.structure.BoundingBox(chunk.x() * 16, -64, chunk.z() * 16, chunk.x() * 16 + 15, 319, chunk.z() * 16 + 15);
                    start.placeInChunk(level.level, null, world.generator(), ChestPredictor.decorationRandom(seed, chunk, index, structure.step().ordinal()), bounds, chunk);
                    for (var chest : predicted.chests()) {
                        if (chest.x() >> 4 != chunk.x() || chest.z() >> 4 != chunk.z()) continue;
                        var actual = level.entities.entrySet().stream().filter(entry -> entry.getKey().getX() == chest.x() && entry.getKey().getZ() == chest.z()).map(java.util.Map.Entry::getValue).filter(entity -> entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity).findFirst().orElseThrow();
                        assertEquals(chest.lootSeed(), ((net.minecraft.world.RandomizableContainer)actual).getLootTableSeed());
                        assertNull(chest.y());
                    }
                }
            }
        }
    }

    @Test void locatorFiltersCitiesByTotalAndKeepsMatchingChests() {
        try (var locator = new Locator()) {
            long seed = 2589511696370800371L;
            var all = locator.search(seed, "structure", "minecraft:ancient_cities", -600, -37, -200, 768, new LootQuery(true, List.of()));
            var filtered = locator.search(seed, "structure", "minecraft:ancient_cities", -600, -37, -200, 768, new LootQuery(true, List.of(new LootQuery.Requirement("minecraft:enchanted_golden_apple",3))));
            assertFalse(filtered.isEmpty());
            assertEquals(all.stream().filter(result -> result.loot().totals().getOrDefault("minecraft:enchanted_golden_apple",0) >= 3).map(result -> result.x() + ":" + result.z()).toList(), filtered.stream().map(result -> result.x() + ":" + result.z()).toList());
            for (var result : filtered) for (var chest : result.loot().chests()) assertEquals(chest.items().stream().anyMatch(item -> item.id().equals("minecraft:enchanted_golden_apple")), chest.wanted());
            assertThrows(IllegalArgumentException.class, () -> locator.search(seed, "biome", "minecraft:deep_dark", 0, -37, 0, 768, new LootQuery(true,List.of())));
        }
    }

}
