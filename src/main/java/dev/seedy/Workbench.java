package dev.seedy;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import dev.seedy.recovery.Observation;
import dev.seedy.recovery.PlacementRule;
import dev.seedy.recovery.PaperSaltRecovery;
import dev.seedy.recovery.RecoveryEngine;
import dev.seedy.recovery.SeedHash;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

public final class Workbench implements AutoCloseable {
    public static final Workbench INSTANCE = new Workbench();
    private static final Gson JSON = new Gson();
    private final RecoveryEngine recovery = new RecoveryEngine();
    private final PaperSaltRecovery paperRecovery = new PaperSaltRecovery();
    private final LinkedHashMap<String, Observation> observations = new LinkedHashMap<>();
    private final Set<String> excludedObservations = new HashSet<>();
    private final LinkedHashMap<String, Integer> paperSalts = new LinkedHashMap<>();
    private final LinkedHashMap<String, SessionStore.SavedLocation> savedLocations = new LinkedHashMap<>();
    private SessionStore store;
    private UiSettings uiStore;
    private JsonObject uiSettings = UiSettings.normalize(new JsonObject());
    private String gameVersion;
    private String generationVersion;
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
    private volatile boolean verified;
    private boolean connected;
    private boolean collecting = true;
    private long session;
    private long revision;
    private int ticks;

    public void initialize() {
        gameVersion = net.minecraft.SharedConstants.getCurrentVersion().name();
        generationVersion = gameVersion;
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
            if (rule.bound() > 1) loaded.put(id, rule);
        });
        rules = Map.copyOf(loaded);
        publish(Minecraft.getInstance());
    }

    public boolean collecting() { return collecting; }
    public boolean hasVerifiedSeed() { return verified; }
    public boolean structureRecoveryRunning() { return recovery.running(); }
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
        excludedObservations.clear();
        paperSalts.clear();
        generationVersion = gameVersion;
        savedLocations.clear();
        dirty = false;
        storageWritable = true;
        error = "";
        storageStatus = seedHash == null ? "Join a world to save progress" : "Progress saves locally";
        revision++;
        recovery.cancel();
        paperRecovery.cancel();
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
                    if (saved.generationVersion() != null) generationVersion = saved.generationVersion();
                    if (saved.excludedObservations() != null) excludedObservations.addAll(saved.excludedObservations());
                    if (verified && saved.paperSalts() != null) saved.paperSalts().forEach((kind, salt) -> { if (rules.containsKey(kind) && salt != null) paperSalts.put(kind, salt); });
                    dirty = false;
                    storageStatus = "Restored saved progress";
                }
            } catch (Exception e) { observations.clear(); excludedObservations.clear(); paperSalts.clear(); savedLocations.clear(); seed = null; verified = false; dirty = false; storageWritable = false; storageStatus = "Saved file could not be loaded"; error = "Progress could not be restored. The saved file has been left unchanged."; }
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
        paperRecovery.cancel();
    }

    public void tick(Minecraft client) {
        if (catalog == null) return;
        if (++ticks % 5 == 0) publish(client);
        if (ticks % 100 == 0) saveSession();
    }

    private void startRecovery() {
        if (hash == null) throw new IllegalArgumentException("Join a world to receive its seed hash.");
        if (!generationVersion.equals(gameVersion)) throw new IllegalArgumentException("Use the matching 26.2 or 26.3 Seedy jar to recover this world's seed from structures.");
        if (verified) throw new IllegalArgumentException("The world seed is already verified.");
        var selected = structureEvidence();
        if (selected.size() < 5 || RecoveryEngine.evidenceBits(selected) < 40) throw new IllegalArgumentException("Select at least five independent structures with 40 evidence bits.");
        long world = session;
        long evidenceRevision = revision;
        recovery.start(selected, hash, value -> Minecraft.getInstance().execute(() -> {
            if (session != world || revision != evidenceRevision) return;
            seed = Long.toString(value);
            seedMap.reset();
            verified = true;
            dirty = true;
            cancelSearch();
        }));
    }

    private List<Observation> selectedObservations() {
        return observations.values().stream().filter(observation -> !excludedObservations.contains(observation.id())).toList();
    }

    private List<Observation> structureEvidence() {
        return selectedObservations().stream().filter(observation -> observation.rule().canLift() && !paperSalts.containsKey(observation.structure())).toList();
    }

    public boolean acceptBlockSeed(long value) {
        if (hash == null || SeedHash.hash(value) != hash) return false;
        seed = Long.toString(value);
        verified = true;
        dirty = true;
        seedMap.reset();
        recovery.cancel();
        cancelSearch();
        publish(Minecraft.getInstance());
        return true;
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
                case "outlines" -> LoadedObjects.lootEnabled = body.get("loot").getAsBoolean();
                case "rescan" -> { SeedyClient.rescan(); collecting = true; }
                case "tryBlockRecovery" -> BedrockRecovery.INSTANCE.tryCurrentData();
                case "stopBlockRecovery" -> BedrockRecovery.INSTANCE.stopSearch();
                case "tryNetherRecovery" -> NetherBedrockRecovery.INSTANCE.tryCurrentData();
                case "stopNetherRecovery" -> NetherBedrockRecovery.INSTANCE.stopSearch();
                case "recover" -> startRecovery();
                case "stopRecovery" -> { recovery.cancel(); revision++; }
                case "recoverPaperSalt" -> recoverPaperSalt(body.get("structure").getAsString());
                case "stopPaperSalt" -> { paperRecovery.cancel(); revision++; }
                case "selectObservation" -> {
                    String id = body.get("id").getAsString();
                    if (!observations.containsKey(id)) throw new IllegalArgumentException("That observation is no longer available.");
                    if (body.get("selected").getAsBoolean()) excludedObservations.remove(id);
                    else excludedObservations.add(id);
                    recovery.cancel();
                    paperRecovery.cancel();
                    revision++;
                }
                case "generationVersion" -> {
                    String version = body.get("version").getAsString();
                    if (!List.of("26.2", "26.3").contains(version)) throw new IllegalArgumentException("Choose a supported generation version.");
                    if (!version.equals(generationVersion)) {
                        generationVersion = version;
                        recovery.cancel();
                        paperRecovery.cancel();
                        cancelSearch();
                        paperSalts.clear();
                        revision++;
                    }
                }
                case "seed" -> {
                    try { seed = Long.toString(Long.parseLong(body.get("seed").getAsString().trim())); }
                    catch (NumberFormatException e) { throw new IllegalArgumentException("Enter a whole number from -9223372036854775808 to 9223372036854775807."); }
                    verified = hash != null && SeedHash.hash(Long.parseLong(seed)) == hash;
                    paperSalts.clear();
                    paperRecovery.cancel();
                    revision++;
                    seedMap.reset();
                    cancelSearch();
                }
                case "clearSeed" -> { seed = null; verified = false; paperSalts.clear(); paperRecovery.cancel(); revision++; seedMap.reset(); cancelSearch(); }
                case "map" -> {
                    if (seed == null) throw new IllegalArgumentException("Recover or enter a seed first.");
                    if (!generationVersion.equals(gameVersion)) throw new IllegalArgumentException("Use the matching 26.2 or 26.3 Seedy jar for the map in this world.");
                    String target = body.get("target").getAsString();
                    Integer placementSalt = null;
                    String warning = "";
                    if (!target.isEmpty()) try { placementSalt = checkedPlacementSalt(target); }
                    catch (IllegalArgumentException conflict) { warning = conflict.getMessage(); }
                    seedMap.request(new SeedMap.Request(body.get("id").getAsLong(), Long.parseLong(seed), body.get("dimension").getAsString(), body.get("y").getAsInt(), body.get("step").getAsInt(), body.get("minX").getAsInt(), body.get("minZ").getAsInt(), body.get("maxX").getAsInt(), body.get("maxZ").getAsInt(), target, body.get("cityFilter").getAsString(), placementSalt, warning.isEmpty(), warning));
                    mapVisible = true;
                }
                case "mapStop" -> stopMap();
                case "observe" -> observe(body.get("structure").getAsString(), body.get("x").getAsInt(), body.get("z").getAsInt(), "Manual");
                case "remove" -> { String id = body.get("id").getAsString(); observations.remove(id); excludedObservations.remove(id); revision++; recovery.cancel(); paperRecovery.cancel(); }
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
            if (java.util.Set.of("seed", "clearSeed", "collect", "rescan", "observe", "remove", "selectObservation", "generationVersion", "pin", "unpin").contains(action)) dirty = true;
            if (body.has("preferences") && uiStore != null) uiSettings = uiStore.save(body.getAsJsonObject("preferences"));
        } catch (Exception e) { error = e.getMessage() == null ? "Invalid input" : e.getMessage(); }
        publish(Minecraft.getInstance());
    }

    private void locate(JsonObject body) {
        if (seed == null) throw new IllegalArgumentException("Recover or enter a seed first.");
        if (!generationVersion.equals(gameVersion)) throw new IllegalArgumentException("Use the matching 26.2 or 26.3 Seedy jar for location searches in this world.");
        long value = Long.parseLong(seed);
        String kind = body.get("kind").getAsString(), target = body.get("target").getAsString();
        if (kind.equals("structure")) checkedPlacementSalt(target);
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
        var activeSalts = Map.copyOf(paperSalts);
        search = searches.submit(() -> {
            try {
                var found = locator.search(value, kind, target, x, y, z, radius, query, dimension, cityFilter, activeSalts);
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

    private void recoverPaperSalt(String kind) {
        if (seed == null || !verified) throw new IllegalArgumentException("Verify the world seed before checking a Paper placement salt.");
        if (!generationVersion.equals(gameVersion)) throw new IllegalArgumentException("Use the matching 26.2 or 26.3 Seedy jar to check Paper placement.");
        var rule = rules.get(kind);
        if (rule == null) throw new IllegalArgumentException("Choose a supported random-spread structure.");
        var selected = selectedObservations().stream().filter(observation -> observation.structure().equals(kind)).toList();
        long world = session;
        long evidenceRevision = revision;
        long value = Long.parseLong(seed);
        paperRecovery.start(value, rule, selected, salt -> Minecraft.getInstance().execute(() -> {
            if (session != world || revision != evidenceRevision || seed == null || Long.parseLong(seed) != value) return;
            paperSalts.put(kind, salt);
            dirty = true;
            cancelSearch();
            seedMap.reset();
        }));
    }

    private Integer checkedPlacementSalt(String target) {
        String kind = target.equals("minecraft:fortress") || target.equals("minecraft:bastion_remnant") ? "minecraft:nether_complexes" : target;
        Integer recovered = paperSalts.get(kind);
        if (!verified || seed == null) return recovered;
        var rule = rules.get(kind);
        if (rule == null) return recovered;
        long value = Long.parseLong(seed);
        if (recovered != null) {
            if (selectedObservations().stream().anyMatch(observation -> observation.structure().equals(kind) && !rule.matchesWithSalt(value, recovered, observation.chunkX(), observation.chunkZ()))) throw new IllegalArgumentException("An observed " + kind.replace("minecraft:", "") + " start conflicts with the recovered Paper salt. Check the observation or generation version.");
            return recovered;
        }
        if (selectedObservations().stream().anyMatch(observation -> observation.structure().equals(kind) && !rule.matchesWithSalt(value, rule.salt(), observation.chunkX(), observation.chunkZ()))) throw new IllegalArgumentException("Selected " + kind.replace("minecraft:", "") + " placement conflicts with the default salt. Recover its Paper salt or deselect the incorrect observation before locating.");
        return null;
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
            store.save(new SessionStore.Session(1, gameVersion, hash, seed, verified, collecting, evidence, List.copyOf(savedLocations.values()), generationVersion, List.copyOf(excludedObservations), Map.copyOf(paperSalts)));
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
        state.addProperty("evidence", RecoveryEngine.evidenceBits(structureEvidence()));
        state.addProperty("selectedObservations", selectedObservations().size());
        state.addProperty("structureEvidenceCount", structureEvidence().size());
        state.addProperty("generationVersion", generationVersion);
        state.addProperty("paperRunning", paperRecovery.running());
        state.addProperty("paperStatus", paperRecovery.status());
        state.addProperty("paperProgress", paperRecovery.progress());
        state.add("paperSalts", JSON.toJsonTree(paperSalts));
        state.add("paperTypes", JSON.toJsonTree(selectedObservations().stream().map(Observation::structure).distinct().sorted().toList()));
        var placementChecks = new com.google.gson.JsonArray();
        if (verified && seed != null) {
            long worldSeed = Long.parseLong(seed);
            for (var kind : selectedObservations().stream().map(Observation::structure).distinct().sorted().toList()) {
                var rule = rules.get(kind);
                int matched = 0, changed = 0;
                for (var observation : selectedObservations()) if (observation.structure().equals(kind)) {
                    if (rule.matchesWithSalt(worldSeed, rule.salt(), observation.chunkX(), observation.chunkZ())) matched++;
                    else changed++;
                }
                var check = new JsonObject();
                check.addProperty("structure", kind);
                check.addProperty("defaultMatches", matched);
                check.addProperty("defaultConflicts", changed);
                if (paperSalts.containsKey(kind)) {
                    int recovered = paperSalts.get(kind);
                    check.addProperty("recoveredSalt", recovered);
                    check.addProperty("recoveredConflicts", selectedObservations().stream().filter(observation -> observation.structure().equals(kind) && !rule.matchesWithSalt(worldSeed, recovered, observation.chunkX(), observation.chunkZ())).count());
                }
                placementChecks.add(check);
            }
        }
        state.add("placementChecks", placementChecks);
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
        var blockProgress = BedrockRecovery.INSTANCE.searchProgress();
        state.addProperty("patternStatus", BedrockRecovery.INSTANCE.status());
        state.addProperty("patternChunks", BedrockRecovery.INSTANCE.scannedChunks());
        state.addProperty("patternEvidenceChunks", BedrockRecovery.INSTANCE.evidenceChunks());
        state.addProperty("patternRawBlocks", BedrockRecovery.INSTANCE.rawBlocks());
        state.addProperty("patternOreCandidates", BedrockRecovery.INSTANCE.oreCandidates());
        state.addProperty("patternRawChunks", BedrockRecovery.INSTANCE.rawChunks());
        state.addProperty("patternCollectionProgress", BedrockRecovery.INSTANCE.collectionProgress());
        state.addProperty("patternRunning", BedrockRecovery.INSTANCE.running());
        state.addProperty("patternCanTry", BedrockRecovery.INSTANCE.canTry());
        state.addProperty("patternPass", blockProgress.pass());
        state.addProperty("patternPassCount", blockProgress.passCount());
        state.addProperty("patternPassSample", blockProgress.sample());
        state.addProperty("patternStage", blockProgress.stage());
        state.addProperty("patternStageSeconds", blockProgress.stageSeconds());
        state.addProperty("patternRejectedStates", blockProgress.rejectedStates());
        state.addProperty("patternPreviousPass", blockProgress.previousPass());
        state.addProperty("patternProgress", blockProgress.fraction());
        state.addProperty("patternCheckedCandidates", blockProgress.checkedCandidates());
        state.addProperty("patternTotalCandidates", blockProgress.totalCandidates());
        state.addProperty("patternElapsedSeconds", blockProgress.elapsedSeconds());
        state.addProperty("patternEtaSeconds", blockProgress.etaSeconds());
        state.addProperty("netherPatternStatus", NetherBedrockRecovery.INSTANCE.status());
        state.addProperty("netherPatternChunks", NetherBedrockRecovery.INSTANCE.scannedChunks());
        state.addProperty("netherPatternFloor", NetherBedrockRecovery.INSTANCE.floorBlocks());
        state.addProperty("netherPatternRoof", NetherBedrockRecovery.INSTANCE.roofBlocks());
        state.addProperty("netherPatternRunning", NetherBedrockRecovery.INSTANCE.running());
        state.addProperty("netherPatternCanTry", NetherBedrockRecovery.INSTANCE.canTry());
        state.addProperty("netherPatternProgress", NetherBedrockRecovery.INSTANCE.progress());
        state.addProperty("netherPatternElapsedSeconds", NetherBedrockRecovery.INSTANCE.elapsedSeconds());
        state.addProperty("lootOutlines", LoadedObjects.lootEnabled);
        state.add("loadedObjects", JSON.toJsonTree(LoadedObjects.snapshot()));
        state.add("lootItems", JSON.toJsonTree(LootCatalog.items()));
        state.addProperty("searchGeneration", searchGeneration.get());
        state.addProperty("dimension", client.level == null ? "overworld" : client.level.dimension().identifier().getPath());
        state.addProperty("gameVersion", gameVersion);
        var server = client.getCurrentServer();
        state.addProperty("serverVersion", server == null ? client.hasSingleplayerServer() ? gameVersion + " (local)" : "Not reported" : server.version == null ? "Not reported" : server.version.getString());
        state.addProperty("x", client.player == null ? 0 : client.player.getBlockX());
        state.addProperty("y", client.player == null ? 64 : client.player.getBlockY());
        state.addProperty("z", client.player == null ? 0 : client.player.getBlockZ());
        var observationState = new com.google.gson.JsonArray();
        for (var observation : observations.values()) {
            var entry = JSON.toJsonTree(observation).getAsJsonObject();
            entry.addProperty("selected", !excludedObservations.contains(observation.id()));
            observationState.add(entry);
        }
        state.add("observations", observationState);
        state.add("results", resultSummaries);
        state.addProperty("chestDetailIndex", chestDetails);
        if (chestDetails >= 0 && chestDetails < results.size()) state.add("chestDetails", JSON.toJsonTree(results.get(chestDetails)));
        state.add("savedLocations", JSON.toJsonTree(savedLocations.values().stream().filter(location -> location.seed().equals(seed)).toList()));
        state.add("structures", JSON.toJsonTree(catalog == null ? List.of() : catalog.structures()));
        state.add("biomes", JSON.toJsonTree(catalog == null ? List.of() : catalog.biomes()));
        state.add("recoveryTypes", JSON.toJsonTree(rules.keySet().stream().sorted().toList()));
        snapshot = JSON.toJson(state);
    }

    @Override public void close() { saveSession(); recovery.close(); paperRecovery.close(); seedMap.close(); cancelSearch(); if (locator != null) searches.submit(locator::close); searches.shutdown(); }
}
