package dev.seedy;

import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.rule.MaterialRule;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

final class TerrainGenerator {
    private static final java.lang.reflect.Method CREATE = GeneratorAccess.method("createNoiseChunk",ChunkAccess.class,StructureManager.class,Blender.class,RandomState.class,NoiseSettings.class);
    private static final java.lang.reflect.Method FILL = GeneratorAccess.method("doFill",NoiseChunk.class,ChunkAccess.class);
    private static final java.lang.reflect.Method SURFACE = GeneratorAccess.method("buildSurface",ChunkAccess.class,NoiseChunk.class,RandomState.class,BiomeManager.class,Set.class,MaterialRule.class);
    private static final java.lang.reflect.Method CARVE = GeneratorAccess.method("applyCarvingMask",ChunkAccess.class,net.minecraft.world.level.chunk.CarvingMask.class,RandomState.class,MaterialRule.class,net.minecraft.world.level.levelgen.WorldGenerationContext.class,NoiseChunk.class,java.util.function.Function.class,net.minecraft.world.level.chunk.CarvingMask.Filter.class);
    final Locator.StructureWorld world;
    final BiomeManager biomes;
    final StructureManager structures;
    private final PalettedContainerFactory containers;
    private final net.minecraft.world.level.biome.BiomeResolver resolver;
    private final net.minecraft.world.level.biome.BiomeResolver carverResolver;
    private final java.util.Map<Long,Holder<Biome>> biomeCache = new java.util.LinkedHashMap<>(16384,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,Holder<Biome>> entry) { return size() > 16384; }
    };
    private final java.util.Map<Long,net.minecraft.world.level.biome.BiomeGenerationSettings> carverBiomes = new java.util.LinkedHashMap<>(4096,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<Long,net.minecraft.world.level.biome.BiomeGenerationSettings> entry) { return size() > 4096; }
    };

    TerrainGenerator(RegistryAccess registries, Locator.StructureWorld world, long seed) { this(registries,world,seed,null); }

    TerrainGenerator(RegistryAccess registries, Locator.StructureWorld world, long seed, StructureManager structureManager) {
        this.world = world;
        containers = PalettedContainerFactory.create(registries);
        resolver = world.generator().getBiomeSource().createResolver(world.placements().randomState().createClimateSampler(SamplerContext.EMPTY_UNCACHED));
        carverResolver = world.generator().getBiomeSource().createUncachedResolver(world.placements().randomState());
        biomes = new BiomeManager((x,y,z) -> biomeCache.computeIfAbsent(net.minecraft.core.BlockPos.asLong(x,y,z),ignored -> resolver.getNoiseBiome(x,y,z)),BiomeManager.obfuscateSeed(seed));
        structures = structureManager != null ? structureManager : new StructureManager(null,new WorldOptions(seed,false,false),null) {
            @Override public List<StructureStart> startsForStructure(int x, int z, Predicate<Structure> predicate) { return List.of(); }
        };
    }

    ProtoChunk generate(int x, int z) { return generate(x,z,true); }

    ProtoChunk generate(int x, int z, boolean carvers) {
        GeneratorAccess.checkpoint();
        var chunk = new ProtoChunk(new ChunkPos(x,z),UpgradeData.EMPTY,world.height(),containers,null);
        var generator = world.generator(); var random = world.placements().randomState(); var settings = generator.generatorSettings().value();
        try (var noise = (NoiseChunk)GeneratorAccess.invoke(CREATE,generator,chunk,structures,Blender.empty(),random,settings.noiseSettings().clampToHeightAccessor(chunk))) {
            for (var section : chunk.getSections()) section.acquire();
            try { GeneratorAccess.invoke(FILL,generator,noise,chunk); }
            finally { for (var section : chunk.getSections()) section.release(); }
            GeneratorAccess.checkpoint();
            GeneratorAccess.invoke(SURFACE,generator,chunk,noise,random,biomes,generator.getBiomeSource().possibleBiomes(),settings.materialRule().value());
            GeneratorAccess.checkpoint();
            if (carvers) carve(chunk,noise,settings.materialRule().value());
        }
        GeneratorAccess.checkpoint();
        return chunk;
    }

    private void carve(ProtoChunk chunk, NoiseChunk noise, MaterialRule rule) {
        var generator = world.generator(); var state = world.placements().randomState();
        var context = new net.minecraft.world.level.levelgen.WorldGenerationContext(generator,chunk);
        var mask = new net.minecraft.world.level.chunk.CarvingMask(context.getMinGenY() + 1,context.getMinGenY() + context.getGenDepth() - 8);
        var random = new net.minecraft.world.level.levelgen.WorldgenRandom(new net.minecraft.world.level.levelgen.LegacyRandomSource(0));
        var center = chunk.getPos();
        for (int dx = -8; dx <= 8; ++dx) for (int dz = -8; dz <= 8; ++dz) {
            GeneratorAccess.checkpoint();
            var position = new ChunkPos(center.x() + dx,center.z() + dz);
            var features = carverBiomes.computeIfAbsent(position.pack(),ignored -> generator.getBiomeGenerationSettings(carverResolver.getNoiseBiome(position.x() * 4,0,position.z() * 4)));
            int index = 0;
            for (var carver : features.getCarvers()) {
                random.setLargeFeatureSeed(state.seed() + index++,position.x(),position.z());
                if (carver.value().isStartChunk(random)) carver.value().carve(context,random,center,position,mask);
            }
        }
        if (!mask.isEmpty()) {
            java.util.function.Function<net.minecraft.core.BlockPos,Holder<Biome>> biome = biomes.withDifferentSource(carverResolver)::getBiome;
            GeneratorAccess.invoke(CARVE,generator,chunk,mask,state,rule,context,noise,biome,null);
        }
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
        return biomeCache.computeIfAbsent(net.minecraft.core.BlockPos.asLong(x,y,z),ignored -> resolver.getNoiseBiome(x,y,z));
    }

    void fillBiomes(ProtoChunk chunk) { chunk.fillBiomesFromNoise(this::noiseBiome); }
    static net.minecraft.world.attribute.EnvironmentAttributeReader environmentAttributes(net.minecraft.world.level.WorldGenLevel level) { return net.minecraft.world.attribute.EnvironmentAttributeSystem.builder().addStaticLayers(level).build(); }
    static java.util.List<net.minecraft.world.level.levelgen.structure.StructureStart> startsFor(StructureManager manager, ProtoChunk chunk, net.minecraft.world.level.levelgen.structure.Structure structure) { return manager.startsForStructure(chunk.getPos().x(),chunk.getPos().z(),structure); }
    static boolean placeFeature(net.minecraft.world.level.levelgen.placement.PlacedFeature feature, net.minecraft.world.level.WorldGenLevel level, net.minecraft.world.level.chunk.ChunkGenerator generator, net.minecraft.util.RandomSource random, net.minecraft.core.BlockPos origin) { return new net.minecraft.world.level.levelgen.placement.FeaturePlacer(level,generator).placeWithBiomeCheck(feature,random,origin); }
    static boolean subsurfaceFossil(net.minecraft.world.level.levelgen.placement.PlacedFeature feature) { return feature.getFeatures().anyMatch(configured -> configured.value() instanceof net.minecraft.world.level.levelgen.feature.FossilFeature); }
}
