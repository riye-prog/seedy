package dev.seedy;

import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SeedMapTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void tilesCoverNegativeCoordinatesAndRejectUnboundedWork() {
        var tiles = SeedMap.tiles(-129,-1,127,128,128);
        assertEquals(9,tiles.size());
        assertTrue(tiles.contains(new SeedMap.TilePos(-2,-1)));
        assertTrue(tiles.contains(new SeedMap.TilePos(0,1)));
        assertThrows(IllegalArgumentException.class, () -> new SeedMap.Request(1,1,"overworld",64,4,-20000,-20000,20000,20000,"","any"));
        assertThrows(IllegalArgumentException.class, () -> new SeedMap.Request(1,1,"overworld",64,3,0,0,127,127,"","any"));
        assertThrows(IllegalArgumentException.class, () -> new SeedMap.Request(1,1,"overworld",64,4,Integer.MIN_VALUE,0,0,0,"","any"));
        assertFalse(new SeedMap.Request(1,1,"overworld",64,64,-4096,-4096,4096,4096,"minecraft:ancient_cities","any").showStructures());
        assertDoesNotThrow(() -> new SeedMap.Request(1,1,"overworld",64,4,0,0,2047,2047,"","any"));
        assertThrows(IllegalArgumentException.class,() -> new SeedMap.Request(1,1,"overworld",64,4,0,0,2048,2047,"","any"));
    }

    @Test void runEncodingPreservesCellsAtNegativeTileBoundaries() {
        var palette = List.of(new SeedMap.Swatch("left",0),new SeedMap.Swatch("right",1));
        var positions = new ArrayList<Integer>();
        var tile = SeedMap.sample((x,y,z) -> { assertEquals(-40,y); positions.add(x); return x < -64 ? "left" : "right"; },palette,new SeedMap.TilePos(-1,-1),-40,4);
        assertEquals(-126,positions.getFirst());
        assertEquals(-2,positions.getLast());
        var decoded = new ArrayList<Integer>();
        for (int i = 0; i < tile.runs().size(); i += 2) for (int j = 0; j < tile.runs().get(i); j++) decoded.add(tile.runs().get(i + 1));
        assertEquals(1024,decoded.size());
        for (int i = 0; i < 1024; i++) assertEquals(i % 32 < 16 ? 0 : 1, decoded.get(i));
    }

    @Test void asyncTilesMatchVanillaAcrossSeedsHeightsAndDimensions() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            try (var map = new SeedMap(); var locator = new Locator()) {
                long id = 0;
                for (String dimension : List.of("overworld","the_nether","the_end")) for (long seed : new long[]{2589511696370800371L,37}) {
                    int y = seed == 37 ? -40 : 64;
                    var request = new SeedMap.Request(++id,seed,dimension,y,4,-128,-128,-1,-1,"","any");
                    long start = System.nanoTime();
                    map.request(request);
                    var snapshot = await(map,id);
                    assertEquals(Long.toString(seed),snapshot.get("seed").getAsString());
                    var palette = snapshot.getAsJsonArray("palette");
                    for (var swatch : palette) assertNotEquals(0x777c85,swatch.getAsJsonObject().get("color").getAsInt(),swatch.getAsJsonObject().get("name").getAsString());
                    var runs = snapshot.getAsJsonArray("tiles").get(0).getAsJsonObject().getAsJsonArray("runs");
                    var sampler = locator.mapBiomes(seed,dimension);
                    int index = 0;
                    for (int i = 0; i < runs.size(); i += 2) {
                        String biome = palette.get(runs.get(i + 1).getAsInt()).getAsJsonObject().get("name").getAsString();
                        for (int count = 0; count < runs.get(i).getAsInt(); count++, index++) assertEquals(sampler.at(-128 + index % 32 * 4 + 2,y,-128 + index / 32 * 4 + 2),biome);
                    }
                    assertEquals(1024,index);
                    System.out.println("Map tile " + dimension + " ms=" + (System.nanoTime() - start) / 1_000_000);
                    map.request(new SeedMap.Request(++id,seed,dimension,y,4,-128,-128,-1,-1,"","any"));
                    assertEquals(snapshot.get("tiles"),await(map,id).get("tiles"));
                }
                map.request(new SeedMap.Request(++id,1,"overworld",64,16,-1500,-1500,1500,1500,"minecraft:ancient_cities","any"));
                map.reset();
                assertEquals(new JsonObject(),map.snapshot());
                map.request(new SeedMap.Request(++id,37,"the_end",64,4,0,0,127,127,"","any"));
                assertEquals("the_end",await(map,id).get("dimension").getAsString());
                assertEquals("37",map.snapshot().get("seed").getAsString());
            }
        });
    }

    @Test void structureTilesPartitionActualEndCitiesAndPreserveShipFilter() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            long seed = 2589511696370800371L;
            try (var locator = new Locator(); var map = new SeedMap()) {
                var results = locator.search(seed,"structure","minecraft:end_cities",3584,64,3584,1536,LootQuery.NONE,"the_end","any");
                var expected = results.stream().filter(result -> result.x() >= 3072 && result.x() < 4096 && result.z() >= 3072 && result.z() < 4096).toList();
                assertFalse(expected.isEmpty());
                var actual = locator.mapStructures(seed,"minecraft:end_cities",3,3,"the_end","any");
                assertEquals(expected,actual);
                map.request(new SeedMap.Request(1,seed,"the_end",64,16,3072,3072,4095,4095,"minecraft:end_cities","with_elytra"));
                var snapshot = await(map,1);
                var markers = snapshot.getAsJsonArray("markers");
                assertEquals(expected.stream().filter(result -> result.elytra() != null).count(),markers.size());
                for (var marker : markers) assertTrue(marker.getAsJsonObject().has("elytra"));
            }
        });
    }

    @Test void viewportReusesCachedTilesAndProducesBoundedPayload() {
        assertTimeoutPreemptively(Duration.ofSeconds(90), () -> {
            try (var map = new SeedMap()) {
                long start = System.nanoTime();
                map.request(new SeedMap.Request(1,2589511696370800371L,"overworld",64,32,-2048,-2048,2047,2047,"minecraft:villages","any"));
                var snapshot = await(map,1);
                long cold = (System.nanoTime() - start) / 1_000_000;
                assertEquals(16,snapshot.getAsJsonArray("tiles").size());
                assertFalse(snapshot.getAsJsonArray("markers").isEmpty());
                assertTrue(snapshot.toString().length() < 250_000);
                start = System.nanoTime();
                map.request(new SeedMap.Request(2,2589511696370800371L,"overworld",64,32,-2048,-2048,2047,2047,"minecraft:villages","any"));
                var cached = await(map,2);
                assertEquals(snapshot.get("tiles"),cached.get("tiles"));
                assertEquals(snapshot.get("markers"),cached.get("markers"));
                System.out.println("Map viewport coldMs=" + cold + " cachedMs=" + (System.nanoTime() - start) / 1_000_000 + " payloadBytes=" + cached.toString().length() + " markers=" + cached.getAsJsonArray("markers").size());
                java.nio.file.Files.createDirectories(java.nio.file.Path.of("build"));
                java.nio.file.Files.writeString(java.nio.file.Path.of("build/seed-map-preview.json"),cached.toString());
                start = System.nanoTime();
                map.request(new SeedMap.Request(3,2589511696370800371L,"overworld",64,8,-2048,-2048,2047,2047,"minecraft:villages","any"));
                var detailed = await(map,3);
                assertEquals(256,detailed.getAsJsonArray("tiles").size());
                assertEquals(cached.get("markers"),detailed.get("markers"));
                assertTrue(detailed.toString().length() < 1_000_000);
                System.out.println("Detailed map ms=" + (System.nanoTime() - start) / 1_000_000 + " payloadBytes=" + detailed.toString().length());
                java.nio.file.Files.writeString(java.nio.file.Path.of("build/seed-map-detailed-preview.json"),detailed.toString());
            }
        });
    }

    @Test void villageStartMatchesFullVanillaGeneration() {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var world = WorldgenAdapter.structureWorld(registries,seed,"overworld");
        var set = registries.lookupOrThrow(net.minecraft.core.registries.Registries.STRUCTURE_SET).listElements().filter(holder -> holder.key().identifier().toString().equals("minecraft:villages")).findFirst().orElseThrow().value();
        var placement = (net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)set.placement();
        try (var structures = new VanillaStructures((net.minecraft.core.RegistryAccess)registries)) {
            int found = 0;
            for (int x = -1; x <= 0; x++) for (int z = -1; z <= 0; z++) {
                var position = placement.getPotentialStructureChunk(seed,x * placement.spacing(),z * placement.spacing());
                var point = structures.villageStart(set,world,seed,position);
                var full = structures.generate(set,world,seed,position);
                assertEquals(full != null,point != null);
                if (point == null) continue;
                found++;
                assertEquals(full.name(),point.name());
                var box = full.start().getPieces().getFirst().getBoundingBox();
                assertTrue(point.position().getX() >= box.minX() && point.position().getX() <= box.maxX());
                assertTrue(point.position().getZ() >= box.minZ() && point.position().getZ() <= box.maxZ());
            }
            assertTrue(found > 0);
        }
    }

    private static JsonObject await(SeedMap map, long id) throws InterruptedException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            var snapshot = map.snapshot();
            if (snapshot.has("id") && snapshot.get("id").getAsLong() == id && snapshot.get("remaining").getAsInt() == 0) {
                assertEquals("",snapshot.get("error").getAsString());
                return snapshot;
            }
            Thread.sleep(10);
        }
        fail("Map worker did not finish");
        return null;
    }
}
