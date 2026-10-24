package dev.seedy;

import java.util.List;
import java.util.function.Predicate;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

final class TerrainGenerator {
    private static final java.lang.reflect.Method FILL = GeneratorAccess.method("doFill",Blender.class,StructureManager.class,RandomState.class,ChunkAccess.class,int.class,int.class);
    final Locator.StructureWorld world;
    final BiomeManager biomes;
    final StructureManager structures;
    private final PalettedContainerFactory containers;
    private final RegistryAccess registries;
    private final long seed;
    private final java.util.Map<Long,net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> biomeCache = new java.util.LinkedHashMap<>(16384,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome>> entry) { return size() > 16384; }
    };
    private final java.util.Map<Long,net.minecraft.world.level.biome.BiomeGenerationSettings> carverBiomes = new java.util.LinkedHashMap<>(4096,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,net.minecraft.world.level.biome.BiomeGenerationSettings> entry) { return size() > 4096; }
    };

    TerrainGenerator(RegistryAccess registries, Locator.StructureWorld world, long seed) { this(registries,world,seed,null); }

    TerrainGenerator(RegistryAccess registries, Locator.StructureWorld world, long seed, StructureManager structureManager) {
        this.world = world; this.registries = registries; this.seed = seed;
        containers = PalettedContainerFactory.create(registries);
        var climate = world.placements().randomState().sampler();
        biomes = new BiomeManager((x,y,z) -> biomeCache.computeIfAbsent(net.minecraft.core.BlockPos.asLong(x,y,z),ignored -> world.generator().getBiomeSource().getNoiseBiome(x,y,z,climate)),BiomeManager.obfuscateSeed(seed));
        structures = structureManager != null ? structureManager : new StructureManager(null,new WorldOptions(seed,false,false),null) {
            @Override public List<StructureStart> startsForStructure(ChunkPos position, Predicate<Structure> predicate) { return List.of(); }
        };
    }

    ProtoChunk generate(int x, int z) { return generate(x,z,true); }

    ProtoChunk generate(int x, int z, boolean carvers) {
        GeneratorAccess.checkpoint();
        var chunk = new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,world.height(),containers,null);
        var generator = world.generator(); var random = world.placements().randomState(); var settings = generator.generatorSettings().value();
        var noiseSettings = settings.noiseSettings().clampToHeightAccessor(chunk);
        int cellHeight = noiseSettings.getCellHeight();
        for (var section : chunk.getSections()) section.acquire();
        try { GeneratorAccess.invoke(FILL,generator,Blender.empty(),structures,random,chunk,Math.floorDiv(noiseSettings.minY(),cellHeight),Math.floorDiv(noiseSettings.height(),cellHeight)); }
        finally { for (var section : chunk.getSections()) section.release(); }
        GeneratorAccess.checkpoint();
        generator.buildSurface(chunk,new WorldGenerationContext(generator,chunk),random,structures,biomes,Blender.empty(),generator.getBiomeSource().possibleBiomes());
        GeneratorAccess.checkpoint();
        if (!carvers) return chunk;
        var noise = chunk.getOrCreateNoiseChunk(ignored -> { throw new IllegalStateException("Missing generated noise"); });
        var context = new CarvingContext(generator,registries,chunk,noise,random,settings.surfaceRule());
        var carving = chunk.getOrCreateCarvingMask();
        var carvingRandom = new WorldgenRandom(new LegacyRandomSource(0));
        for (int dx = -8; dx <= 8; ++dx) for (int dz = -8; dz <= 8; ++dz) {
            GeneratorAccess.checkpoint();
            var position = new ChunkPos(x + dx,z + dz);
            var features = carverBiomes.computeIfAbsent(position.pack(),ignored -> generator.getBiomeGenerationSettings(generator.getBiomeSource().getNoiseBiome(position.x() * 4,0,position.z() * 4,random.sampler())));
            int index = 0;
            for (var carver : features.getCarvers()) {
                carvingRandom.setLargeFeatureSeed(seed + index++,position.x(),position.z());
                if (carver.value().isStartChunk(carvingRandom)) carver.value().carve(context,chunk,biomes::getBiome,carvingRandom,noise.aquifer(),position,carving);
            }
        }
        return chunk;
    }

    ProtoChunk empty(int x, int z) { return new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,world.height(),containers,null); }

    ProtoChunk copy(ProtoChunk original) {
        var sections = java.util.Arrays.stream(original.getSections()).map(net.minecraft.world.level.chunk.LevelChunkSection::copy).toArray(net.minecraft.world.level.chunk.LevelChunkSection[]::new);
        var chunk = new ProtoChunk(original.getPos(),UpgradeData.EMPTY,sections,new net.minecraft.world.ticks.ProtoChunkTicks<>(),new net.minecraft.world.ticks.ProtoChunkTicks<>(),world.height(),containers,null);
        for (var entry : original.getHeightmaps()) chunk.setHeightmap(entry.getKey(),entry.getValue().getRawData().clone());
        chunk.setPersistedStatus(net.minecraft.world.level.chunk.status.ChunkStatus.FEATURES);
        return chunk;
    }

    net.minecraft.core.Holder<net.minecraft.world.level.biome.Biome> noiseBiome(int x, int y, int z) {
        return biomeCache.computeIfAbsent(net.minecraft.core.BlockPos.asLong(x,y,z),ignored -> world.generator().getBiomeSource().getNoiseBiome(x,y,z,world.placements().randomState().sampler()));
    }

    void fillBiomes(ProtoChunk chunk) { chunk.fillBiomesFromNoise((x,y,z,climate) -> noiseBiome(x,y,z),world.placements().randomState().sampler()); }
    static net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes(net.minecraft.world.level.WorldGenLevel level) { return net.minecraft.world.attribute.EnvironmentAttributeReader.EMPTY; }
    static java.util.List<net.minecraft.world.level.levelgen.structure.StructureStart> startsFor(StructureManager manager, ProtoChunk chunk, net.minecraft.world.level.levelgen.structure.Structure structure) { return manager.startsForStructure(net.minecraft.core.SectionPos.of(chunk.getPos(),chunk.getMinSectionY()),structure); }
    static boolean placeFeature(net.minecraft.world.level.levelgen.placement.PlacedFeature feature, net.minecraft.world.level.WorldGenLevel level, net.minecraft.world.level.chunk.ChunkGenerator generator, net.minecraft.util.RandomSource random, net.minecraft.core.BlockPos origin) { return feature.placeWithBiomeCheck(level,generator,random,origin); }
    static boolean subsurfaceFossil(net.minecraft.world.level.levelgen.placement.PlacedFeature feature) { return feature.getFeatures().anyMatch(configured -> configured.value().feature() instanceof net.minecraft.world.level.levelgen.feature.FossilFeature); }
}
