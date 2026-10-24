package dev.seedy;

import com.google.gson.JsonObject;
import java.util.Base64;

final class TerrainSurface {
    static final int BORDER = 2, SIDE = 16 + BORDER * 2, COLUMNS = SIDE * SIDE;
    final TerrainPalette.Material[] materials = new TerrainPalette.Material[COLUMNS];
    final int[] tints = new int[COLUMNS], heights = new int[COLUMNS], depths = new int[COLUMNS], ground = new int[COLUMNS];

    private int height(int x, int z, int fallback) {
        int index = Math.clamp(z,0,SIDE - 1) * SIDE + Math.clamp(x,0,SIDE - 1);
        return materials[index] == null ? fallback : heights[index];
    }

    private boolean foliage(int x, int z) {
        var material = materials[Math.clamp(z,0,SIDE - 1) * SIDE + Math.clamp(x,0,SIDE - 1)];
        return material != null && (material.flags() & TerrainPalette.FOLIAGE) != 0;
    }

    private double slopeHeight(int x, int z, int elevation, boolean canopy) {
        return Math.clamp(height(x,z,elevation) - elevation,canopy ? -2 : -5,canopy ? 2 : 5);
    }

    private double canopyCoverage(int x, int z, int col, int row) {
        int sideX = col < 2 ? -1 : 1, sideZ = row < 2 ? -1 : 1;
        double blendX = Math.abs((col + 0.5) / 4 - 0.5), blendZ = Math.abs((row + 0.5) / 4 - 0.5);
        double upper = 1 - blendX + (foliage(x + sideX,z) ? blendX : 0);
        double lower = (foliage(x,z + sideZ) ? 1 - blendX : 0) + (foliage(x + sideX,z + sideZ) ? blendX : 0);
        double coverage = Math.clamp((upper * (1 - blendZ) + lower * blendZ - 0.45) / 0.30,0,1);
        return coverage * coverage * (3 - 2 * coverage);
    }

    private double lighting(int x, int z) {
        int index = z * SIDE + x, elevation = heights[index];
        var material = materials[index];
        if (material == null || (material.flags() & TerrainPalette.WATER) != 0) return 1;
        if ((material.flags() & TerrainPalette.EMISSIVE) != 0) return 1.08;
        boolean canopy = foliage(x,z);
        double dx = 0, dz = 0;
        for (int offset = -1; offset <= 1; ++offset) {
            int weight = offset == 0 ? 2 : 1;
            dx += weight * (slopeHeight(x + 1,z + offset,elevation,canopy) - slopeHeight(x - 1,z + offset,elevation,canopy)) / 8;
            dz += weight * (slopeHeight(x + offset,z + 1,elevation,canopy) - slopeHeight(x + offset,z - 1,elevation,canopy)) / 8;
        }
        if (canopy) { dx *= 1.4; dz *= 1.4; }
        double diffuse = (2.6 - dx * 0.48 - dz * 0.38) / Math.sqrt(dx * dx + dz * dz + 7.25);
        double light = canopy ? 0.83 + 0.22 * diffuse : 0.88 + 0.16 * diffuse;
        return !canopy && (foliage(x - 1,z) || foliage(x,z - 1)) ? light * 0.94 : light;
    }

    private double neighborValue(double[] values, int x, int z, int center) {
        int index = z * SIDE + x;
        var neighbor = materials[index]; var material = materials[center];
        int mask = TerrainPalette.FOLIAGE | TerrainPalette.WATER | TerrainPalette.EMISSIVE;
        return neighbor != null && material != null && (neighbor.flags() & mask) == (material.flags() & mask) ? values[index] : values[center];
    }

    private double interpolate(double[] values, int x, int z, int col, int row) {
        int index = z * SIDE + x, neighborX = x + (col < 2 ? -1 : 1), neighborZ = z + (row < 2 ? -1 : 1);
        double blendX = Math.abs((col + 0.5) / 4 - 0.5), blendZ = Math.abs((row + 0.5) / 4 - 0.5);
        double upper = values[index] * (1 - blendX) + neighborValue(values,neighborX,z,index) * blendX;
        double lower = neighborValue(values,x,neighborZ,index) * (1 - blendX) + neighborValue(values,neighborX,neighborZ,index) * blendX;
        return upper * (1 - blendZ) + lower * blendZ;
    }

    byte[] raster() {
        byte[] pixels = new byte[64 * 64 * 3];
        double[] lights = new double[COLUMNS], waterDepths = new double[COLUMNS];
        for (int z = 1; z < SIDE - 1; ++z) for (int x = 1; x < SIDE - 1; ++x) {
            lights[z * SIDE + x] = lighting(x,z); waterDepths[z * SIDE + x] = depths[z * SIDE + x];
        }
        for (int z = BORDER; z < 16 + BORDER; ++z) for (int x = BORDER; x < 16 + BORDER; ++x) {
            int index = z * SIDE + x;
            var material = materials[index] == null ? TerrainPalette.VOID : materials[index];
            boolean water = (material.flags() & TerrainPalette.WATER) != 0;
            boolean canopy = (material.flags() & TerrainPalette.FOLIAGE) != 0;
            int tint = tints[index];
            if (tint == 0 && material.tintIndex() < 0) tint = 0xffffff;
            double averageRed = 0, averageGreen = 0, averageBlue = 0, totalAlpha = 0;
            for (int pixel : material.texels()) {
                double alpha = pixel >>> 24;
                averageRed += (pixel >> 16 & 255) * alpha;
                averageGreen += (pixel >> 8 & 255) * alpha;
                averageBlue += (pixel & 255) * alpha;
                totalAlpha += alpha;
            }
            if (totalAlpha > 0) { averageRed /= totalAlpha; averageGreen /= totalAlpha; averageBlue /= totalAlpha; }
            double detail = canopy ? 0.025 : material.tintIndex() >= 0 ? 0.035 : 0.22;
            for (int row = 0; row < 4; ++row) for (int col = 0; col < 4; ++col) {
                double light = interpolate(lights,x,z,col,row);
                int argb = material.texels()[row * 4 + col];
                double alpha = (argb >>> 24) / 255.0;
                if (canopy) alpha = 0.97 * canopyCoverage(x,z,col,row);
                int under = ground[index] == 0 ? 0x243326 : ground[index];
                double red = (averageRed + ((argb >> 16 & 255) - averageRed) * detail) * (tint >> 16 & 255) / 255.0;
                double green = (averageGreen + ((argb >> 8 & 255) - averageGreen) * detail) * (tint >> 8 & 255) / 255.0;
                double blue = (averageBlue + ((argb & 255) - averageBlue) * detail) * (tint & 255) / 255.0;
                double exposure = canopy && material.tintIndex() >= 0 ? 190 / Math.max(1,averageRed * 0.2126 + averageGreen * 0.7152 + averageBlue * 0.0722) : material.tintIndex() >= 0 ? 1.08 : 1.0;
                double background = canopy ? 0.85 : 0.60;
                red = red * alpha * exposure + (under >> 16 & 255) * (1 - alpha) * background;
                green = green * alpha * exposure + (under >> 8 & 255) * (1 - alpha) * background;
                blue = blue * alpha * exposure + (under & 255) * (1 - alpha) * background;
                if (water) {
                    double depth = Math.clamp(interpolate(waterDepths,x,z,col,row) / 14.0,0,1);
                    double texture = 0.995 + ((argb >> 16 & 255) + (argb >> 8 & 255) + (argb & 255)) / 76500.0;
                    double floor = 0.28 * (1 - depth);
                    red = ((tint >> 16 & 255) * 0.38 + 0x34 * 0.62) * (1 - depth * 0.20) * texture * (1 - floor) + (under >> 16 & 255) * floor;
                    green = ((tint >> 8 & 255) * 0.38 + 0x65 * 0.62) * (1 - depth * 0.20) * texture * (1 - floor) + (under >> 8 & 255) * floor;
                    blue = ((tint & 255) * 0.38 + 0x78 * 0.62) * (1 - depth * 0.20) * texture * (1 - floor) + (under & 255) * floor;
                }
                int output = (((z - BORDER) * 4 + row) * 64 + (x - BORDER) * 4 + col) * 3;
                pixels[output] = (byte)Math.clamp((int)(red * light),0,255);
                pixels[output + 1] = (byte)Math.clamp((int)(green * light),0,255);
                pixels[output + 2] = (byte)Math.clamp((int)(blue * light),0,255);
            }
        }
        return pixels;
    }

    JsonObject encode(int x, int z) {
        var result = new JsonObject(); result.addProperty("x",x); result.addProperty("z",z); result.addProperty("resolution",64);
        result.addProperty("pixels",Base64.getEncoder().encodeToString(raster())); return result;
    }
}
