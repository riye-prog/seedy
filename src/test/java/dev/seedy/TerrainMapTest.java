package dev.seedy;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainMapTest {
    @Test void disconnectResetsTheTerrainStream() {
        try (var map = new TerrainMap()) {
            map.reset();
            var first = com.google.gson.JsonParser.parseString(map.poll()).getAsJsonObject();
            assertEquals("",first.get("dimension").getAsString());
            map.reset();
            var second = com.google.gson.JsonParser.parseString(map.poll()).getAsJsonObject();
            assertTrue(second.get("reset").getAsLong() > first.get("reset").getAsLong());
            assertNull(map.poll());
        }
    }

    @Test void terrainEncodingPreservesAllColumnsAndNegativeChunkCoordinates() {
        int[] colors = new int[256], heights = new int[256];
        for (int i = 0; i < colors.length; ++i) colors[i] = 0x306030 + i;
        Arrays.fill(heights,64);
        var tile = TerrainMap.encode(-9,-4,colors,heights);
        assertEquals(-9,tile.get("x").getAsInt());
        assertEquals(-4,tile.get("z").getAsInt());
        var runs = tile.getAsJsonArray("runs");
        int index = 0;
        for (int i = 0; i < runs.size(); i += 2) for (int count = runs.get(i).getAsInt(); count > 0; --count) assertEquals(colors[index++],runs.get(i + 1).getAsInt());
        assertEquals(256,index);
        assertThrows(IllegalArgumentException.class,() -> TerrainMap.encode(0,0,new int[255],heights));
    }

    @Test void terrainReliefClampsSteepSlopesAndCompressesFlatChunks() {
        int[] colors = new int[256], heights = new int[256]; Arrays.fill(colors,0xffffff);
        var flat = TerrainMap.encode(0,0,colors,heights).getAsJsonArray("runs");
        assertEquals(2,flat.size()); assertEquals(256,flat.get(0).getAsInt());
        heights[16] = 100; heights[17] = -100;
        var shaded = TerrainMap.encode(0,0,colors,heights).getAsJsonArray("runs");
        int index = 0;
        for (int i = 0; i < shaded.size(); i += 2) for (int count = shaded.get(i).getAsInt(); count > 0; --count, ++index) {
            int value = shaded.get(i + 1).getAsInt();
            assertTrue(value >= 0 && value <= 0xffffff);
            if (index == 16) assertEquals(0xb7b7b7,value);
            if (index == 17) assertEquals(0xffffff,value);
        }
    }
}
