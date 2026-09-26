package dev.seedy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.seedy.recovery.Observation;
import dev.seedy.recovery.PlacementRule;
import dev.seedy.recovery.RecoveryEngine;
import dev.seedy.recovery.SeedHash;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class Workbench implements AutoCloseable {
    public static final Workbench INSTANCE = new Workbench();
    private static final Gson JSON = new Gson();
    private final RecoveryEngine recovery = new RecoveryEngine();
    private final LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
    private final LinkedHashMap<String, SessionStore.SavedLocation> savedLocations = new LinkedHashMap<>();
    private SessionStore store;
    private UiSettings uiStore;
    private JsonObject uiSettings = UiSettings.normalize(new JsonObject());
    private String gameVersion;
    private String storageStatus = "Join a world to save progress";
    private boolean dirty;
    private boolean storageWritable = true;
    private final ExecutorService searches = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory());
    private final AtomicLong searchGeneration = new AtomicLong();
    private final SeedMap seedMap = new SeedMap();
    private boolean mapVisible;
    private Future<?> search;
    private Locator locator;
    private Map<String, PlacementRule> rules = Map.of();
    private Locator.Catalog catalog;
    private Map<String, Locator.Catalog> dimensionCatalogs = Map.of();
    private String searchDimension = "overworld";
    private String searchCityFilter = "any";
    private volatile List<Locator.Result> results = List.of();
    private com.google.gson.JsonArray resultSummaries = new com.google.gson.JsonArray();
    private int chestDetails = -1;
    private volatile String searchStatus = "Idle";
    private String searchTarget = "";
    private String searchKind = "";
    private volatile String error = "";
    private volatile String snapshot = "{}";
    private Long hash;
    private String seed;
    private boolean verified;
    private boolean connected;
    private boolean collecting = true;
    private long session;
    private long revision;
    private long attemptedRevision = -1;
    private int ticks;

    public void initialize() {
        gameVersion = net.minecraft.SharedConstants.getCurrentVersion().name();
        store = new SessionStore(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("seedy/sessions"), gameVersion);
        uiStore = new UiSettings(net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("seedy/ui.json"));
        try { uiSettings = uiStore.load(); }
        catch (java.io.IOException error) { org.slf4j.LoggerFactory.getLogger("Seedy").warn("Using default window settings", error); }
        locator = new Locator();
        catalog = locator.catalog();
        dimensionCatalogs = java.util.stream.Stream.of("overworld", "the_nether", "the_end").collect(java.util.stream.Collectors.toUnmodifiableMap(dimension -> dimension, locator::catalog));
        var registry = WorldgenAdapter.registries();
        var loaded = new LinkedHashMap<String, PlacementRule>();
        registry.lookupOrThrow(Registries.STRUCTURE_SET).listElements().forEach(holder -> {
            if (!(holder.value().placement() instanceof RandomSpreadStructurePlacement placement)) return;
            String id = holder.key().identifier().toString();
            var rule = PlacementRules.read(id, placement);
            if (rule.canLift()) loaded.put(id, rule);
        });
        rules = Map.copyOf(loaded);
        publish(Minecraft.getInstance());
    }

    public boolean collecting() { return collecting; }
    public String snapshot() { return snapshot; }
    public void refresh() { publish(Minecraft.getInstance()); }
    public void stopMap() { mapVisible = false; seedMap.stop(); }

    public void session(Long seedHash) {
        saveSession();
        session++;
        seedMap.reset();
        connected = seedHash != null;
        hash = seedHash;
        seed = null;
        verified = false;
        observations.clear();
        savedLocations.clear();
        dirty = false;
        storageWritable = true;
        error = "";
        storageStatus = seedHash == null ? "Join a world to save progress" : "Progress saves locally";
        revision++;
        attemptedRevision = -1;
        recovery.cancel();
        cancelSearch();
        if (seedHash != null && store != null) {
            try {
                var saved = store.load(seedHash);
                if (saved != null) {
                    for (var entry : saved.observations()) if (entry != null && rules.containsKey(entry.structure())) observe(entry.structure(), entry.chunkX(), entry.chunkZ(), entry.source() == null ? "Saved" : entry.source());
                    for (var location : saved.locations()) if (location != null && location.id() != null && location.seed() != null && location.name() != null && location.dimension() != null && Math.abs((long)location.x()) <= 30_000_000 && Math.abs((long)location.z()) <= 30_000_000) savedLocations.put(location.id(), location);
                    seed = saved.seed();
                    verified = saved.verified() && seed != null && SeedHash.hash(Long.parseLong(seed)) == seedHash;
                    collecting = saved.collecting();
                    dirty = false;
                    storageStatus = "Restored saved progress";
                }
            } catch (Exception e) { observations.clear(); savedLocations.clear(); seed = null; verified = false; dirty = false; storageWritable = false; storageStatus = "Saved file could not be loaded"; error = "Progress could not be restored. The saved file has been left unchanged."; }
        }
        publish(Minecraft.getInstance());
    }

    public void observe(String kind, int chunkX, int chunkZ, String source) {
        if (observations.size() >= 512) return;
        if (Math.abs((long)chunkX) > 1_875_000 || Math.abs((long)chunkZ) > 1_875_000) throw new IllegalArgumentException("Use a start chunk inside the world border.");
        var rule = rules.get(kind);
        if (rule == null) throw new IllegalArgumentException("This structure is not supported by the recovery engine.");
        Observation observation;
        try { observation = Observation.create(rule, chunkX, chunkZ, source); }
        catch (IllegalArgumentException e) { if (source.equals("Detected")) return; throw e; }
        if (observations.containsKey(observation.id())) return;
        boolean sameRegion = observations.values().stream().anyMatch(o -> o.structure().equals(kind) && Math.floorDiv(o.chunkX(), rule.spacing()) == Math.floorDiv(chunkX, rule.spacing()) && Math.floorDiv(o.chunkZ(), rule.spacing()) == Math.floorDiv(chunkZ, rule.spacing()));
        if (sameRegion) return;
        observations.put(observation.id(), observation);
        dirty = true;
        revision++;
        recovery.cancel();
    }

    public void tick(Minecraft client) {
        if (catalog == null) return;
        if (collecting && hash != null && !verified && !recovery.running() && revision != attemptedRevision && observations.size() >= 5 && RecoveryEngine.evidenceBits(List.copyOf(observations.values())) >= 40) startRecovery();
        if (++ticks % 5 == 0) publish(client);
        if (ticks % 100 == 0) saveSession();
    }

    private void startRecovery() {
        if (hash == null) throw new IllegalArgumentException("Join a world to receive its seed hash.");
        attemptedRevision = revision;
        long world = session;
        recovery.start(List.copyOf(observations.values()), hash, value -> Minecraft.getInstance().execute(() -> {
            if (session != world) return;
            seed = Long.toString(value);
            seedMap.reset();
            verified = true;
            dirty = true;
            cancelSearch();
        }));
    }

    public void action(String message) {
        try {
            var body = JSON.fromJson(message, JsonObject.class);
            String action = body.get("action").getAsString();
            if (body.has("stopMap") && body.get("stopMap").getAsBoolean()) stopMap();
            error = "";
            switch (action) {
                case "preferences" -> { }
                case "close" -> Minecraft.getInstance().gui.setScreen(null);
                case "collect" -> { collecting = !collecting; if (collecting) SeedyClient.rescan(); }
                case "outlines" -> { LoadedObjects.treasureEnabled = body.get("treasure").getAsBoolean(); LoadedObjects.spawnersEnabled = body.get("spawners").getAsBoolean(); if (body.has("loot")) LoadedObjects.lootEnabled = body.get("loot").getAsBoolean(); }
                case "rescan" -> { SeedyClient.rescan(); collecting = true; }
                case "recover" -> startRecovery();
                case "stopRecovery" -> { recovery.cancel(); attemptedRevision = revision; }
                case "seed" -> {
                    try { seed = Long.toString(Long.parseLong(body.get("seed").getAsString().trim())); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a whole number from -9223372036854775808 to 9223372036854775807."); }
                    verified = false;
                    seedMap.reset();
                    cancelSearch();
                }
                case "clearSeed" -> { seed = null; verified = false; seedMap.reset(); cancelSearch(); }
                case "map" -> {
                    if (seed == null) throw new IllegalArgumentException("Recover or enter a seed first.");
                    seedMap.request(new SeedMap.Request(body.get("id").getAsLong(), Long.parseLong(seed), body.get("dimension").getAsString(), body.get("y").getAsInt(), body.get("step").getAsInt(), body.get("minX").getAsInt(), body.get("minZ").getAsInt(), body.get("maxX").getAsInt(), body.get("maxZ").getAsInt(), body.get("target").getAsString(), body.get("cityFilter").getAsString()));
                    mapVisible = true;
                }
                case "mapStop" -> stopMap();
                case "observe" -> observe(body.get("structure").getAsString(), body.get("x").getAsInt(), body.get("z").getAsInt(), "Manual");
                case "remove" -> { observations.remove(body.get("id").getAsString()); revision++; recovery.cancel(); }
                case "locate" -> locate(body);
                case "cancelSearch" -> cancelSearch();
                case "chests" -> {
                    int index = body.get("index").getAsInt();
                    if (body.get("generation").getAsLong() != searchGeneration.get() || index < 0 || index >= results.size()) throw new IllegalArgumentException("Search results changed. Select the structure again.");
                    chestDetails = index;
                }
                case "closeChests" -> chestDetails = -1;
                case "pin" -> pin(body.get("index").getAsInt(), body.get("generation").getAsLong());
                case "unpin" -> savedLocations.remove(body.get("id").getAsString());
                case "copy" -> Minecraft.getInstance().keyboardHandler.setClipboard(body.get("text").getAsString());
                default -> throw new IllegalArgumentException("Unknown action");
            }
            if (java.util.Set.of("seed", "clearSeed", "collect", "rescan", "observe", "remove", "pin", "unpin").contains(action)) dirty = true;
            if (body.has("preferences") && uiStore != null) uiSettings = uiStore.save(body.getAsJsonObject("preferences"));
        } catch (Exception e) { error = e.getMessage() == null ? "Invalid input" : e.getMessage(); }
        publish(Minecraft.getInstance());
    }

    private void locate(JsonObject body) {
        if (seed == null) throw new IllegalArgumentException("Recover or enter a seed first.");
        long value = Long.parseLong(seed);
        String kind = body.get("kind").getAsString(), target = body.get("target").getAsString();
        int x = body.get("x").getAsInt(), y = body.get("y").getAsInt(), z = body.get("z").getAsInt(), radius = body.get("radius").getAsInt();
        var requirements = new java.util.ArrayList<LootQuery.Requirement>();
        if (body.has("lootRequirements")) for (var entry : body.getAsJsonArray("lootRequirements")) {
            var rule = entry.getAsJsonObject();
            requirements.add(new LootQuery.Requirement(rule.get("item").getAsString(), rule.get("minimum").getAsInt()));
        }
        String dimension = body.has("dimension") ? body.get("dimension").getAsString() : "overworld";
        String cityFilter = body.has("cityFilter") ? body.get("cityFilter").getAsString() : "any";
        var query = new LootQuery(body.has("lootEnabled") && body.get("lootEnabled").getAsBoolean(), requirements);
        cancelSearch();
        long generation = searchGeneration.get();
        searchTarget = target;
        searchKind = kind;
        searchDimension = dimension;
        searchCityFilter = cityFilter;
        searchStatus = "Searching";
        search = searches.submit(() -> {
            try {
                var found = locator.search(value, kind, target, x, y, z, radius, query, dimension, cityFilter);
                Minecraft.getInstance().execute(() -> {
                    if (searchGeneration.get() != generation) return;
                    results = found;
                    resultSummaries = new com.google.gson.JsonArray();
                    for (var result : found) {
                        var summary = JSON.toJsonTree(new Locator.Result(result.name(), result.x(), result.y(), result.z(), result.distance(), result.confidence(), result.dimension(), null, result.elytra())).getAsJsonObject();
                        if (result.loot() != null) {
                            var lootSummary = new JsonObject();
                            lootSummary.add("totals", JSON.toJsonTree(result.loot().totals()));
                            lootSummary.addProperty("chestCount", result.loot().chests().size());
                            lootSummary.addProperty("uncertainCount", result.loot().chests().stream().filter(chest -> !chest.warning().isEmpty()).count());
                            summary.add("loot", lootSummary);
                        }
                        resultSummaries.add(summary);
                    }
                    LoadedObjects.predictions(found);
                    searchStatus = "Complete";
                });
            } catch (Exception e) {
                Minecraft.getInstance().execute(() -> {
                    if (searchGeneration.get() != generation) return;
                    error = e.getMessage() == null ? "Search failed" : e.getMessage();
                    searchStatus = "Failed";
                });
            }
        });
    }

    private void cancelSearch() {
        searchGeneration.incrementAndGet();
        if (search != null) search.cancel(true);
        results = List.of();
        resultSummaries = new com.google.gson.JsonArray();
        chestDetails = -1;
        LoadedObjects.predictions(List.of());
        searchStatus = "Idle";
    }

    private void pin(int index, long generation) {
        if (hash == null || seed == null) throw new IllegalArgumentException("Join a world and set its seed before saving locations.");
        if (generation != searchGeneration.get() || index < 0 || index >= results.size()) throw new IllegalArgumentException("Search results changed. Select the location again.");
        var result = results.get(index);
        String id = seed + ":" + result.dimension() + ":" + result.name() + ":" + result.x() + ":" + result.y() + ":" + result.z();
        if (!savedLocations.containsKey(id) && savedLocations.size() >= 256) throw new IllegalArgumentException("Remove a saved location before adding more.");
        savedLocations.put(id, new SessionStore.SavedLocation(id, seed, result.name(), result.x(), result.y(), result.z(), result.dimension(), result.confidence()));
    }

    private void saveSession() {
        if (!dirty || hash == null || store == null || !storageWritable) return;
        try {
            var evidence = observations.values().stream().map(o -> new SessionStore.Evidence(o.structure(), o.chunkX(), o.chunkZ(), o.source())).toList();
            store.save(new SessionStore.Session(1, gameVersion, hash, seed, verified, collecting, evidence, List.copyOf(savedLocations.values())));
            dirty = false;
            storageStatus = "Saved locally";
        } catch (Exception e) { storageStatus = "Could not save progress"; }
    }

    private void publish(Minecraft client) {
        var state = new JsonObject();
        state.addProperty("seed", seed == null ? "" : seed);
        state.add("uiSettings", uiSettings);
        state.addProperty("verified", verified);
        state.addProperty("connected", connected);
        state.addProperty("collecting", collecting);
        state.addProperty("recovering", recovery.running());
        state.addProperty("recoveryStatus", recovery.stage());
        state.addProperty("progress", recovery.progress());
        state.addProperty("evidence", RecoveryEngine.evidenceBits(List.copyOf(observations.values())));
        state.addProperty("searchStatus", searchStatus);
        state.addProperty("searchTarget", searchTarget);
        state.addProperty("searchKind", searchKind);
        state.addProperty("searchDimension", searchDimension);
        state.addProperty("searchCityFilter", searchCityFilter);
        state.add("catalogs", JSON.toJsonTree(dimensionCatalogs));
        if (mapVisible) state.add("map", seedMap.snapshot());
        state.addProperty("error", error);
        state.addProperty("storageStatus", dirty && storageWritable ? "Unsaved changes" : storageStatus);
        state.addProperty("pendingChunks", SeedyClient.pendingChunks());
        state.addProperty("treasureOutlines", LoadedObjects.treasureEnabled);
        state.addProperty("spawnerOutlines", LoadedObjects.spawnersEnabled);
        state.addProperty("lootOutlines", LoadedObjects.lootEnabled);
        state.add("loadedObjects", JSON.toJsonTree(LoadedObjects.snapshot()));
        state.add("lootItems", JSON.toJsonTree(LootCatalog.items()));
        state.addProperty("searchGeneration", searchGeneration.get());
        state.addProperty("dimension", client.level == null ? "overworld" : client.level.dimension().identifier().getPath());
        state.addProperty("gameVersion", gameVersion);
        state.addProperty("x", client.player == null ? 0 : client.player.getBlockX());
        state.addProperty("y", client.player == null ? 64 : client.player.getBlockY());
        state.addProperty("z", client.player == null ? 0 : client.player.getBlockZ());
        state.add("observations", JSON.toJsonTree(observations.values()));
        state.add("results", resultSummaries);
        state.addProperty("chestDetailIndex", chestDetails);
        if (chestDetails >= 0 && chestDetails < results.size()) state.add("chestDetails", JSON.toJsonTree(results.get(chestDetails)));
        state.add("savedLocations", JSON.toJsonTree(savedLocations.values().stream().filter(location -> location.seed().equals(seed)).toList()));
        state.add("structures", JSON.toJsonTree(catalog == null ? List.of() : catalog.structures()));
        state.add("biomes", JSON.toJsonTree(catalog == null ? List.of() : catalog.biomes()));
        state.add("recoveryTypes", JSON.toJsonTree(rules.keySet().stream().sorted().toList()));
        snapshot = JSON.toJson(state);
    }

    @Override public void close() { saveSession(); recovery.close(); seedMap.close(); cancelSearch(); if (locator != null) searches.submit(locator::close); searches.shutdown(); }
}
