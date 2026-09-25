package dev.seedy;

import dev.seedy.recovery.Observation;
import dev.seedy.recovery.PlacementRule;
import dev.seedy.recovery.RecoveryEngine;
import dev.seedy.recovery.SeedHash;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.SharedConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void legacyRandomMatchesJdkIncludingRejection() {
        for (long seed : new long[]{0, 1, -1, Long.MIN_VALUE, Long.MAX_VALUE, 123456789}) {
            for (int bound : new int[]{20, 24, 48, 64, 1073741825}) {
                var expected = new Random(seed);
                var actual = new PlacementRule.LegacyRandom(seed);
                for (int i = 0; i < 1000; i++) assertEquals(expected.nextInt(bound), actual.nextInt(bound));
            }
        }
    }

    @Test void inputMappingUsesEachMinecraftVersionsButtonAndKeyConstants() {
        assertEquals(0,OverlayScreen.mapMouseButton(InputConstants.MOUSE_BUTTON_LEFT));
        assertEquals(1,OverlayScreen.mapMouseButton(InputConstants.MOUSE_BUTTON_RIGHT));
        assertEquals(2,OverlayScreen.mapMouseButton(InputConstants.MOUSE_BUTTON_MIDDLE));
        assertEquals(6,OverlayScreen.mapKey(InputConstants.KEY_BACKSPACE));
        assertEquals(8,OverlayScreen.mapKey(InputConstants.KEY_RETURN));
        assertEquals(15,OverlayScreen.mapKey(InputConstants.KEY_V));
    }

    @Test void mouseCoordinatesMatchFramebufferAtFractionalGuiSizesAndRetinaDensity() {
        assertEquals(1001, OverlayScreen.framebufferCoordinate(334, 1001, 334), 0.0001);
        assertEquals(1500, OverlayScreen.framebufferCoordinate(500, 3000, 1000), 0.0001);
        assertEquals(1287.5, OverlayScreen.framebufferCoordinate(643.75, 3024, 1512), 0.0001);
        assertEquals(0, OverlayScreen.framebufferCoordinate(0, 3024, 756), 0.0001);
    }

    @Test void ownPlacementMathMatchesEveryVanillaRandomSpreadSet() {
        var registries = WorldgenAdapter.registries();
        registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().forEach(holder -> {
            if (!(holder.value().placement() instanceof RandomSpreadStructurePlacement vanilla)) return;
            var rule = PlacementRules.read(holder.key().identifier().toString(),vanilla);
            for (long seed : new long[]{0,-123456789,Long.MIN_VALUE,Long.MAX_VALUE}) for (int region : new int[]{-19,-1,0,23}) {
                var expected = vanilla.getPotentialStructureChunk(seed, region * rule.spacing(), -region * 3 * rule.spacing());
                var actual = rule.chunk(seed, region, -region * 3);
                assertArrayEquals(new int[]{expected.x(),expected.z()},actual,rule.id());
            }
        });
    }

    @Test void hashAndUpperBitsMatchMinecraftForSignedSeeds() {
        for (long seed : new long[]{0,-123456789,Long.MIN_VALUE,Long.MAX_VALUE}) {
            assertEquals(BiomeManager.obfuscateSeed(seed),SeedHash.hash(seed));
            assertEquals(seed, SeedHash.recover(seed & PlacementRule.MASK,SeedHash.hash(seed),() -> false));
        }
    }

    @Test void engineRecoversSeedFromIndependentStructureObservations() throws Exception {
        long expected = 123456789;
        var rule = new PlacementRule("minecraft:desert_pyramids",32,8,14357617,false);
        var observations = new ArrayList<Observation>();
        for (int region : new int[]{-71,-15,0,12,39,83,141}) {
            int[] chunk = rule.chunk(expected,region,-region * 2 + 3);
            observations.add(Observation.create(rule,chunk[0],chunk[1],"Test"));
        }
        var constraints = observations.stream().map(o -> o.rule().constrain(o.chunkX(),o.chunkZ())).toList();
        assertTrue(RecoveryEngine.lowerCandidates(constraints,() -> false).contains(expected & ((1L << 19) - 1)));
        var done = new CountDownLatch(1);
        var recovered = new AtomicLong();
        try (var engine = new RecoveryEngine()) {
            engine.start(observations,SeedHash.hash(expected),seed -> { recovered.set(seed); done.countDown(); });
            assertTrue(done.await(45,TimeUnit.SECONDS),engine.stage());
            assertEquals(expected,recovered.get());
        }
    }

    @Test void cancellationAndEvidenceRequirementsAreEnforced() {
        assertTrue(RecoveryEngine.lowerCandidates(List.of(),() -> true).isEmpty());
        assertNull(SeedHash.recover(0,1,() -> true));
        try (var engine = new RecoveryEngine()) {
            assertThrows(IllegalArgumentException.class,() -> engine.start(List.of(),0,seed -> fail()));
            engine.cancel();
            assertFalse(engine.running());
        }
    }

    @Test void rejectedRandomDrawsAreRecoveredOutsideTheLowerBitFilter() {
        var rule = new PlacementRule("minecraft:desert_pyramids",32,8,14357617,false);
        long rejectedState = (((1L << 31) - 1) << 17) | 23;
        long seed = ((PlacementRule.previousState(rejectedState) ^ 0x5DEECE66DL) - rule.regionOffset(0,0)) & PlacementRule.MASK;
        var constraints = new ArrayList<PlacementRule.Constraint>();
        for (int region : new int[]{0,11,-28,42,71,92}) {
            int[] chunk = rule.chunk(seed,region,-region);
            constraints.add(rule.constrain(chunk[0],chunk[1]));
        }
        var found = new AtomicLong(-1);
        RecoveryEngine.scanRejectedDraws(constraints,() -> false,candidate -> {
            if (candidate != seed) return false;
            found.set(candidate);
            return true;
        });
        assertEquals(seed,found.get());
    }

    @Test void invalidStartChunksAreRejectedIncludingNegativeRegions() {
        var rule = new PlacementRule("test",32,8,14357617,false);
        assertThrows(IllegalArgumentException.class,() -> rule.constrain(31,0));
        assertThrows(IllegalArgumentException.class,() -> rule.constrain(-1,0));
        assertDoesNotThrow(() -> rule.constrain(-32,-32));
    }

    @Test void trialChambersAndShipwrecksContributeToRecoveryTogetherAndAlone() throws Exception {
        long expected = 123456789;
        var registries = WorldgenAdapter.registries();
        var rules = java.util.stream.Stream.of("minecraft:shipwrecks", "minecraft:trial_chambers").map(id -> {
            var placement = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals(id)).findFirst().orElseThrow().value().placement();
            return PlacementRules.read(id, (RandomSpreadStructurePlacement)placement);
        }).toList();
        assertEquals(20, rules.getFirst().bound());
        assertEquals(22, rules.getLast().bound());
        assertTrue(rules.stream().allMatch(PlacementRule::canLift));
        assertFalse(new PlacementRule("power_of_two",24,8,1,false).canLift());
        for (int mode = 0; mode < 3; mode++) {
            var evidence = new ArrayList<Observation>();
            for (int i = 0; i < 16; i++) {
                var rule = rules.get(mode == 2 ? i % 2 : mode);
                int[] chunk = rule.chunk(expected, i * 31 - 150, i * -17 + 21);
                evidence.add(Observation.create(rule, chunk[0], chunk[1], "Test"));
            }
            var done = new CountDownLatch(1);
            var found = new AtomicLong();
            try (var engine = new RecoveryEngine()) {
                engine.start(evidence, SeedHash.hash(expected), seed -> { found.set(seed); done.countDown(); });
                assertTrue(done.await(45, TimeUnit.SECONDS), engine.stage());
                assertEquals(expected, found.get());
            }
        }
    }

    @Test void locatorFindsActualBiomeSamplesAndBoundedCandidates() {
        var locator = new Locator();
        var sampler = WorldgenAdapter.sampler(WorldgenAdapter.registries(), -1234);
        String biome = sampler.at(-512,-32,768);
        var biomes = locator.search(-1234,"biome",biome,-512,-32,768,128);
        assertTrue(biomes.stream().anyMatch(p -> p.x() == -512 && p.z() == 768));
        assertTrue(biomes.stream().allMatch(p -> sampler.at(p.x(),p.y(),p.z()).equals(biome)));
        var structures = locator.search(-1234,"structure","minecraft:villages",-512,64,768,2048);
        assertFalse(structures.isEmpty());
        assertTrue(structures.stream().allMatch(p -> p.distance() <= 2048));
        assertTrue(structures.stream().allMatch(p -> p.dimension().equals("overworld")));
        assertTrue(structures.stream().allMatch(p -> p.y() == null));
        assertThrows(IllegalArgumentException.class,() -> locator.search(0,"biome",biome,0,64,0,Integer.MAX_VALUE));
    }

    @Test void structureResultsUseGeneratedPiecesAcrossDimensions() {
        var locator = new Locator();
        var registries = WorldgenAdapter.registries();
        long seed = -1234;
        for (String target : List.of("minecraft:villages", "minecraft:nether_complexes", "minecraft:end_cities")) {
            String dimension = target.contains("nether") ? "the_nether" : target.contains("end_cities") ? "the_end" : "overworld";
            var world = WorldgenAdapter.structureWorld(registries, seed, dimension);
            var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals(target)).findFirst().orElseThrow().value();
            var allowed = set.structures().stream().flatMap(entry -> entry.structure().value().biomes().stream()).map(h -> h.unwrapKey().orElseThrow().identifier().toString()).collect(java.util.stream.Collectors.toSet());
            var found = locator.search(seed,"structure",target,4096,64,-4096,2048);
            assertFalse(found.isEmpty(),target);
            for (var result : found) {
                assertEquals(dimension,result.dimension());
                if (dimension.equals("the_end")) assertNotNull(result.y());
                else assertNull(result.y());
                assertTrue(set.structures().stream().anyMatch(entry -> entry.structure().unwrapKey().orElseThrow().identifier().toString().equals(result.name())));
                assertTrue(result.confidence().contains("generated"));
            }
        }
    }
}
