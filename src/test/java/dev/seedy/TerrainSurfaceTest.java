package dev.seedy;

import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainSurfaceTest {
    @org.junit.jupiter.api.BeforeAll static void bootstrap() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }

    @Test void savedTerrainReadsBothBlockStateFormatsWithLeafProperties() {
        for (boolean modern : new boolean[]{false,true}) {
            var tag = new net.minecraft.nbt.CompoundTag(); var properties = new net.minecraft.nbt.CompoundTag();
            tag.putString(modern ? "id" : "Name","minecraft:birch_leaves"); properties.putString("distance","3");
            tag.put(modern ? "properties" : "Properties",properties);
            var state = SavedTerrainProbe.blockState(tag);
            assertTrue(state.is(net.minecraft.world.level.block.Blocks.BIRCH_LEAVES));
            assertEquals(3,state.getValue(net.minecraft.world.level.block.LeavesBlock.DISTANCE));
        }
    }

    private static TerrainSurface flat(TerrainPalette.Material material, int tint) {
        var surface = new TerrainSurface();
        Arrays.fill(surface.materials,material); Arrays.fill(surface.tints,tint); Arrays.fill(surface.heights,70); Arrays.fill(surface.ground,0x8b9e73);
        return surface;
    }

    @Test void textureSamplingPreservesDetailAndIgnoresTransparentPixelColors() {
        int[] detail = TerrainPalette.downsample(4,4,(x,z) -> 0xff000000 | (x + z * 4 + 20) * 0x010101);
        assertEquals(0xff141414,detail[0]); assertEquals(0xff232323,detail[15]);
        int[] alpha = TerrainPalette.downsample(16,16,(x,z) -> x % 2 == 0 ? 0xffff0000 : 0x000000ff);
        assertEquals(0x7fff0000,alpha[0]);
        var count = new AtomicInteger();
        TerrainPalette.downsample(512,512,(x,z) -> { count.incrementAndGet(); return 0xff808080; });
        assertEquals(256,count.get());
    }

    @Test void terrainPacketsHaveFourTextureSamplesPerBlockAndRetainSharpPixels() {
        int[] detail = TerrainPalette.downsample(4,4,(x,z) -> 0xff000000 | (x + z * 4 + 30) * 0x010101);
        var surface = flat(new TerrainPalette.Material(detail,-1,true,0),0xffffff);
        byte[] pixels = surface.raster();
        assertEquals(64 * 64 * 3,pixels.length);
        assertNotEquals(pixels[0],pixels[(3 * 64 + 3) * 3]); assertEquals(pixels[0],pixels[12]);
        var packet = surface.encode(-3,8);
        assertEquals(-3,packet.get("x").getAsInt()); assertEquals(64,packet.get("resolution").getAsInt());
        assertArrayEquals(pixels,Base64.getDecoder().decode(packet.get("pixels").getAsString()));
        assertEquals(16384,packet.get("pixels").getAsString().length());
    }

    @Test void waterDepthAndHeightChangesCreateDistinctShading() {
        var water = new TerrainPalette.Material(TerrainPalette.solid(0x989898,0).texels(),0,true,TerrainPalette.WATER);
        var shallow = flat(water,0x3f76e4); Arrays.fill(shallow.depths,1);
        var deep = flat(water,0x3f76e4); Arrays.fill(deep.depths,24);
        assertFalse(Arrays.equals(shallow.raster(),deep.raster()));
        var terrain = flat(TerrainPalette.solid(0x808080,0),0xffffff);
        byte[] before = terrain.raster();
        terrain.heights[8 * TerrainSurface.SIDE + 8] += 12;
        assertFalse(Arrays.equals(before,terrain.raster()));
    }

    @Test void denseCanopiesDoNotTurnTransparentTexelsIntoGroundColoredHoles() {
        int[] texture = new int[16]; Arrays.fill(texture,0xffaaaaaa); texture[5] = 0;
        var surface = flat(new TerrainPalette.Material(texture,0,true,TerrainPalette.FOLIAGE),0x77a84e);
        byte[] pixels = surface.raster();
        int opaque = (4 * 64 + 4) * 3, transparent = (5 * 64 + 5) * 3;
        assertTrue(Math.abs((pixels[opaque + 1] & 255) - (pixels[transparent + 1] & 255)) < 12);
        var ground = flat(TerrainPalette.solid(0xaaaaaa,0),0xffffff);
        assertFalse(Arrays.equals(pixels,ground.raster()));
    }

    @Test void neighboringWaterTilesKeepTheirDepthGradientAcrossChunkEdges() {
        byte[][] tiles = new byte[2][];
        for (int chunk = 0; chunk < 2; ++chunk) {
            var surface = flat(new TerrainPalette.Material(TerrainPalette.solid(0x999999,0).texels(),0,true,TerrainPalette.WATER),0x3f76e4);
            for (int z = 0; z < TerrainSurface.SIDE; ++z) for (int x = 0; x < TerrainSurface.SIDE; ++x)
                surface.depths[z * TerrainSurface.SIDE + x] = Math.clamp((chunk * 16 + x - TerrainSurface.BORDER) / 2,1,24);
            tiles[chunk] = surface.raster();
        }
        for (int channel = 0; channel < 3; ++channel)
            assertTrue(Math.abs((tiles[0][(32 * 64 + 63) * 3 + channel] & 255) - (tiles[1][32 * 64 * 3 + channel] & 255)) <= 2);
        assertNotEquals(tiles[0][32 * 64 * 3 + 1],tiles[1][(32 * 64 + 63) * 3 + 1]);
    }
}
