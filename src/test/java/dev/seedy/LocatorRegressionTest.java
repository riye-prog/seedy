package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LocatorRegressionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void reportedAncientCityCandidatesMustPassActualVanillaGeneration() {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals("minecraft:ancient_cities")).findFirst().orElseThrow().value();
        var world = WorldgenAdapter.structureWorld(registries, seed, "overworld");
        int[][] oldCoordinates = {{-632,-584},{-584,-232},{-712,200},{-248,-152},{-312,56}};
        try (var generator = new VanillaStructures((RegistryAccess)registries); var locator = new Locator()) {
            for (var coordinate : oldCoordinates) {
                var chunk = new ChunkPos(Math.floorDiv(coordinate[0],16),Math.floorDiv(coordinate[1],16));
                boolean placement = set.placement().isStructureChunk(world.placements(), chunk.x(), chunk.z());
                var start = placement ? generator.generate(set, world, seed, chunk) : null;
                System.out.println("Reported point " + coordinate[0] + "," + coordinate[1] + " placement=" + placement + " generated=" + (start == null ? "none" : start.start().getPieces().getFirst().getBoundingBox().getCenter()));
                if (SharedConstants.getCurrentVersion().name().equals("26.2") && coordinate[0] == -632) assertNull(start);
            }
            var results = locator.search(seed,"structure","minecraft:ancient_cities",0,-40,0,2048);
            System.out.println("Ancient-city regression " + SharedConstants.getCurrentVersion().name() + ": " + results);
            if (SharedConstants.getCurrentVersion().name().equals("26.2")) assertTrue(results.stream().anyMatch(result -> result.x() == -596 && result.y() == -37 && result.z() == -240));
            for (var result : results) {
                assertEquals("minecraft:ancient_city", result.name());
                assertNotNull(result.y());
                assertTrue(result.y() < 0);
                for (var coordinate : oldCoordinates) assertFalse(result.x() == coordinate[0] && result.z() == coordinate[1]);
            }
        }
    }
}
