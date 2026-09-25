package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.structure.TemplateStructurePiece;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class DimensionSearchTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void catalogsSeparateDimensionsAndRejectMismatchedTargets() {
        try (var locator = new Locator()) {
            assertTrue(locator.catalog("the_end").structures().contains("minecraft:end_cities"));
            assertTrue(locator.catalog("the_end").biomes().contains("minecraft:end_highlands"));
            assertTrue(locator.catalog("the_nether").structures().contains("minecraft:nether_complexes"));
            assertTrue(locator.catalog("the_nether").biomes().contains("minecraft:warped_forest"));
            assertFalse(locator.catalog("overworld").structures().contains("minecraft:end_cities"));
            assertFalse(locator.catalog("the_nether").biomes().contains("minecraft:plains"));
            assertThrows(IllegalArgumentException.class, () -> locator.search(1,"structure","minecraft:end_cities",0,64,0,128,LootQuery.NONE,"overworld","any"));
            assertThrows(IllegalArgumentException.class, () -> locator.search(1,"biome","minecraft:plains",0,64,0,128,LootQuery.NONE,"the_nether","any"));
            assertThrows(IllegalArgumentException.class, () -> locator.catalog("unknown"));
        }
    }

    @Test void biomeSearchesUseTheSelectedDimensionAndSeed() {
        var registries = WorldgenAdapter.registries();
        try (var locator = new Locator()) {
            for (long seed : new long[]{2589511696370800371L, 37L}) for (String dimension : List.of("the_end", "the_nether", "overworld", "the_end")) {
                var sampler = WorldgenAdapter.structureWorld(registries, seed, dimension).biomes();
                String target = sampler.at(-3000,72,2000);
                var results = locator.search(seed,"biome",target,-3000,72,2000,128,LootQuery.NONE,dimension,"any");
                assertTrue(results.stream().anyMatch(result -> result.x() == -3000 && result.z() == 2000));
                for (var result : results) {
                    assertEquals(dimension,result.dimension());
                    assertEquals(target,sampler.at(result.x(),result.y(),result.z()));
                }
            }
        }
    }

    @Test void endCityFiltersMatchGeneratedShipTemplates() throws Exception {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var world = WorldgenAdapter.structureWorld(registries,seed,"the_end");
        var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(holder -> holder.key().identifier().toString().equals("minecraft:end_cities")).findFirst().orElseThrow().value();
        var templateName = TemplateStructurePiece.class.getDeclaredField("templateName");
        templateName.setAccessible(true);
        try (var locator = new Locator(); var structures = new VanillaStructures((RegistryAccess)registries)) {
            var all = locator.search(seed,"structure","minecraft:end_cities",3200,72,3200,2048,LootQuery.NONE,"the_end","any");
            var with = locator.search(seed,"structure","minecraft:end_cities",3200,72,3200,2048,LootQuery.NONE,"the_end","with_elytra");
            var without = locator.search(seed,"structure","minecraft:end_cities",3200,72,3200,2048,LootQuery.NONE,"the_end","without_elytra");
            assertFalse(with.isEmpty());
            assertFalse(without.isEmpty());
            assertEquals(all.stream().filter(result -> result.elytra() != null).toList(),with);
            assertEquals(all.stream().filter(result -> result.elytra() == null).toList(),without);
            for (var result : all) {
                var generated = structures.generate(set,world,seed,new ChunkPos(result.x() >> 4,result.z() >> 4));
                assertNotNull(generated);
                boolean ship = false;
                for (var piece : generated.start().getPieces()) if (piece instanceof TemplateStructurePiece && templateName.get(piece).equals("ship")) {
                    ship = true;
                    assertNotNull(result.elytra());
                    assertTrue(piece.getBoundingBox().isInside(new net.minecraft.core.BlockPos(result.elytra().x(),result.elytra().y(),result.elytra().z())));
                }
                assertEquals(ship,result.elytra() != null);
                assertNotNull(result.y());
                assertEquals("the_end",result.dimension());
            }
            var nether = locator.search(seed,"structure","minecraft:nether_complexes",0,64,0,2048,LootQuery.NONE,"the_nether","any");
            assertFalse(nether.isEmpty());
            for (String target : List.of("minecraft:fortress", "minecraft:bastion_remnant")) {
                var specific = locator.search(seed,"structure",target,0,64,0,2048,LootQuery.NONE,"the_nether","any");
                assertEquals(nether.stream().filter(result -> result.name().equals(target)).toList(),specific);
                assertFalse(specific.isEmpty());
            }
            assertTrue(nether.stream().allMatch(result -> result.dimension().equals("the_nether") && List.of("minecraft:fortress","minecraft:bastion_remnant").contains(result.name())));
            System.out.println("Dimension search: end=" + all.size() + " withShip=" + with.size() + " withoutShip=" + without.size() + " nether=" + nether.size());
        }
    }
}
