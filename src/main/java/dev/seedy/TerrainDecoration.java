package dev.seedy;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.FeatureSorter;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;

final class TerrainDecoration {
    private static final Field FEATURES = featureField();
    private final List<FeatureSorter.StepFeatureData> features;
    private final Map<Integer,List<Structure>> structures;
    private final ChunkGenerator generator;

    private static Field featureField() {
        try { var field = ChunkGenerator.class.getDeclaredField("featuresPerStep"); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported decoration layout",failure); }
    }

    TerrainDecoration(ChunkGenerator generator, RegistryAccess registries) {
        this.generator = generator;
        try { features = ((Supplier<List<FeatureSorter.StepFeatureData>>)FEATURES.get(generator)).get(); }
        catch (IllegalAccessException failure) { throw new IllegalStateException("Could not read decoration order",failure); }
        structures = registries.lookupOrThrow(Registries.STRUCTURE).stream().collect(java.util.stream.Collectors.groupingBy(structure -> structure.step().ordinal()));
    }

    void decorate(WorldGenLevel level, ProtoChunk chunk, StructureManager manager) {
        var center = chunk.getPos();
        var origin = new net.minecraft.core.BlockPos(center.getMinBlockX(),level.getMinY(),center.getMinBlockZ());
        var random = new WorldgenRandom(new XoroshiroRandomSource(0));
        long decorationSeed = random.setDecorationSeed(level.getSeed(),origin.getX(),origin.getZ());
        var biomes = new HashSet<Holder<Biome>>();
        for (int x = center.x() - 1; x <= center.x() + 1; ++x) for (int z = center.z() - 1; z <= center.z() + 1; ++z)
            for (var section : level.getChunk(x,z).getSections()) section.getBiomes().getAll(biomes::add);
        biomes.retainAll(generator.getBiomeSource().possibleBiomes());
        var bounds = new BoundingBox(center.getMinBlockX(),chunk.getMinY() + 1,center.getMinBlockZ(),center.getMinBlockX() + 15,chunk.getMaxY(),center.getMinBlockZ() + 15);
        for (int step = 0; step < Math.max(features.size(),GenerationStep.Decoration.values().length); ++step) {
            int index = 0;
            for (var structure : structures.getOrDefault(step,List.of())) {
                GeneratorAccess.checkpoint(); random.setFeatureSeed(decorationSeed,index++,step);
                for (var start : TerrainGenerator.startsFor(manager,chunk,structure)) start.placeInChunk(level,manager,generator,random,bounds,center);
            }
            if (step >= features.size()) continue;
            var data = features.get(step);
            var indices = new it.unimi.dsi.fastutil.ints.IntAVLTreeSet();
            for (var biome : biomes) {
                var settings = generator.getBiomeGenerationSettings(biome).features();
                if (step < settings.size()) settings.get(step).stream().map(Holder::value).forEach(feature -> indices.add(data.indexMapping().applyAsInt(feature)));
            }
            for (int featureIndex : indices) {
                GeneratorAccess.checkpoint();
                var feature = data.features().get(featureIndex);
                if (TerrainGenerator.subsurfaceFossil(feature)) continue;
                random.setFeatureSeed(decorationSeed,featureIndex,step);
                try { TerrainGenerator.placeFeature(feature,level,generator,random,origin); }
                catch (RuntimeException failure) { throw new IllegalStateException("Could not predict " + level.registryAccess().lookupOrThrow(Registries.PLACED_FEATURE).getResourceKey(feature).map(Object::toString).orElse("feature"),failure); }
            }
        }
    }
}
