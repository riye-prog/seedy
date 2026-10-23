package dev.seedy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class SeedMap implements AutoCloseable {
    public record Request(long id, long seed, String dimension, int y, int step, int minX, int minZ, int maxX, int maxZ, String target, String cityFilter, Integer paperSalt, boolean structuresAllowed, String warning, boolean relief) {
        public Request(long id, long seed, String dimension, int y, int step, int minX, int minZ, int maxX, int maxZ, String target, String cityFilter) {
            this(id, seed, dimension, y, step, minX, minZ, maxX, maxZ, target, cityFilter, null, true, "", false);
        }
        public Request(long id, long seed, String dimension, int y, int step, int minX, int minZ, int maxX, int maxZ, String target, String cityFilter, Integer paperSalt, boolean structuresAllowed, String warning) {
            this(id, seed, dimension, y, step, minX, minZ, maxX, maxZ, target, cityFilter, paperSalt, structuresAllowed, warning, false);
        }
        public Request {
            if (!List.of("overworld", "the_nether", "the_end").contains(dimension) || y < -64 || y > 320 || step < 1 || step > 65536 || (step & (step - 1)) != 0) throw new IllegalArgumentException("Invalid map settings.");
            if (minX > maxX || minZ > maxZ || Math.abs((long)minX) > 29_970_000 || Math.abs((long)maxX) > 29_970_000 || Math.abs((long)minZ) > 29_970_000 || Math.abs((long)maxZ) > 29_970_000) throw new IllegalArgumentException("Keep the map inside world bounds.");
            if (tiles(minX, minZ, maxX, maxZ, step * 32).size() > 512) throw new IllegalArgumentException("Too many map tiles.");
            if (!List.of("any", "with_elytra", "without_elytra").contains(cityFilter)) throw new IllegalArgumentException("Invalid End city filter.");
        }
        boolean showStructures() { return structuresAllowed && !target.isEmpty(); }
        boolean overviewStructures() { return (long)(Math.floorDiv(maxX,1024) - Math.floorDiv(minX,1024) + 1) * (Math.floorDiv(maxZ,1024) - Math.floorDiv(minZ,1024) + 1) > 256; }

    }
    record TilePos(int x, int z) { }
    record BiomeKey(long seed, String dimension, int y, int step, TilePos position, boolean relief) { }
    record StructureKey(long seed, String dimension, String target, String cityFilter, Integer paperSalt, TilePos position) { }
    record Tile(int x, int z, List<Integer> runs, List<Integer> heights) {
        Tile(int x, int z, List<Integer> runs) { this(x, z, runs, List.of()); }
    }
    record HeightKey(long seed, int x, int z) { }
    private static final class SearchProgressHolder { boolean partial; }
    record Swatch(String name, int color) { }
    private static final Gson JSON = new Gson();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(task -> { var thread = new Thread(task, "Seedy map"); thread.setDaemon(true); thread.setPriority(Thread.MIN_PRIORITY); return thread; });
    private final Map<BiomeKey, Tile> biomeCache = boundedCache(1024);
    private final Map<StructureKey, List<Locator.Result>> structureCache = boundedCache(256);
    private final Map<String, List<Swatch>> palettes = new LinkedHashMap<>();
    private final Map<HeightKey, Integer> heightCache = boundedCache(16384);
    private Locator locator;
    private Future<?> pending;
    private long generation;
    private long revision;
    private volatile JsonObject snapshot = new JsonObject();

    private static <K,V> Map<K,V> boundedCache(int limit) {
        return new LinkedHashMap<>(limit, 0.75f, true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> entry) { return size() > limit; }
        };
    }

    static List<TilePos> tiles(int minX, int minZ, int maxX, int maxZ, int span) {
        int left = Math.floorDiv(minX, span), right = Math.floorDiv(maxX, span), top = Math.floorDiv(minZ, span), bottom = Math.floorDiv(maxZ, span);
        if ((long)(right - left + 1) * (bottom - top + 1) > 512) throw new IllegalArgumentException("Too many map tiles.");
        var tiles = new ArrayList<TilePos>();
        for (int z = top; z <= bottom; z++) for (int x = left; x <= right; x++) tiles.add(new TilePos(x, z));
        double centerX = (left + right) / 2.0, centerZ = (top + bottom) / 2.0;
        tiles.sort(Comparator.comparingDouble(tile -> Math.hypot(tile.x() - centerX, tile.z() - centerZ)));
        return tiles;
    }

    static Tile sample(Locator.BiomeSampler sampler, List<Swatch> palette, TilePos position, int y, int step) {
        var indices = new LinkedHashMap<String, Integer>();
        for (int i = 0; i < palette.size(); i++) indices.put(palette.get(i).name(), i);
        var runs = new ArrayList<Integer>();
        int previous = -1, count = 0;
        for (int z = 0; z < 32; z++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            for (int x = 0; x < 32; x++) {
                int value = indices.get(sampler.at((position.x() * 32 + x) * step + step / 2, y, (position.z() * 32 + z) * step + step / 2));
                if (value != previous && count > 0) { runs.add(count); runs.add(previous); count = 0; }
                previous = value;
                count++;
            }
        }
        if (count > 0) { runs.add(count); runs.add(previous); }
        return new Tile(position.x(), position.z(), List.copyOf(runs));
    }

    public synchronized void request(Request request) {
        stop();
        long token = generation;
        pending = worker.submit(() -> generate(request, token));
    }

    public synchronized void stop() {
        generation++;
        if (pending != null) pending.cancel(true);
    }

    public synchronized void reset() { stop(); snapshot = new JsonObject(); }
    public JsonObject snapshot() { return snapshot; }

    private void generate(Request request, long token) {
        var completed = new ArrayList<Tile>();
        var markers = new ArrayList<Locator.Result>();
        int remaining = 0;
        try {
            if (locator == null) locator = new Locator();
            var palette = palettes.computeIfAbsent(request.dimension(), dimension -> locator.catalog(dimension).biomes().stream().map(name -> new Swatch(name, color(name))).toList());
            if (!request.target().isEmpty() && !locator.catalog(request.dimension()).structures().contains(request.target())) throw new IllegalArgumentException("Choose a structure in this dimension.");
            if (!request.cityFilter().equals("any") && !request.target().equals("minecraft:end_cities")) throw new IllegalArgumentException("The ship filter applies to End cities.");
            var positions = tiles(request.minX(), request.minZ(), request.maxX(), request.maxZ(), request.step() * 32);
            var structurePositions = request.showStructures() && !request.overviewStructures() ? tiles(request.minX(), request.minZ(), request.maxX(), request.maxZ(), 1024) : List.<TilePos>of();
            var missing = new ArrayList<BiomeKey>();
            var missingStructures = new ArrayList<StructureKey>();
            for (var position : positions) {
                var key = new BiomeKey(request.seed(), request.dimension(), request.y(), request.step(), position, request.relief());
                var tile = biomeCache.get(key);
                if (tile == null) missing.add(key); else completed.add(tile);
            }
            for (var position : structurePositions) {
                var key = new StructureKey(request.seed(), request.dimension(), request.target(), request.cityFilter(), request.paperSalt(), position);
                var found = structureCache.get(key);
                if (found == null) missingStructures.add(key); else markers.addAll(found);
            }
            boolean shade = request.relief() && request.dimension().equals("overworld") && request.y() >= 48;
            int tileOffset = completed.size();
            remaining = missing.size() * (shade ? 2 : 1) + missingStructures.size() + (request.showStructures() && request.overviewStructures() ? 1 : 0);
            publish(request, token, palette, completed, markers, remaining, "");
            var sampler = locator.mapBiomes(request.seed(), request.dimension());
            long published = System.nanoTime();
            for (var key : missing) {
                check(token);
                var baseKey = new BiomeKey(request.seed(), request.dimension(), request.y(), request.step(), key.position(), false);
                var tile = biomeCache.get(baseKey);
                if (tile == null) { tile = sample(sampler, palette, key.position(), request.y(), request.step()); biomeCache.put(baseKey, tile); }
                if (!shade) biomeCache.put(key, tile);
                completed.add(tile);
                remaining--;
                if (System.nanoTime() - published > 150_000_000 || remaining == 0) { publish(request, token, palette, completed, markers, remaining, ""); published = System.nanoTime(); }
            }
            publish(request, token, palette, completed, markers, remaining, "");
            for (var key : missingStructures) {
                check(token);
                var found = locator.mapStructures(request.seed(), request.target(), key.position().x(), key.position().z(), request.dimension(), request.cityFilter(), request.paperSalt());
                structureCache.put(key, found);
                markers.addAll(found);
                publish(request, token, palette, completed, markers, --remaining, "");
            }
            String structureWarning = "";
            if (request.showStructures() && request.overviewStructures()) {
                check(token);
                int centerX = request.minX() + (request.maxX() - request.minX()) / 2;
                int centerZ = request.minZ() + (request.maxZ() - request.minZ()) / 2;
                int radius = Math.clamp((int)Math.ceil(Math.hypot(request.maxX() - centerX, request.maxZ() - centerZ)), 128, Locator.MAX_RADIUS);
                String saltTarget = request.target().equals("minecraft:fortress") || request.target().equals("minecraft:bastion_remnant") ? "minecraft:nether_complexes" : request.target();
                SearchProgressHolder progress = new SearchProgressHolder();
                var found = locator.search(request.seed(), "structure", request.target(), centerX, request.y(), centerZ, radius, LootQuery.NONE, request.dimension(), request.cityFilter(), request.paperSalt() == null ? Map.of() : Map.of(saltTarget, request.paperSalt()), value -> progress.partial = value.partial());
                markers.addAll(found.stream().filter(result -> result.x() >= request.minX() && result.x() <= request.maxX() && result.z() >= request.minZ() && result.z() <= request.maxZ()).toList());
                structureWarning = progress.partial ? "Structure scan reached its work limit. Zoom in to continue." : "";
                publish(request, token, palette, completed, markers, --remaining, structureWarning);
            }
            if (shade) for (int index = 0; index < missing.size(); index++) {
                var key = missing.get(index);
                var heights = new ArrayList<Integer>(49);
                for (int row = -1; row <= 5; row++) for (int col = -1; col <= 5; col++) {
                    check(token);
                    int x = (key.position().x() * 32 + col * 8) * request.step();
                    int z = (key.position().z() * 32 + row * 8) * request.step();
                    heights.add(heightCache.computeIfAbsent(new HeightKey(request.seed(), x, z), ignored -> locator.mapHeight(request.seed(), x, z)));
                }
                var tile = completed.get(tileOffset + index);
                tile = new Tile(tile.x(), tile.z(), tile.runs(), List.copyOf(heights));
                completed.set(tileOffset + index, tile); biomeCache.put(key, tile); remaining--;
                if (System.nanoTime() - published > 150_000_000 || remaining == 0) { publish(request, token, palette, completed, markers, remaining, structureWarning); published = System.nanoTime(); }
            }

        } catch (CancellationException ignored) {
        } catch (Throwable error) {
            org.slf4j.LoggerFactory.getLogger("Seedy").error("Seed map generation failed", error);
            publish(request, token, palettes.getOrDefault(request.dimension(), List.of()), completed, markers, 0, error.getMessage() == null ? "Map generation failed." : error.getMessage());
            if (error instanceof VirtualMachineError fatal) throw fatal;
        }
    }

    private synchronized void check(long token) { if (token != generation || Thread.currentThread().isInterrupted()) throw new CancellationException(); }

    private void publish(Request request, long token, List<Swatch> palette, List<Tile> tiles, List<Locator.Result> markers, int remaining, String error) {
        synchronized (this) { if (token != generation || Thread.currentThread().isInterrupted()) return; }
        var next = JSON.toJsonTree(request).getAsJsonObject();
        next.addProperty("seed", Long.toString(request.seed()));
        next.addProperty("remaining", remaining);
        next.addProperty("structuresVisible", request.showStructures());
        next.addProperty("overviewStructures", request.overviewStructures());
        next.addProperty("error", error);
        next.add("palette", JSON.toJsonTree(palette));
        next.add("tiles", JSON.toJsonTree(tiles));
        next.add("markers", JSON.toJsonTree(markers));
        synchronized (this) {
            if (token != generation || Thread.currentThread().isInterrupted()) return;
            next.addProperty("revision", ++revision);
            snapshot = next;
        }
    }

    static int color(String biome) {
        return switch (biome.replace("minecraft:", "")) {
            case "ocean" -> 0x39758b;
            case "deep_ocean" -> 0x28536e;
            case "cold_ocean" -> 0x4c8399;
            case "deep_cold_ocean" -> 0x355b7b;
            case "frozen_ocean" -> 0x9fbdc4;
            case "deep_frozen_ocean" -> 0x6f96ad;
            case "lukewarm_ocean" -> 0x419797;
            case "deep_lukewarm_ocean" -> 0x327883;
            case "warm_ocean" -> 0x61b8b0;
            case "river" -> 0x64a3bc;
            case "frozen_river" -> 0xb4d2d6;
            case "plains" -> 0xa0b773;
            case "sunflower_plains" -> 0xb9c67d;
            case "forest" -> 0x668b57;
            case "flower_forest" -> 0x8faa7e;
            case "birch_forest" -> 0x9da877;
            case "old_growth_birch_forest" -> 0x84976d;
            case "dark_forest" -> 0x455e4d;
            case "pale_garden" -> 0x9aa59a;
            case "dappled_forest" -> 0x899d64;
            case "taiga" -> 0x6d9286;
            case "old_growth_pine_taiga" -> 0x66836a;
            case "old_growth_spruce_taiga" -> 0x527d71;
            case "snowy_taiga" -> 0xabc2b4;
            case "jungle" -> 0x477e4b;
            case "sparse_jungle" -> 0x7b9d59;
            case "bamboo_jungle" -> 0x709445;
            case "swamp" -> 0x7c8470;
            case "mangrove_swamp" -> 0x647760;
            case "desert" -> 0xd6bd85;
            case "beach" -> 0xe4d4a7;
            case "snowy_beach" -> 0xd1d5c1;
            case "stony_shore" -> 0x929b9b;
            case "badlands" -> 0xbc7959;
            case "eroded_badlands" -> 0xd49a72;
            case "wooded_badlands" -> 0x957555;
            case "savanna" -> 0xb6ae67;
            case "savanna_plateau" -> 0xa39761;
            case "windswept_savanna" -> 0x938e64;
            case "meadow" -> 0x9bba91;
            case "cherry_grove" -> 0xd5acbe;
            case "grove" -> 0xafc4bd;
            case "snowy_plains" -> 0xd9e3df;
            case "ice_spikes" -> 0xb8d2db;
            case "snowy_slopes" -> 0xc5d1d2;
            case "frozen_peaks" -> 0xb2c6d2;
            case "jagged_peaks" -> 0xd2d6dc;
            case "stony_peaks" -> 0xa6a392;
            case "windswept_hills" -> 0x899b8d;
            case "windswept_gravelly_hills" -> 0x9da39d;
            case "windswept_forest" -> 0x71866e;
            case "mushroom_fields" -> 0xad8fa5;
            case "dripstone_caves" -> 0x987e66;
            case "lush_caves" -> 0x7ca67a;
            case "sulfur_caves" -> 0xb8a25b;
            case "deep_dark" -> 0x344a59;
            case "nether_wastes" -> 0x985d55;
            case "crimson_forest" -> 0xa34862;
            case "warped_forest" -> 0x428f8c;
            case "soul_sand_valley" -> 0x8c756b;
            case "basalt_deltas" -> 0x5d626e;
            case "the_end" -> 0xc8c098;
            case "end_highlands" -> 0xd6cea6;
            case "end_midlands" -> 0xaca58e;
            case "small_end_islands" -> 0x777589;
            case "end_barrens" -> 0x58596d;
            default -> 0x777c85;
        };
    }

    @Override public synchronized void close() { stop(); worker.submit(() -> { if (locator != null) locator.close(); biomeCache.clear(); structureCache.clear(); heightCache.clear(); }); worker.shutdown(); }
}
