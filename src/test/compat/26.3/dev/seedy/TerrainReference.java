package dev.seedy;

final class TerrainReference {
    static boolean includesCarvers() { return true; }
    static net.minecraft.world.level.chunk.ChunkAccess generate(TerrainGenerator generator, net.minecraft.core.RegistryAccess registries) {
        var world = generator.world;
        var chunk = new net.minecraft.world.level.chunk.ProtoChunk(new net.minecraft.world.level.ChunkPos(0,0),net.minecraft.world.level.chunk.UpgradeData.EMPTY,world.height(),net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries),null);
        chunk.fillBiomesFromNoise(world.generator().getBiomeSource().createUncachedResolver(world.placements().randomState()));
        return world.generator().buildTerrain(chunk,net.minecraft.world.level.levelgen.blending.Blender.empty(),world.placements().randomState(),generator.structures,generator.biomes,null,world.generator().getBiomeSource().possibleBiomes()).join();
    }
}
