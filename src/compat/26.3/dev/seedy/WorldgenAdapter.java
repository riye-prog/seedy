package dev.seedy;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;

final class WorldgenAdapter {
    private static HolderLookup.Provider loadedRegistries;
    static synchronized HolderLookup.Provider registries() {
        if (loadedRegistries != null) return loadedRegistries;
        var resources = new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, java.util.List.of(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources()));
        var base = net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY);
        var tags = net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources, base);
        var lookups = net.minecraft.tags.TagLoader.buildUpdatedLookups(base, tags);
        loadedRegistries = net.minecraft.resources.RegistryDataLoader.load(resources, lookups, net.minecraft.resources.RegistryDataLoader.WORLD_REGISTRIES, Runnable::run).join();
        return loadedRegistries;
    }
    static Locator.BiomeSampler sampler(HolderLookup.Provider registries, long seed) {
        var source = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.OVERWORLD).value();
        var state = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings);
        var resolver = source.createResolver(state.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
        return (x, y, z) -> resolver.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().orElseThrow().identifier().toString();
    }
    static Locator.StructureWorld structureWorld(HolderLookup.Provider registries, long seed, String dimension) {
        var source = Locator.source(registries, dimension);
        var key = switch (dimension) {
            case "the_nether" -> NoiseGeneratorSettings.NETHER;
            case "the_end" -> NoiseGeneratorSettings.END;
            default -> NoiseGeneratorSettings.OVERWORLD;
        };
        var settings = registries.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(key);
        var generator = new net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator(source, settings);
        var noise = settings.value().noiseSettings();
        var height = net.minecraft.world.level.LevelHeightAccessor.create(noise.minY(), noise.height());
        var random = RandomState.create(registries.lookupOrThrow(Registries.NOISE), seed, settings.value());
        var placements = net.minecraft.world.level.chunk.ChunkGeneratorStructureState.createForNormal(random, seed, generator.getOrigin(random), source, registries.lookupOrThrow(Registries.STRUCTURE_SET));
        var resolver = source.createResolver(random.createClimateSampler(SamplerContext.EMPTY_UNCACHED));
        Locator.BiomeSampler biomes = (x,y,z) -> resolver.getNoiseBiome(x >> 2, y >> 2, z >> 2).unwrapKey().orElseThrow().identifier().toString();
        return new Locator.StructureWorld(dimension, placements, generator, height, biomes);
    }

    static net.minecraft.server.packs.resources.MultiPackResourceManager resources() {
        return new net.minecraft.server.packs.resources.MultiPackResourceManager(net.minecraft.server.packs.PackType.SERVER_DATA, java.util.List.of(net.minecraft.server.packs.repository.ServerPacksSource.createVanillaPackSource().fullResources()));
    }

    static java.util.Optional<net.minecraft.world.level.levelgen.structure.Structure.GenerationStub> startPoint(net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure> holder, net.minecraft.core.RegistryAccess registries, Locator.StructureWorld world, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates, long seed, net.minecraft.world.level.ChunkPos position) {
        var random = world.placements().randomState();
        var context = new net.minecraft.world.level.levelgen.structure.Structure.GenerationContext(registries, world.generator(), world.generator().getBiomeSource(), random.createClimateSampler(SamplerContext.EMPTY_UNCACHED), random, templates, seed, position, world.height(), holder.value().biomes()::contains);
        return holder.value().findValidGenerationPoint(context);
    }

    static net.minecraft.world.level.levelgen.structure.StructureStart generate(net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure> holder, net.minecraft.core.RegistryAccess registries, Locator.StructureWorld world, net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates, long seed, net.minecraft.world.level.ChunkPos position) {
        var dimension = switch (world.dimension()) {
            case "the_nether" -> net.minecraft.world.level.Level.NETHER;
            case "the_end" -> net.minecraft.world.level.Level.END;
            default -> net.minecraft.world.level.Level.OVERWORLD;
        };
        var source = world.generator().getBiomeSource();
        var random = world.placements().randomState();
        return holder.value().generate(holder, dimension, registries, world.generator(), source, random.createClimateSampler(SamplerContext.EMPTY_UNCACHED), random, templates, seed, position, 0, world.height(), holder.value().biomes()::contains);
    }

}
