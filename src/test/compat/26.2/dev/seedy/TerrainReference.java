package dev.seedy;

final class TerrainReference {
    static boolean includesCarvers() { return false; }
    static net.minecraft.world.level.chunk.ChunkAccess generate(TerrainGenerator generator, net.minecraft.core.RegistryAccess registries) {
        var world = generator.world;
        var chunk = new net.minecraft.world.level.chunk.ProtoChunk(new net.minecraft.world.level.ChunkPos(0,0),net.minecraft.world.level.chunk.UpgradeData.EMPTY,world.height(),net.minecraft.world.level.chunk.PalettedContainerFactory.create(registries),null);
        chunk.fillBiomesFromNoise(world.generator().getBiomeSource(),world.placements().randomState().sampler());
        world.generator().fillFromNoise(net.minecraft.world.level.levelgen.blending.Blender.empty(),world.placements().randomState(),generator.structures,chunk).join();
        world.generator().buildSurface(chunk,new net.minecraft.world.level.levelgen.WorldGenerationContext(world.generator(),chunk),world.placements().randomState(),generator.structures,generator.biomes,net.minecraft.world.level.levelgen.blending.Blender.empty(),world.generator().getBiomeSource().possibleBiomes());
        return chunk;
    }
}
