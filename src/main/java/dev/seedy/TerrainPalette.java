package dev.seedy;

import dev.seedy.mixin.SpriteContentsAccess;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.function.IntBinaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

final class TerrainPalette {
    static final int WATER = 1, FOLIAGE = 2, EMISSIVE = 4, TRANSLUCENT = 8;
    record Material(int[] texels, int tintIndex, boolean top, int flags) {
        Material {
            if (texels.length != 16) throw new IllegalArgumentException("Terrain materials need sixteen texels.");
            for (int pixel : texels) if ((pixel >>> 24) < 250) { flags |= TRANSLUCENT; break; }
        }
    }
    static final Material VOID = solid(0x151c23,0);
    private final IdentityHashMap<BlockState,Material> materials = new IdentityHashMap<>();
    private final IdentityHashMap<Object,int[]> textures = new IdentityHashMap<>();
    private final RandomSource random = RandomSource.create(0);
    private final ArrayList<BlockStateModelPart> parts = new ArrayList<>();
    private Object modelSet;

    boolean refresh(Minecraft client) {
        var current = client.getModelManager().getBlockStateModelSet();
        if (modelSet == current) return false;
        modelSet = current; materials.clear(); textures.clear(); return true;
    }

    Material get(Minecraft client, BlockState state) {
        var cached = materials.get(state);
        if (cached != null) return cached;
        if (materials.size() > 4096) materials.clear();
        var baked = bake(client,state); materials.put(state,baked); return baked;
    }

    int tint(Minecraft client, ClientLevel level, BlockState state, BlockPos position, Material material) {
        if ((material.flags() & WATER) != 0) return client.getModelManager().getFluidStateModelSet().get(state.getFluidState()).tintSource().colorInWorld(state,level,position) & 0xffffff;
        if (material.tintIndex() < 0) return 0xffffff;
        var source = client.getBlockColors().getTintSource(state,material.tintIndex());
        return source == null ? 0xffffff : source.colorInWorld(state,level,position) & 0xffffff;
    }

    private Material bake(Minecraft client, BlockState state) {
        int flags = state.getBlock() instanceof LeavesBlock ? FOLIAGE : 0;
        if (state.getLightEmission() >= 12) flags |= EMISSIVE;
        if (!state.getFluidState().isEmpty() && (state.getBlock() instanceof net.minecraft.world.level.block.LiquidBlock || state.is(net.minecraft.world.level.block.Blocks.BUBBLE_COLUMN))) {
            var model = client.getModelManager().getFluidStateModelSet().get(state.getFluidState());
            boolean water = state.getFluidState().is(FluidTags.WATER);
            return new Material(texture(model.stillMaterial().sprite().contents()),water ? 0 : -1,true,flags | (water ? WATER : EMISSIVE));
        }
        var model = client.getModelManager().getBlockStateModelSet().get(state);
        parts.clear(); random.setSeed(0); model.collectParts(random,parts);
        BakedQuad chosen = null;
        float highest = -Float.MAX_VALUE;
        for (var part : parts) for (var face : new Direction[]{Direction.UP,null}) for (var quad : part.getQuads(face)) if (quad.direction() == Direction.UP) {
            float height = (quad.position0().y() + quad.position1().y() + quad.position2().y() + quad.position3().y()) * 0.25f;
            if (height > highest) { chosen = quad; highest = height; }
        }
        var sprite = chosen == null ? model.particleMaterial().sprite() : chosen.materialInfo().sprite();
        int tintIndex = chosen == null ? client.getBlockColors().getTintSources(state).isEmpty() ? -1 : 0 : chosen.materialInfo().isTinted() ? chosen.materialInfo().tintIndex() : -1;
        return new Material(texture(sprite.contents()),tintIndex,chosen != null,flags);
    }

    private int[] texture(net.minecraft.client.renderer.texture.SpriteContents contents) {
        return textures.computeIfAbsent(contents,key -> {
            var image = ((SpriteContentsAccess)contents).seedyImage();
            return downsample(contents.width(),contents.height(),image::getPixel);
        });
    }

    static int[] downsample(int width, int height, IntBinaryOperator pixel) {
        var output = new int[16];
        for (int z = 0; z < 4; ++z) for (int x = 0; x < 4; ++x) {
            int x0 = x * width / 4, z0 = z * height / 4;
            int x1 = Math.max(x0 + 1,(x + 1) * width / 4), z1 = Math.max(z0 + 1,(z + 1) * height / 4);
            long alpha = 0, red = 0, green = 0, blue = 0;
            int samplesX = Math.min(4,x1 - x0), samplesZ = Math.min(4,z1 - z0);
            for (int y = 0; y < samplesZ; ++y) for (int col = 0; col < samplesX; ++col) {
                int argb = pixel.applyAsInt(x0 + (2 * col + 1) * (x1 - x0) / (2 * samplesX),z0 + (2 * y + 1) * (z1 - z0) / (2 * samplesZ)), a = argb >>> 24;
                alpha += a; red += (argb >> 16 & 255) * a; green += (argb >> 8 & 255) * a; blue += (argb & 255) * a;
            }
            if (alpha > 0) output[z * 4 + x] = ((int)(alpha / (samplesX * samplesZ)) << 24) | ((int)(red / alpha) << 16) | ((int)(green / alpha) << 8) | (int)(blue / alpha);
        }
        return output;
    }

    static Material solid(int rgb, int flags) {
        int[] pixels = new int[16]; Arrays.fill(pixels,0xff000000 | rgb); return new Material(pixels,-1,true,flags);
    }
}
