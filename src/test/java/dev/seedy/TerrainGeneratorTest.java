package dev.seedy;

import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainGeneratorTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); SeedTerrainProbe.prepareVanillaTags(); }

    @Test void generatesSurfaceBlocksWithoutAClientOrServerWorld() {
        var registries = WorldgenAdapter.registries();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),((RegistryAccess)registries).registries()));
        for (String dimension : List.of("overworld","the_nether","the_end")) {
            var world = WorldgenAdapter.structureWorld(registries,2589511696370800371L,dimension);
            var generator = new TerrainGenerator(access,world,2589511696370800371L);
            long started = System.nanoTime();
            var chunk = generator.generate(0,0);
            assertEquals(0,chunk.getPos().x());
            int visible = 0;
            var blocks = new java.util.HashSet<String>();
            var pos = new net.minecraft.core.BlockPos.MutableBlockPos();
            for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
                int height = chunk.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x,z);
                if (height < chunk.getMinY()) continue;
                var state = chunk.getBlockState(pos.set(x,height,z));
                assertFalse(state.isAir());
                blocks.add(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
                ++visible;
            }
            assertTrue(visible > 0,dimension);
            if (dimension.equals("overworld")) assertTrue(blocks.stream().anyMatch(name -> name.contains("grass") || name.contains("sand") || name.contains("water")),blocks.toString());
            System.out.println(dimension + " terrain ms=" + (System.nanoTime() - started) / 1_000_000 + " surface=" + blocks);
        }
    }

    @Test void cachedTerrainMatchesTheVanillaGenerationEntryPoint() {
        var registries = WorldgenAdapter.registries();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),((RegistryAccess)registries).registries()));
        long seed = -7619526468459309251L;
        var generator = new TerrainGenerator(access,WorldgenAdapter.structureWorld(registries,seed,"overworld"),seed);
        var actual = generator.generate(0,0,TerrainReference.includesCarvers());
        var reference = TerrainReference.generate(generator,access);
        int matching = 0;
        var position = new net.minecraft.core.BlockPos.MutableBlockPos();
        for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
            int expectedY = reference.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x,z), actualY = actual.getHeight(Heightmap.Types.WORLD_SURFACE_WG,x,z);
            if (actualY == expectedY && actual.getBlockState(position.set(x,actualY,z)).equals(reference.getBlockState(position))) ++matching;
        }
        assertEquals(256,matching);
        System.out.println("surface columns matching vanilla=" + matching);
    }
}
