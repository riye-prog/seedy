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
import java.util.Map;
import java.util.concurrent.CancellationException;

public final class Locator implements AutoCloseable {
    public static final int MAX_RADIUS = 1_048_576;
    public record SearchProgress(int checked, int found, int range, boolean partial) { }
    private record Candidate(net.minecraft.world.level.ChunkPos position, double distance) { }
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
    private record MapStartKey(long seed, String dimension, String target, String cityFilter, Integer salt, boolean pieces, int x, int z) { }
    private final java.util.Map<MapStartKey, java.util.Optional<Result>> mapStarts = new java.util.LinkedHashMap<>(512,0.75f,true) {
        @Override protected boolean removeEldestEntry(java.util.Map.Entry<MapStartKey, java.util.Optional<Result>> entry) { return size() > 4096; }
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
        return search(seed, kind, target, x, y, z, radius, query, dimension, cityFilter, 256, Map.of(), progress -> { });
    }

    public List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius, LootQuery query, String dimension, String cityFilter, Map<String, Integer> paperSalts) {
        return search(seed, kind, target, x, y, z, radius, query, dimension, cityFilter, 256, paperSalts, progress -> { });
    }

    public List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius, LootQuery query, String dimension, String cityFilter, Map<String, Integer> paperSalts, java.util.function.Consumer<SearchProgress> progress) {
        return search(seed, kind, target, x, y, z, radius, query, dimension, cityFilter, 256, paperSalts, progress);
    }

    private final Map<String, BiomeSampler> mapSamplers = new java.util.HashMap<>();

    BiomeSampler mapBiomes(long seed, String dimension) {
        validateDimension(dimension);
        if (structureSeed == null || structureSeed != seed) { structureWorlds.clear(); mapSamplers.clear(); structureSeed = seed; }
        var world = structureWorlds.computeIfAbsent(dimension, key -> WorldgenAdapter.structureWorld(registries, seed, key));
        return mapSamplers.computeIfAbsent(dimension,key -> WorldgenAdapter.mapBiomes(world, seed));
    }

    int mapHeight(long seed, int x, int z) {
        mapBiomes(seed, "overworld");
        var world = structureWorlds.get("overworld");
        return world.generator().getBaseHeight(x, z, net.minecraft.world.level.levelgen.Heightmap.Types.OCEAN_FLOOR_WG, world.height(), world.placements().randomState());
    }

    StructureWorld mapWorld(long seed, String dimension) {
        mapBiomes(seed,dimension);
        return structureWorlds.get(dimension);
    }

    List<Result> mapStructures(long seed, String target, int tileX, int tileZ, String dimension, String cityFilter) {
        return mapStructures(seed, target, tileX, tileZ, dimension, cityFilter, null);
    }

    List<Result> mapStructures(long seed, String target, int tileX, int tileZ, String dimension, String cityFilter, Integer paperSalt) {
        int x = tileX * 1024, z = tileZ * 1024;
        String setTarget = target.equals("minecraft:fortress") || target.equals("minecraft:bastion_remnant") ? "minecraft:nether_complexes" : target;
        return search(seed, "structure", target, x + 512, 64, z + 512, 1536, LootQuery.NONE, dimension, cityFilter, Integer.MAX_VALUE, paperSalt == null ? Map.of() : Map.of(setTarget, paperSalt), progress -> { }).stream()
            .filter(result -> result.x() >= x && result.x() < x + 1024 && result.z() >= z && result.z() < z + 1024).toList();
    }

    private List<Result> search(long seed, String kind, String target, int x, int y, int z, int radius, LootQuery query, String dimension, String cityFilter, int limit, Map<String, Integer> paperSalts, java.util.function.Consumer<SearchProgress> progress) {
        validateDimension(dimension);
        if (!List.of("any", "with_elytra", "without_elytra").contains(cityFilter)) throw new IllegalArgumentException("Choose all cities, with elytra, or without elytra.");
        if (!cityFilter.equals("any") && (!kind.equals("structure") || !target.equals("minecraft:end_cities"))) throw new IllegalArgumentException("The elytra filter only applies to End cities.");
        if (structureSeed == null || structureSeed != seed) { structureWorlds.clear(); mapSamplers.clear(); structureSeed = seed; }
        if (query.enabled() && (!kind.equals("structure") || !LootQuery.supports(target))) throw new IllegalArgumentException("Chest loot is supported for ancient cities and desert temples.");
        if (query.enabled() && query.requirements().stream().anyMatch(rule -> !LootCatalog.items().contains(rule.item()))) throw new IllegalArgumentException("Choose an item from the chest loot list.");
        if (radius < 128 || radius > MAX_RADIUS || Math.abs((long)x) > 29_990_000 || Math.abs((long)z) > 29_990_000 || y < -64 || y > 320) throw new IllegalArgumentException("Use a radius of 128–1048576 and coordinates inside world bounds.");
        var results = new ArrayList<Result>();
        if (kind.equals("structure")) {
            boolean specificNetherStructure = target.equals("minecraft:fortress") || target.equals("minecraft:bastion_remnant");
            String setTarget = specificNetherStructure ? "minecraft:nether_complexes" : target;
            var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals(setTarget)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown structure set."));
            var placement = set.value().placement();
            if (paperSalts.containsKey(setTarget)) {
                if (!(placement instanceof RandomSpreadStructurePlacement spread)) throw new IllegalArgumentException("This Paper salt does not apply to the selected structure.");
                placement = PlacementRules.withSalt(spread, paperSalts.get(setTarget));
            }
            if (!catalog(dimension).structures().contains(target)) throw new IllegalArgumentException("This structure is not available in the selected dimension.");
            var world = structureWorlds.computeIfAbsent(dimension, d -> WorldgenAdapter.structureWorld(registries, seed, d));
            Iterable<Candidate> candidates;
            if (placement instanceof RandomSpreadStructurePlacement spread) {
                candidates = () -> java.util.stream.StreamSupport.stream(new SearchRegions(x, z, spread.spacing() * 16, radius + 512).spliterator(), false)
                    .map(region -> new Candidate(spread.getPotentialStructureChunk(seed, region.x() * spread.spacing(), region.z() * spread.spacing()), region.distance())).iterator();
            } else if (placement instanceof ConcentricRingsStructurePlacement rings) {
                var positions = world.placements().getRingPositionsFor(rings);
                candidates = positions == null ? List.of() : positions.stream().map(pos -> new Candidate(pos, Math.hypot((long)pos.x() * 16 + 8 - x, (long)pos.z() * 16 + 8 - z))).sorted(Comparator.comparingDouble(Candidate::distance)).toList();
            } else throw new IllegalArgumentException("This placement type is not supported.");
            int checked = 0;
            double cutoff = radius;
            boolean partial = false;
            long deadline = System.nanoTime() + 120_000_000_000L;
            for (var candidate : candidates) {
                    checkCancelled();
                    if (candidate.distance() > cutoff + 512) break;
                    if (checked >= 100_000 || System.nanoTime() > deadline) { partial = true; break; }
                    var pos = candidate.position();
                    if (++checked % 32 == 0) progress.accept(new SearchProgress(checked, Math.min(limit, results.size()), (int)candidate.distance(), false));
                    if (Math.abs((long)pos.x() * 16) > 29_999_000 || Math.abs((long)pos.z() * 16) > 29_999_000 || !placement.isStructureChunk(world.placements(), pos.x(), pos.z())) continue;
                    if (results.size() >= limit) {
                        results.sort(Comparator.comparingLong(Result::distance));
                        if (results.size() > limit) results.subList(limit, results.size()).clear();
                        cutoff = Math.min(radius, results.getLast().distance());
                    }
                    if (vanillaStructures == null) vanillaStructures = new VanillaStructures((net.minecraft.core.RegistryAccess)registries);
                    if (limit == Integer.MAX_VALUE || !query.enabled()) {
                        var key = new MapStartKey(seed, dimension, target, cityFilter, paperSalts.get(setTarget), limit != Integer.MAX_VALUE, pos.x(), pos.z());
                        var marker = mapStarts.computeIfAbsent(key, ignored -> java.util.Optional.ofNullable(mapStart(seed, target, dimension, cityFilter, set.value(), world, pos, limit != Integer.MAX_VALUE)));
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
            progress.accept(new SearchProgress(checked, Math.min(limit, results.size()), (int)cutoff, partial));
        } else if (kind.equals("biome")) {
            if (!catalog(dimension).biomes().contains(target)) throw new IllegalArgumentException("This biome is not available in the selected dimension.");
            var sampler = structureWorlds.computeIfAbsent(dimension, key -> WorldgenAdapter.structureWorld(registries, seed, key)).biomes();
            int stride = 64;
            while ((long)(radius / stride) * (radius / stride) > 262_144) stride *= 2;
            for (int dx = -radius; dx <= radius; dx += stride) {
                checkCancelled();
                for (int dz = -radius; dz <= radius; dz += stride) {
                    if ((long)dx * dx + (long)dz * dz <= (long)radius * radius && sampler.at(x + dx, y, z + dz).equals(target)) {
                        add(results, target, x + dx, y, z + dz, x, z, radius, "Vanilla dimension noise biome at selected Y; " + stride + "-block sampling", dimension);
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

    private Result mapStart(long seed, String target, String dimension, String cityFilter, net.minecraft.world.level.levelgen.structure.StructureSet set, StructureWorld world, net.minecraft.world.level.ChunkPos position, boolean piecesRequired) {
        if (target.equals("minecraft:villages") && !piecesRequired) {
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
        if (!dev.seedy.recovery.RecoveryWorkload.checkpoint(() -> false)) throw new CancellationException("Search cancelled.");
    }
}
