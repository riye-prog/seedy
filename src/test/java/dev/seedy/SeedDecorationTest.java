package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SeedDecorationTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); SeedTerrainProbe.prepareVanillaTags(); }

    @Test void decoratesWithoutAClientOrServerWorld() {
        var registries = WorldgenAdapter.registries();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),((RegistryAccess)registries).registries()));
        long seed = -7619526468459309251L;
        var world = WorldgenAdapter.structureWorld(registries,seed,"overworld");
        try (var decoration = new SeedDecorationWorld(access,world,seed)) {
            long started = System.nanoTime();
            var chunks = decoration.generate(0,0);
            assertEquals(4,chunks.size());
            var blocks = new java.util.HashMap<String,Integer>();
            var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            chunks.values().forEach(chunk -> {
                for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
                    var state = chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX() + x,y,chunk.getPos().getMinBlockZ() + z));
                    blocks.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),1,Integer::sum);
                }
            });
            System.out.println("decorated ms=" + (System.nanoTime() - started) / 1_000_000 + " surface=" + blocks);
            assertTrue(blocks.values().stream().mapToInt(Integer::intValue).sum() > 0);
            assertTrue(blocks.keySet().stream().anyMatch(name -> name.endsWith("_leaves")),blocks.toString());
        }
    }

    @Test void placesVillageBuildingsFromTheSeed() {
        long seed = -7619526468459309251L;
        try (var locator = new Locator()) {
            var villages = locator.search(seed,"structure","minecraft:villages",0,64,0,2048);
            assertFalse(villages.isEmpty());
            var village = villages.getFirst();
            var registries = (RegistryAccess)WorldgenAdapter.registries();
            var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),registries.registries()));
            try (var decoration = new SeedDecorationWorld(access,locator.mapWorld(seed,"overworld"),seed)) {
                var chunks = decoration.generate(village.x() >> 4,village.z() >> 4);
                int buildingBlocks = 0;
                var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
                for (var chunk : chunks.values()) for (int y = 50; y < 150; ++y) for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
                    var state = chunk.getBlockState(pos.set(chunk.getPos().getMinBlockX() + x,y,chunk.getPos().getMinBlockZ() + z));
                    var name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    if (name.contains("planks") || name.contains("stairs") || name.contains("cobblestone")) ++buildingBlocks;
                }
                System.out.println("village=" + village.x() + "," + village.z() + " building blocks=" + buildingBlocks);
                assertTrue(buildingBlocks > 20);
            }
        }
    }

    @Test void decorationMatchesVanillaAndDoesNotDependOnMapVisitOrder() {
        long seed = -7619526468459309251L;
        var registries = (RegistryAccess)WorldgenAdapter.registries();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),registries.registries()));
        var world = WorldgenAdapter.structureWorld(registries,seed,"overworld");
        try (var prediction = new SeedDecorationWorld(access,world,seed); var vanilla = new SeedDecorationWorld(access,WorldgenAdapter.structureWorld(registries,seed,"overworld"),seed)) {
            var expected = vanilla.generate(0,0,true);
            var first = prediction.generate(0,0);
            prediction.generate(4,4);
            var repeated = prediction.generate(0,0);
            var position = new net.minecraft.core.BlockPos.MutableBlockPos();
            int compared = 0;
            for (var entry : expected.entrySet()) for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
                var actual = first.get(entry.getKey()); var reference = entry.getValue(); var again = repeated.get(entry.getKey());
                int y = reference.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
                position.set(reference.getPos().getMinBlockX() + x,y,reference.getPos().getMinBlockZ() + z);
                assertEquals(y,actual.getHeight(Heightmap.Types.WORLD_SURFACE,x,z));
                assertEquals(reference.getBlockState(position),actual.getBlockState(position));
                assertEquals(y,again.getHeight(Heightmap.Types.WORLD_SURFACE,x,z));
                assertEquals(reference.getBlockState(position),again.getBlockState(position));
                ++compared;
            }
            System.out.println("decoration columns matching vanilla and revisit=" + compared);
        }
    }

    @Test void paperPlacementRulesCarryIntoGeneratedBuildings() {
        long seed = -7619526468459309251L;
        var registries = WorldgenAdapter.registries();
        var profile = new SeedDecorationRules(java.util.Map.of("minecraft:villages",1728394),java.util.Set.of("minecraft:trial_chambers"));
        var sets = profile.placements(registries);
        var village = sets.listElements().filter(holder -> holder.key().identifier().getPath().equals("villages")).findFirst().orElseThrow().value();
        assertEquals(1728394,PlacementRules.read("minecraft:villages",(net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)village.placement()).salt());
        assertTrue(sets.listElements().noneMatch(holder -> holder.key().identifier().getPath().equals("trial_chambers")));
        var world = WorldgenAdapter.withPlacements(WorldgenAdapter.structureWorld(registries,seed,"overworld"),seed,sets);
        assertTrue(world.placements().possibleStructureSets().stream().noneMatch(holder -> holder.unwrapKey().orElseThrow().identifier().getPath().equals("trial_chambers")));
    }

    @Test void decoratesNetherAndEndCityBlocksWithoutEntities() {
        long seed = -1234;
        var registries = (RegistryAccess)WorldgenAdapter.registries();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),registries.registries()));
        try (var nether = new SeedDecorationWorld(access,WorldgenAdapter.structureWorld(registries,seed,"the_nether"),seed)) {
            assertEquals(4,nether.generate(0,0).size());
        }
        try (var locator = new Locator()) {
            var city = locator.search(seed,"structure","minecraft:end_cities",4096,64,-4096,2048).getFirst();
            try (var end = new SeedDecorationWorld(access,locator.mapWorld(seed,"the_end"),seed)) {
                var generated = end.generate(city.x() >> 4,city.z() >> 4);
                var position = new net.minecraft.core.BlockPos.MutableBlockPos();
                int purpur = 0;
                for (var chunk : generated.values()) for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
                    int y = chunk.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
                    var state = chunk.getBlockState(position.set(chunk.getPos().getMinBlockX() + x,y,chunk.getPos().getMinBlockZ() + z));
                    if (BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath().contains("purpur")) ++purpur;
                }
                System.out.println("end city=" + city.x() + "," + city.z() + " roof columns=" + purpur);
                assertTrue(purpur > 0);
            }
        }
    }
}
