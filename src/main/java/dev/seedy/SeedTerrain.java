package dev.seedy;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;

final class SeedTerrain implements AutoCloseable {
    record Pixel(int height, int floor, BlockState block, Biome biome, String biomeName, int ground) {
        Pixel(int height, int floor, BlockState block, Biome biome, String biomeName) { this(height,floor,block,biome,biomeName,0x263c29); }
    }
    record ChunkKey(int x, int z, int slice) { }
    record SampleKey(int x, int z, int slice) { }
    record Surface(List<Integer> runs, String pixels, String water, boolean detailed, int resolution) { }
    final TerrainGenerator generator;
    private final SeedDecorationWorld decoration;
    private final SeedTerrainPalette textures = new SeedTerrainPalette();
    private final String dimension;
    private final Map<ChunkKey,Pixel[]> chunks = bounded(512);
    private final Map<SampleKey,Pixel> overview = bounded(16384);
    private final Locator.BiomeSampler biomeSampler;
    private final net.minecraft.core.HolderLookup.Provider registries;
    private final BlockPos.MutableBlockPos position = new BlockPos.MutableBlockPos();

    SeedTerrain(Locator.StructureWorld world, long seed, net.minecraft.core.HolderLookup.Provider registries, Locator.BiomeSampler biomeSampler) {
        this(world,seed,registries,biomeSampler,SeedDecorationRules.VANILLA);
    }

    SeedTerrain(Locator.StructureWorld world, long seed, net.minecraft.core.HolderLookup.Provider registries, Locator.BiomeSampler biomeSampler, SeedDecorationRules rules) {
        this.registries = registries; this.biomeSampler = biomeSampler; dimension = world.dimension();
        var access = new RegistryAccess.ImmutableRegistryAccess(java.util.stream.Stream.concat(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY).registries(),((RegistryAccess)registries).registries()));
        decoration = new SeedDecorationWorld(access,WorldgenAdapter.withPlacements(world,seed,rules.placements(registries)),seed);
        generator = decoration.generator;
    }

    private static <K,V> Map<K,V> bounded(int limit) {
        return new LinkedHashMap<>(limit,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> entry) { return size() > limit; }
        };
    }

    private Pixel[] chunk(int x, int z, int y) {
        int slice = dimension.equals("the_nether") ? y : Integer.MAX_VALUE;
        var key = new ChunkKey(x,z,slice);
        var cached = chunks.get(key);
        if (cached != null) return cached;
        for (var chunk : decoration.generate(x,z).values()) {
            int cx = chunk.getPos().x(), cz = chunk.getPos().z();
            var result = new Pixel[256];
            for (int row = 0; row < 16; ++row) for (int col = 0; col < 16; ++col) {
                GeneratorAccess.checkpoint();
                int blockX = cx * 16 + col, blockZ = cz * 16 + row;
                int height = Math.min(slice,chunk.getHeight(Heightmap.Types.WORLD_SURFACE,col,row));
                while (height >= chunk.getMinY()) {
                    var state = chunk.getBlockState(position.set(blockX,height,blockZ));
                    if (!state.isAir() && (textures.material(state).top() || state.isSolidRender() || state.hasBlockEntity() || state.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock)) break;
                    --height;
                }
                var block = chunk.getBlockState(position.set(blockX,height,blockZ));
                int floor = height;
                if (water(block)) while (floor > chunk.getMinY() && water(chunk.getBlockState(position.set(blockX,floor,blockZ)))) --floor;
                var holder = generator.biomes.getBiome(position.set(blockX,height + 1,blockZ));
                int ground = 0x263c29;
                if (water(block) || block.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) for (int depth = 1; depth <= 24; ++depth) {
                    var below = chunk.getBlockState(position.set(blockX,height - depth,blockZ));
                    if (below.isAir() || water(below) || below.getBlock() instanceof net.minecraft.world.level.block.LeavesBlock) continue;
                    var color = below.getMapColor(EmptyBlockGetter.INSTANCE,position);
                    if (color == MapColor.NONE) continue;
                    ground = color == MapColor.GRASS ? holder.value().getGrassColor(blockX,blockZ) : color.col; break;
                }
                result[row * 16 + col] = new Pixel(height,floor,block,holder.value(),holder.unwrapKey().orElseThrow().identifier().toString(),ground);
            }
            chunks.put(new ChunkKey(cx,cz,slice),result);
        }
        return chunks.get(key);
    }

    Pixel pixel(int x, int z, int y) {
        return chunk(Math.floorDiv(x,16),Math.floorDiv(z,16),y)[Math.floorMod(z,16) * 16 + Math.floorMod(x,16)];
    }

    private Pixel overview(int x, int z, int y) {
        int slice = dimension.equals("the_nether") ? y : Integer.MAX_VALUE;
        return overview.computeIfAbsent(new SampleKey(x,z,slice),key -> {
            GeneratorAccess.checkpoint();
            var world = generator.world;
            int floor = world.generator().getBaseHeight(x,z,Heightmap.Types.OCEAN_FLOOR_WG,world.height(),world.placements().randomState()) - 1;
            int sea = world.generator().getSeaLevel();
            int height = dimension.equals("overworld") ? Math.max(floor,sea - 1) : floor;
            BlockState block;
            if (dimension.equals("the_nether")) {
                var column = world.generator().getBaseColumn(x,z,world.height(),world.placements().randomState());
                height = Math.min(slice,world.height().getMaxY());
                while (height >= world.height().getMinY() && column.getBlock(height).isAir()) --height;
                block = column.getBlock(height); floor = height;
            } else block = dimension.equals("the_end") ? floor < world.height().getMinY() ? Blocks.AIR.defaultBlockState() : Blocks.END_STONE.defaultBlockState() : floor < sea - 1 ? Blocks.WATER.defaultBlockState() : Blocks.GRASS_BLOCK.defaultBlockState();
            String name = biomeSampler.at(x,height + 1,z);
            var biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BIOME,net.minecraft.resources.Identifier.parse(name))).value();
            if (block.is(Blocks.GRASS_BLOCK)) block = overviewSurface(name).defaultBlockState();
            return new Pixel(height,floor,block,biome,name);
        });
    }

    private static net.minecraft.world.level.block.Block overviewSurface(String biome) {
        if (biome.contains("stony") || biome.contains("windswept_gravelly")) return Blocks.STONE;
        if (biome.contains("snow") || biome.contains("frozen") || biome.contains("ice_spikes") || biome.contains("grove")) return Blocks.SNOW_BLOCK;
        if (biome.contains("badlands")) return Blocks.RED_SAND;
        if (biome.contains("desert") || biome.contains("beach")) return Blocks.SAND;
        if (biome.contains("mushroom")) return Blocks.MYCELIUM;
        return Blocks.GRASS_BLOCK;
    }

    private static boolean water(BlockState state) { return state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN); }

    static int color(Pixel pixel, int grassTint, int waterTint) {
        var state = pixel.block();
        if (state.isAir()) return 0x151c23;
        if (water(state)) {
            int tint = waterTint;
            double depth = Math.clamp((pixel.height() - pixel.floor()) / 14.0,0,1);
            return rgb(((tint >> 16 & 255) * 0.38 + 0x34 * 0.62) * (1 - depth * 0.20),((tint >> 8 & 255) * 0.38 + 0x65 * 0.62) * (1 - depth * 0.20),((tint & 255) * 0.38 + 0x78 * 0.62) * (1 - depth * 0.20));
        }
        var color = state.getMapColor(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
        int tint = color.col;
        if (color == MapColor.GRASS) {
            tint = grassTint;
            return rgb((tint >> 16 & 255) * 0.75,(tint >> 8 & 255) * 0.79,(tint & 255) * 0.90);
        }
        double shade = color == MapColor.SAND ? 0.91 : color == MapColor.STONE ? 1.12 : 0.94;
        return rgb((tint >> 16 & 255) * shade,(tint >> 8 & 255) * shade,(tint & 255) * shade);
    }

    private static int rgb(double red, double green, double blue) { return Math.clamp((int)red,0,255) << 16 | Math.clamp((int)green,0,255) << 8 | Math.clamp((int)blue,0,255); }

    private static int blendedTint(int[] colors, int index) {
        int red = 0, green = 0, blue = 0;
        for (int row = -1; row <= 1; ++row) for (int col = -1; col <= 1; ++col) {
            int color = colors[index + row * 34 + col]; red += color >> 16 & 255; green += color >> 8 & 255; blue += color & 255;
        }
        return rgb(red / 9,green / 9,blue / 9);
    }

    private byte[] detailedRaster(int startX, int startZ, int step, int y) {
        byte[] output = new byte[128 * 128 * 3];
        int span = 32 * step;
        for (int cz = 0; cz < span; cz += 16) for (int cx = 0; cx < span; cx += 16) {
            GeneratorAccess.checkpoint();
            var surface = new TerrainSurface();
            for (int row = 0; row < TerrainSurface.SIDE; ++row) for (int col = 0; col < TerrainSurface.SIDE; ++col) {
                int x = startX + cx + col - TerrainSurface.BORDER, z = startZ + cz + row - TerrainSurface.BORDER;
                var sample = pixel(x,z,y); int index = row * TerrainSurface.SIDE + col;
                var material = textures.material(sample.block());
                surface.materials[index] = sample.block().isAir() ? TerrainPalette.VOID : material;
                surface.heights[index] = sample.height(); surface.depths[index] = sample.height() - sample.floor(); surface.ground[index] = sample.ground();
                surface.tints[index] = material.tintIndex() < 0 ? 0xffffff : (material.flags() & TerrainPalette.WATER) != 0 ? sample.biome().getWaterColor() : (material.flags() & TerrainPalette.FOLIAGE) != 0 ? sample.block().is(Blocks.BIRCH_LEAVES) ? 0x80a755 : sample.block().is(Blocks.SPRUCE_LEAVES) ? 0x619961 : sample.biome().getFoliageColor() : sample.biome().getGrassColor(x,z);
            }
            byte[] raster = surface.raster();
            for (int row = 0; row < 64 / step; ++row) for (int col = 0; col < 64 / step; ++col) for (int channel = 0; channel < 3; ++channel) {
                int sum = 0;
                for (int dz = 0; dz < step; ++dz) for (int dx = 0; dx < step; ++dx) sum += raster[((row * step + dz) * 64 + col * step + dx) * 3 + channel] & 255;
                output[((cz * 4 / step + row) * 128 + cx * 4 / step + col) * 3 + channel] = (byte)(sum / (step * step));
            }
        }
        return output;
    }

    Surface tile(SeedMap.TilePos tile, int step, int y, boolean relief, List<SeedMap.Swatch> palette) {
        boolean detailed = step <= 2;
        int startX = tile.x() * 32 * step, startZ = tile.z() * 32 * step;
        var samples = new Pixel[34 * 34];
        if (detailed) {
            for (int row = 0; row < 34; ++row) for (int col = 0; col < 34; ++col) {
                GeneratorAccess.checkpoint();
                samples[row * 34 + col] = pixel(startX + (col - 1) * step + step / 2,startZ + (row - 1) * step + step / 2,y);
            }
        } else {
            int spacing = step * 4;
            var nodes = new Pixel[11 * 11];
            for (int row = 0; row < 11; ++row) for (int col = 0; col < 11; ++col)
                nodes[row * 11 + col] = overview(startX + (col - 1) * spacing,startZ + (row - 1) * spacing,y);
            for (int row = 0; row < 34; ++row) for (int col = 0; col < 34; ++col) {
                if (col == 0) GeneratorAccess.checkpoint();
                int x = startX + (col - 1) * step + step / 2, z = startZ + (row - 1) * step + step / 2;
                double gx = (col - 1) / 4.0 + 1 + 0.125, gz = (row - 1) / 4.0 + 1 + 0.125;
                int cx = (int)Math.floor(gx), cz = (int)Math.floor(gz);
                double dx = gx - cx, dz = gz - cz;
                var a = nodes[cz * 11 + cx]; var b = nodes[cz * 11 + cx + 1]; var c = nodes[(cz + 1) * 11 + cx]; var d = nodes[(cz + 1) * 11 + cx + 1];
                int floor = (int)Math.round((a.floor() * (1 - dx) + b.floor() * dx) * (1 - dz) + (c.floor() * (1 - dx) + d.floor() * dx) * dz);
                int sea = generator.world.generator().getSeaLevel();
                int height = dimension.equals("overworld") ? Math.max(floor,sea - 1) : (int)Math.round((a.height() * (1 - dx) + b.height() * dx) * (1 - dz) + (c.height() * (1 - dx) + d.height() * dx) * dz);
                String name = biomeSampler.at(x,height + 1,z);
                var biome = registries.lookupOrThrow(Registries.BIOME).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.BIOME,net.minecraft.resources.Identifier.parse(name))).value();
                var block = dimension.equals("overworld") ? floor < sea - 1 ? Blocks.WATER.defaultBlockState() : overviewSurface(name).defaultBlockState() : dimension.equals("the_end") ? floor < generator.world.height().getMinY() ? Blocks.AIR.defaultBlockState() : Blocks.END_STONE.defaultBlockState() : a.block();
                samples[row * 34 + col] = new Pixel(height,floor,block,biome,name);
            }
        }
        var indices = new java.util.HashMap<String,Integer>();
        int[] grassTints = new int[34 * 34], waterTints = new int[34 * 34];
        for (int row = 0; row < 34; ++row) for (int col = 0; col < 34; ++col) {
            var biome = samples[row * 34 + col].biome();
            grassTints[row * 34 + col] = biome.getGrassColor(startX + (col - 1) * step,startZ + (row - 1) * step);
            waterTints[row * 34 + col] = biome.getWaterColor();
        }
        for (int i = 0; i < palette.size(); ++i) indices.put(palette.get(i).name(),i);
        byte[] pixels = new byte[32 * 32 * 3], wet = new byte[128]; var runs = new ArrayList<Integer>();
        int previous = -1, count = 0;
        for (int row = 1; row <= 32; ++row) for (int col = 1; col <= 32; ++col) {
            var sample = samples[row * 34 + col];
            int cell = (row - 1) * 32 + col - 1;
            if (water(sample.block())) wet[cell / 8] |= (byte)(1 << cell % 8);
            int value = indices.get(sample.biomeName());
            if (value != previous && count > 0) { runs.add(count); runs.add(previous); count = 0; }
            previous = value; ++count;
            int color = color(sample,blendedTint(grassTints,row * 34 + col),blendedTint(waterTints,row * 34 + col));
            double light = 1;
            if (relief && !water(sample.block()) && !sample.block().isAir()) {
                double dx = 0, dz = 0;
                for (int offset = -1; offset <= 1; ++offset) {
                    int weight = offset == 0 ? 2 : 1;
                    dx += weight * Math.clamp(samples[(row + offset) * 34 + col + 1].height() - samples[(row + offset) * 34 + col - 1].height(),-step * 12,step * 12) / (8.0 * step);
                    dz += weight * Math.clamp(samples[(row + 1) * 34 + col + offset].height() - samples[(row - 1) * 34 + col + offset].height(),-step * 12,step * 12) / (8.0 * step);
                }
                light = 0.88 + 0.16 * (2.6 - dx * 0.48 - dz * 0.38) / Math.sqrt(dx * dx + dz * dz + 7.25);
            }
            int output = ((row - 1) * 32 + col - 1) * 3;
            pixels[output] = (byte)Math.clamp((int)((color >> 16 & 255) * light),0,255);
            pixels[output + 1] = (byte)Math.clamp((int)((color >> 8 & 255) * light),0,255);
            pixels[output + 2] = (byte)Math.clamp((int)((color & 255) * light),0,255);
        }
        runs.add(count); runs.add(previous);
        if (detailed) pixels = detailedRaster(startX,startZ,step,y);
        return new Surface(List.copyOf(runs),Base64.getEncoder().encodeToString(pixels),java.util.HexFormat.of().formatHex(wet),detailed,detailed ? 128 : 32);
    }

    @Override public void close() { decoration.close(); chunks.clear(); overview.clear(); }
}
