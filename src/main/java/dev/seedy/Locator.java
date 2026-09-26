package dev.seedy;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CancellationException;

public final class Locator implements AutoCloseable {
    public interface BiomeSampler { String at(int x, int y, int z); }
    public record Position(int x, int y, int z) { }
    public record Result(String name, int x, Integer y, int z, long distance, String confidence, String dimension, ChestLoot loot, Position elytra) {
        public Result(String name, int x, Integer y, int z, long distance, String confidence, String dimension) { this(name, x, y, z, distance, confidence, dimension, null, null); }
    }
    public record Catalog(List<String> structures, List<String> biomes) { }
    private final HolderLookup.Provider registries = WorldgenAdapter.registries();
    public record StructureWorld(String dimension, net.minecraft.world.level.chunk.ChunkGeneratorStructureState placements, net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator generator, net.minecraft.world.level.LevelHeightAccessor height, BiomeSampler biomes) { }
    private final java.util.Map<String, StructureWorld> structureWorlds = new java.util.HashMap<>();
    private Long structureSeed;
    private VanillaStructures vanillaStructures;
    private ChestPredictor chestPredictor;
    private final java.util.Map<String, Catalog> dimensionCatalogs = new java.util.HashMap<>();
    private record MapStartKey(long seed, String dimension, String target, String cityFilter, int x, int z) { }
    private final java.util.Map<MapStartKey, java.util.Optional<Result>> mapStarts = new java.util.LinkedHashMap<>(512,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<MapStartKey, java.util.Optional<Result>> entry) { return size() > 512; }
    };

    public Catalog catalog() {
        var structures = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements()
            .filter(h -> h.value().placement() instanceof RandomSpreadStructurePlacement || h.value().placement() instanceof ConcentricRingsStructurePlacement)
            .map(h -> h.key().identifier().toString()).sorted().toList();
        var source = MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var biomes = source.possibleBiomes().stream().map(h -> h.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
        return new Catalog(structures, biomes);
    }

    public Catalog catalog(String dimension) {
        validateDimension(dimension);
        return dimensionCatalogs.computeIfAbsent(dimension, key -> {
            var biomes = source(registries, key).possibleBiomes().stream().map(holder -> holder.unwrapKey().orElseThrow().identifier().toString()).sorted().toList();
            var structures = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements()
                .filter(holder -> holder.value().placement() instanceof RandomSpreadStructurePlacement || holder.value().placement() instanceof ConcentricRingsStructurePlacement)
                .filter(holder -> holder.value().structures().stream().anyMatch(entry -> entry.structure().value().biomes().stream().anyMatch(biome -> biomes.contains(biome.unwrapKey().orElseThrow().identifier().toString()))))
                .map(holder -> holder.key().identifier().toString()).sorted().toList();
            if (key.equals("the_nether")) structures = java.util.stream.Stream.concat(structures.stream(), java.util.stream.Stream.of("minecraft:fortress", "minecraft:bastion_remnant")).sorted().toList();
            return new Catalog(structures, biomes);
        });
    }

    private String dimensionFor(String target) {
        return java.util.stream.Stream.of("overworld", "the_nether", "the_end").filter(dimension -> catalog(dimension).structures().contains(target)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown structure set."));
    }

    private static void validateDimension(String dimension) {
        if (!List.of("overworld", "the_nether", "the_end").contains(dimension)) throw new IllegalArgumentException("Choose Overworld, Nether, or End.");
    }

    static Position elytraPosition(net.minecraft.world.level.levelgen.structure.StructureStart start) {
        for (var piece : start.getPieces()) {
            if (!(piece instanceof net.minecraft.world.level.levelgen.structure.structures.EndCityPieces.EndCityPiece city)) continue;
            for (var marker : city.template().filterBlocks(city.templatePosition(), city.placeSettings(), net.minecraft.world.level.block.Blocks.STRUCTURE_BLOCK)) {
                if (marker.nbt() == null || !marker.nbt().getStringOr("mode", "").equals("DATA") || !marker.nbt().getStringOr("metadata", "").startsWith("Elytra")) continue;
                return new Position(marker.pos().getX(), marker.pos().getY(), marker.pos().getZ());
            }
        }
        return null;
    }

    public List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius) {
        return search(seed, kind, target, x, y, z, radius, LootQuery.NONE);
    }

    public List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius, LootQuery query) {
        return search(seed, kind, target, x, y, z, radius, query, kind.equals("structure") ? dimensionFor(target) : "overworld", "any");
    }

    public List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius, LootQuery query, String dimension, String cityFilter) {
        return search(seed, kind, target, x, y, z, radius, query, dimension, cityFilter, 128);
    }

    BiomeSampler mapBiomes(long seed, String dimension) {
        validateDimension(dimension);
        if (structureSeed == null || structureSeed != seed) { structureWorlds.clear(); structureSeed = seed; }
        return structureWorlds.computeIfAbsent(dimension, key -> WorldgenAdapter.structureWorld(registries, seed, key)).biomes();
    }

    List<Result> mapStructures(long seed, String target, int tileX, int tileZ, String dimension, String cityFilter) {
        int x = tileX * 1024, z = tileZ * 1024;
        return search(seed, "structure", target, x + 512, 64, z + 512, 1536, LootQuery.NONE, dimension, cityFilter, Integer.MAX_VALUE).stream()
            .filter(result -> result.x() >= x && result.x() < x + 1024 && result.z() >= z && result.z() < z + 1024).toList();
    }

    private List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius, LootQuery query, String dimension, String cityFilter, int limit) {
        validateDimension(dimension);
        if (!List.of("any", "with_elytra", "without_elytra").contains(cityFilter)) throw new IllegalArgumentException("Choose all cities, with elytra, or without elytra.");
        if (!cityFilter.equals("any") && (!kind.equals("structure") || !target.equals("minecraft:end_cities"))) throw new IllegalArgumentException("The elytra filter only applies to End cities.");
        if (structureSeed == null || structureSeed != seed) { structureWorlds.clear(); structureSeed = seed; }
        if (query.enabled() && (!kind.equals("structure") || !LootQuery.supports(target))) throw new IllegalArgumentException("Chest loot is supported for ancient cities and desert temples.");
        if (query.enabled() && query.requirements().stream().anyMatch(rule -> !LootCatalog.items().contains(rule.item()))) throw new IllegalArgumentException("Choose an item from the chest loot list.");
        if (radius < 128 || radius > 8192 || Math.abs((long)x) > 29_990_000 || Math.abs((long)z) > 29_990_000 || y < -64 || y > 320) throw new IllegalArgumentException("Use a radius of 128–8192 and coordinates inside world bounds.");
        var results = new ArrayList<Result>();
        if (kind.equals("structure")) {
            boolean specificNetherStructure = target.equals("minecraft:fortress") || target.equals("minecraft:bastion_remnant");
            String setTarget = specificNetherStructure ? "minecraft:nether_complexes" : target;
            var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals(setTarget)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown structure set."));
            var placement = set.value().placement();
            if (!catalog(dimension).structures().contains(target)) throw new IllegalArgumentException("This structure is not available in the selected dimension.");
            var world = structureWorlds.computeIfAbsent(dimension, d -> WorldgenAdapter.structureWorld(registries, seed, d));
            var candidates = new ArrayList<net.minecraft.world.level.ChunkPos>();
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                int spacing = spread.spacing();
                for (int rx = Math.floorDiv((x - radius) >> 4, spacing); rx <= Math.floorDiv((x + radius) >> 4, spacing); rx++) {
                    checkCancelled();
                    for (int rz = Math.floorDiv((z - radius) >> 4, spacing); rz <= Math.floorDiv((z + radius) >> 4, spacing); rz++) candidates.add(spread.getPotentialStructureChunk(seed, rx * spacing, rz * spacing));
                }
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                var positions = world.placements().getRingPositionsFor(rings);
                if (positions != null) candidates.addAll(positions);
            } else throw new IllegalArgumentException("This placement type is not supported.");
            for (var pos : candidates) {
                    checkCancelled();
                    int px = pos.x() * 16 + 8, pz = pos.z() * 16 + 8;
                    if (Math.hypot((long)px - x, (long)pz - z) > radius || !placement.isStructureChunk(world.placements(), pos.x(), pos.z())) continue;
                    if (vanillaStructures == null) vanillaStructures = new VanillaStructures((net.minecraft.core.RegistryAccess)registries);
                    if (limit == Integer.MAX_VALUE) {
                        var key = new MapStartKey(seed, dimension, target, cityFilter, pos.x(), pos.z());
                        var marker = mapStarts.computeIfAbsent(key, ignored -> java.util.Optional.ofNullable(mapStart(seed, target, dimension, cityFilter, set.value(), world, pos)));
                        if (marker.isPresent()) {
                            var result = marker.get();
                            double distance = Math.hypot((long)result.x() - x, (long)result.z() - z);
                            if (distance <= radius) results.add(new Result(result.name(), result.x(), result.y(), result.z(), Math.round(distance), result.confidence(), dimension, null, result.elytra()));
                        }
                        continue;
                    }
                    var generated = vanillaStructures.generate(set.value(), world, seed, pos);
                    if (generated == null || specificNetherStructure && !generated.name().equals(target)) continue;
                    var elytra = elytraPosition(generated.start());
                    if (cityFilter.equals("with_elytra") && elytra == null || cityFilter.equals("without_elytra") && elytra != null) continue;
                    ChestLoot chestLoot = null;
                    if (query.enabled()) {
                        if (chestPredictor == null) chestPredictor = new ChestPredictor(vanillaStructures);
                        chestLoot = chestPredictor.predict(seed, generated, query);
                        if (!query.accepts(chestLoot.totals())) continue;
                    }
                    var pieces = generated.start().getPieces();
                    var portalRoom = pieces.stream().filter(piece -> piece instanceof StrongholdPieces.PortalRoom).findFirst();
                    var center = portalRoom.orElse(pieces.getFirst()).getBoundingBox().getCenter();
                    Integer height = target.equals("minecraft:ancient_cities") || target.equals("minecraft:trial_chambers") || target.equals("minecraft:strongholds") || target.equals("minecraft:end_cities") ? center.getY() : null;
                    double distance = Math.hypot((long)center.getX() - x, (long)center.getZ() - z);
                    if (distance <= radius) results.add(new Result(generated.name(), center.getX(), height, center.getZ(), Math.round(distance), portalRoom.isPresent() ? "Vanilla stronghold portal room" : "Vanilla structure start and pieces generated for this seed and game version", dimension, chestLoot, elytra));
            }
        } else if (kind.equals("biome")) {
            if (!catalog(dimension).biomes().contains(target)) throw new IllegalArgumentException("This biome is not available in the selected dimension.");
            var sampler = structureWorlds.computeIfAbsent(dimension, key -> WorldgenAdapter.structureWorld(registries, seed, key)).biomes();
            for (int dx = -radius; dx <= radius; dx += 64) {
                checkCancelled();
                for (int dz = -radius; dz <= radius; dz += 64) {
                    if ((long)dx * dx + (long)dz * dz <= (long)radius * radius && sampler.at(x + dx, y, z + dz).equals(target)) {
                        add(results, target, x + dx, y, z + dz, x, z, radius, "Vanilla dimension noise biome at selected Y; 64-block sampling", dimension);
                    }
                }
            }
        } else throw new IllegalArgumentException("Choose structure or biome.");
        return results.stream().sorted(Comparator.comparingLong(Result::distance)).limit(limit).toList();
    }

    static net.minecraft.world.level.biome.BiomeSource source(HolderLookup.Provider registries, String dimension) {
        if (dimension.equals("the_end")) return net.minecraft.world.level.biome.TheEndBiomeSource.create(registries.lookupOrThrow(Registries.BIOME));
        var preset = dimension.equals("the_nether") ? MultiNoiseBiomeSourceParameterLists.NETHER : MultiNoiseBiomeSourceParameterLists.OVERWORLD;
        return MultiNoiseBiomeSource.createFromPreset(registries.lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST).getOrThrow(preset));
    }

    private Result mapStart(long seed, String target, String dimension, String cityFilter, net.minecraft.world.level.levelgen.structure.StructureSet set, StructureWorld world, net.minecraft.world.level.ChunkPos position) {
        if (target.equals("minecraft:villages")) {
            var point = vanillaStructures.villageStart(set, world, seed, position);
            return point == null ? null : new Result(point.name(), point.position().getX(), null, point.position().getZ(), 0, "Vanilla village start with terrain and biome checks", dimension);
        }
        var generated = vanillaStructures.generate(set, world, seed, position);
        if (generated == null || (target.equals("minecraft:fortress") || target.equals("minecraft:bastion_remnant")) && !generated.name().equals(target)) return null;
        var elytra = elytraPosition(generated.start());
        if (cityFilter.equals("with_elytra") && elytra == null || cityFilter.equals("without_elytra") && elytra != null) return null;
        var pieces = generated.start().getPieces();
        var portalRoom = pieces.stream().filter(piece -> piece instanceof StrongholdPieces.PortalRoom).findFirst();
        var center = portalRoom.orElse(pieces.getFirst()).getBoundingBox().getCenter();
        Integer height = List.of("minecraft:ancient_cities", "minecraft:trial_chambers", "minecraft:strongholds", "minecraft:end_cities").contains(target) ? center.getY() : null;
        return new Result(generated.name(), center.getX(), height, center.getZ(), 0, portalRoom.isPresent() ? "Vanilla stronghold portal room" : "Vanilla structure start and pieces generated for this seed and game version", dimension, null, elytra);
    }

    private static void add(List<Result> results, String name, int px, Integer py, int pz, int x, int z, int radius, String confidence, String dimension) {
        double distance = Math.hypot((long)px - x, (long)pz - z);
        if (distance <= radius) results.add(new Result(name, px, py, pz, Math.round(distance), confidence, dimension));
    }

    @Override public void close() { if (vanillaStructures != null) { vanillaStructures.close(); vanillaStructures = null; chestPredictor = null; } }

    private static void checkCancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Search cancelled.");
    }
}
