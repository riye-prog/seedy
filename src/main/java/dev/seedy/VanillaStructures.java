package dev.seedy;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.LevelStorageSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;

final class VanillaStructures implements AutoCloseable {
    record Generated(String name, StructureStart start) { }
    record StartPoint(String name, net.minecraft.core.BlockPos position) { }
    private final RegistryAccess registries;
    private final Path directory;
    private final LevelStorageSource.LevelStorageAccess storage;
    private final net.minecraft.server.packs.resources.MultiPackResourceManager resources;
    private final StructureTemplateManager templates;
    RegistryAccess registries() { return registries; }
    StructureTemplateManager templates() { return templates; }

    VanillaStructures(RegistryAccess worldRegistries) {
        registries = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(), worldRegistries.registries()));
        try {
            directory = Files.createTempDirectory("seedy-structure-templates-");
            storage = LevelStorageSource.createDefault(directory).createAccess("templates");
            resources = WorldgenAdapter.resources();
            templates = new StructureTemplateManager(resources, storage, net.minecraft.util.datafix.DataFixers.getDataFixer(), BuiltInRegistries.BLOCK);
        } catch (IOException e) { throw new IllegalStateException("Could not initialize vanilla structure templates", e); }
    }

    Generated generate(StructureSet set, Locator.StructureWorld world, long seed, ChunkPos position) {
        return select(set, seed, position, holder -> {
            var start = WorldgenAdapter.generate(holder, registries, world, templates, seed, position);
            return start.isValid() ? new Generated(holder.unwrapKey().orElseThrow().identifier().toString(), start) : null;
        });
    }

    StartPoint villageStart(StructureSet set, Locator.StructureWorld world, long seed, ChunkPos position) {
        return select(set, seed, position, holder -> WorldgenAdapter.startPoint(holder, registries, world, templates, seed, position)
            .map(stub -> new StartPoint(holder.unwrapKey().orElseThrow().identifier().toString(), stub.position())).orElse(null));
    }

    private <T> T select(StructureSet set, long seed, ChunkPos position, java.util.function.Function<net.minecraft.core.Holder<net.minecraft.world.level.levelgen.structure.Structure>, T> generator) {
        var entries = new ArrayList<>(set.structures());
        var random = new WorldgenRandom(new LegacyRandomSource(0));
        random.setLargeFeatureSeed(seed, position.x(), position.z());
        int weight = entries.stream().mapToInt(StructureSet.StructureSelectionEntry::weight).sum();
        while (!entries.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException("Search cancelled");
            int choice = entries.size() == 1 ? 0 : random.nextInt(weight);
            int index = 0;
            while (choice >= entries.get(index).weight()) choice -= entries.get(index++).weight();
            var selected = entries.remove(index);
            var holder = selected.structure();
            var generated = generator.apply(holder);
            if (generated != null) return generated;
            weight -= selected.weight();
        }
        return null;
    }

    @Override public void close() {
        resources.close();
        try {
            storage.close();
            try (var paths = Files.walk(directory)) {
                for (var path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        } catch (IOException e) { org.slf4j.LoggerFactory.getLogger("Seedy").warn("Could not remove temporary structure template directory", e); }
    }
}
