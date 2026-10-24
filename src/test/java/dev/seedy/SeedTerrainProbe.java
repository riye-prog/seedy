package dev.seedy;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;

public final class SeedTerrainProbe {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); prepareVanillaTags(); SeedMapTest.initializeTerrainColors();
        long seed = Long.parseLong(args[0]); String dimension = args[1]; int step = Integer.parseInt(args[2]);
        long started = System.nanoTime();
        try (var map = new SeedMap()) {
            map.request(new SeedMap.Request(1,seed,dimension,64,step,Integer.parseInt(args[3]),Integer.parseInt(args[4]),Integer.parseInt(args[5]),Integer.parseInt(args[6]),"","any",null,true,"",true,true));
            long deadline = System.nanoTime() + 240_000_000_000L;
            while (System.nanoTime() < deadline) {
                while (map.poll() != null) { }
                var result = map.snapshot();
                if (result.has("id") && result.get("remaining").getAsInt() == 0) {
                    if (!result.get("error").getAsString().isEmpty()) throw new IllegalStateException(result.get("error").getAsString());
                    int centerX = Math.floorDiv(Integer.parseInt(args[3]) + Integer.parseInt(args[5]),2), centerZ = Math.floorDiv(Integer.parseInt(args[4]) + Integer.parseInt(args[6]),2);
                    int span = step * 32;
                    for (var value : result.getAsJsonArray("tiles")) {
                        var tile = value.getAsJsonObject();
                        if (tile.get("x").getAsInt() != Math.floorDiv(centerX,span) || tile.get("z").getAsInt() != Math.floorDiv(centerZ,span)) continue;
                        int index = Math.floorMod(centerZ,span) / step * 32 + Math.floorMod(centerX,span) / step, count = 0;
                        var runs = tile.getAsJsonArray("runs");
                        for (int i = 0; i < runs.size(); i += 2) {
                            count += runs.get(i).getAsInt();
                            if (count > index) { result.addProperty("biome",result.getAsJsonArray("palette").get(runs.get(i + 1).getAsInt()).getAsJsonObject().get("name").getAsString()); break; }
                        }
                    }
                    Files.writeString(Path.of(args[7]),result.toString());
                    var stats = new JsonObject(); stats.addProperty("seed",Long.toString(seed)); stats.addProperty("dimension",dimension); stats.addProperty("tiles",result.getAsJsonArray("tiles").size());
                    stats.addProperty("elapsedMs",(System.nanoTime() - started) / 1_000_000); stats.addProperty("payloadBytes",result.toString().length()); stats.addProperty("scope","seed-only generation; no client chunks, server, or Minecraft instance");
                    System.out.println(stats);
                    if (args.length > 8) try (var locator = new Locator(); var terrain = new SeedTerrain(locator.mapWorld(seed,dimension),seed,WorldgenAdapter.registries(),locator.mapBiomes(seed,dimension))) {
                        var compared = SavedTerrainProbe.compareSurface(Path.of(args[8]),seed,terrain);
                        Files.writeString(Path.of(args[7] + ".comparison.json"),compared.toString()); System.out.println(compared);
                        var decorated = SavedTerrainProbe.compareDecoration(Path.of(args[8]),seed,terrain,centerX,centerZ,2);
                        Files.writeString(Path.of(args[7] + ".decoration-comparison.json"),decorated.toString()); System.out.println(decorated);
                    }
                    return;
                }
                Thread.sleep(10);
            }
            throw new IllegalStateException("Seed terrain generation exceeded the probe deadline");
        }
    }

    static void prepareVanillaTags() {
        var registries = WorldgenAdapter.registries();
        var access = new net.minecraft.core.RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(net.minecraft.core.RegistryAccess.fromRegistryOfRegistries(net.minecraft.core.registries.BuiltInRegistries.REGISTRY).registries(),((net.minecraft.core.RegistryAccess)registries).registries()));
        try (var resources = WorldgenAdapter.resources()) {
            net.minecraft.tags.TagLoader.loadTagsForExistingRegistries(resources,access).forEach(net.minecraft.core.Registry.PendingTags::apply);
            net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.build(access).forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        }
    }
}
