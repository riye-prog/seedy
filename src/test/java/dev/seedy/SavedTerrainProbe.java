package dev.seedy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.DataInputStream;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;
import javax.imageio.ImageIO;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

public final class SavedTerrainProbe {
    private record Model(Map<String,String> textures, JsonArray elements) { }
    private record Section(BlockState[] states, long[] data, String[] biomes, long[] biomeData) {
        BlockState block(int x, int y, int z) { return states[paletteIndex(data,states.length,4,((y & 15) << 8) | (z << 4) | x)]; }
        String biome(int x, int y, int z) { return biomes[paletteIndex(biomeData,biomes.length,1,(((y & 15) >> 2) << 4) | ((z >> 2) << 2) | (x >> 2))]; }
    }
    private record Surface(TerrainPalette.Material[] materials, int[] heights, int[] tints, int[] depths, int[] ground, String[] biomes, BlockState[] blocks) { }
    private final Map<String,Model> models = new HashMap<>();
    private final IdentityHashMap<BlockState,TerrainPalette.Material> materials = new IdentityHashMap<>();
    private final Map<String,int[]> textures = new HashMap<>();
    private final Map<Long,Surface> surfaces = new HashMap<>();
    private final Map<String,RandomAccessFile> regions = new HashMap<>();
    private final Map<String,Integer> histogram = new HashMap<>();
    private final JsonArray markers = new JsonArray();
    private final Path directory;

    private SavedTerrainProbe(Path directory) { this.directory = directory; }

    private static JsonObject json(String path) {
        try (var input = SavedTerrainProbe.class.getResourceAsStream(path)) {
            return input == null ? new JsonObject() : JsonParser.parseReader(new InputStreamReader(input,java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception missing) { return new JsonObject(); }
    }

    private Model model(String name, int depth) {
        if (depth > 24) return new Model(Map.of(),new JsonArray());
        if (models.containsKey(name)) return models.get(name);
        var id = Identifier.parse(name);
        var source = json("/assets/" + id.getNamespace() + "/models/" + id.getPath() + ".json");
        var parent = source.has("parent") ? model(source.get("parent").getAsString(),depth + 1) : new Model(Map.of(),new JsonArray());
        var slots = new HashMap<>(parent.textures());
        if (source.has("textures")) for (var entry : source.getAsJsonObject("textures").entrySet()) slots.put(entry.getKey(),entry.getValue().getAsString());
        var result = new Model(slots,source.has("elements") ? source.getAsJsonArray("elements") : parent.elements());
        models.put(name,result); return result;
    }

    private static String resolve(Model model, String texture) {
        for (int i = 0; i < 24 && texture.startsWith("#"); ++i) texture = model.textures().getOrDefault(texture.substring(1),"minecraft:block/stone");
        return texture;
    }

    private String modelName(BlockState state) {
        var id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        var source = json("/assets/" + id.getNamespace() + "/blockstates/" + id.getPath() + ".json");
        if (source.has("variants")) for (var entry : source.getAsJsonObject("variants").entrySet()) {
            boolean matches = true;
            for (String pair : entry.getKey().split(",")) if (!pair.isEmpty()) {
                String[] property = pair.split("=");
                var definition = state.getBlock().getStateDefinition().getProperty(property[0]);
                if (definition == null || !propertyValue(state,definition).equals(property[1])) matches = false;
            }
            if (matches) { var value = entry.getValue().isJsonArray() ? entry.getValue().getAsJsonArray().get(0) : entry.getValue(); return value.getAsJsonObject().get("model").getAsString(); }
        }
        return id.getNamespace() + ":block/" + id.getPath();
    }

    private static <T extends Comparable<T>> String propertyValue(BlockState state, Property<T> property) { return property.getName(state.getValue(property)); }

    private int[] texture(String name) {
        return textures.computeIfAbsent(name,value -> {
            var id = Identifier.parse(value);
            try (var input = SavedTerrainProbe.class.getResourceAsStream("/assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png")) {
                if (input == null) return TerrainPalette.solid(0x808080,0).texels();
                var image = ImageIO.read(input);
                return TerrainPalette.downsample(image.getWidth(),Math.min(image.getWidth(),image.getHeight()),image::getRGB);
            } catch (Exception error) { throw new IllegalStateException(value,error); }
        });
    }

    private TerrainPalette.Material material(BlockState state) {
        return materials.computeIfAbsent(state,value -> {
            boolean water = state.is(Blocks.WATER) || state.is(Blocks.BUBBLE_COLUMN);
            int flags = state.getBlock() instanceof LeavesBlock ? TerrainPalette.FOLIAGE : 0;
            if (water) return new TerrainPalette.Material(texture("minecraft:block/water_still"),0,true,flags | TerrainPalette.WATER);
            if (state.getLightEmission() >= 12) flags |= TerrainPalette.EMISSIVE;
            var model = model(modelName(state),0);
            JsonObject face = null;
            double highest = -1;
            for (var entry : model.elements()) {
                var element = entry.getAsJsonObject();
                var faces = element.getAsJsonObject("faces");
                if (faces != null && faces.has("up") && element.getAsJsonArray("to").get(1).getAsDouble() > highest) { highest = element.getAsJsonArray("to").get(1).getAsDouble(); face = faces.getAsJsonObject("up"); }
            }
            String slot = face == null ? model.textures().getOrDefault("particle",model.textures().getOrDefault("all","minecraft:block/stone")) : face.get("texture").getAsString();
            int tint = face != null && face.has("tintindex") ? face.get("tintindex").getAsInt() : -1;
            return new TerrainPalette.Material(texture(resolve(model,slot)),tint,face != null,flags);
        });
    }

    private static int paletteIndex(long[] words, int size, int minimumBits, int index) {
        if (size <= 1) return 0;
        int bits = Math.max(minimumBits,32 - Integer.numberOfLeadingZeros(size - 1)), perWord = 64 / bits;
        int word = index / perWord;
        return word >= words.length ? 0 : (int)(words[word] >>> (index % perWord * bits) & ((1L << bits) - 1));
    }

    private static <T extends Comparable<T>> BlockState property(BlockState state, Property<T> key, String value) { return key.getValue(value).map(parsed -> state.setValue(key,parsed)).orElse(state); }

    static BlockState blockState(net.minecraft.nbt.Tag input) {
        if (!(input instanceof CompoundTag tag)) {
            String text = input.asString().orElse("minecraft:air");
            int properties = text.indexOf('[');
            String name = properties < 0 ? text : text.substring(0,properties);
            var state = BuiltInRegistries.BLOCK.getValue(Identifier.parse(name)).defaultBlockState();
            if (properties >= 0) for (String pair : text.substring(properties + 1,text.length() - 1).split(",")) {
                String[] value = pair.split("="); var key = state.getBlock().getStateDefinition().getProperty(value[0]);
                if (key != null && value.length == 2) state = property(state,key,value[1]);
            }
            return state;
        }
        var state = BuiltInRegistries.BLOCK.getValue(Identifier.parse(tag.getStringOr("Name",tag.getStringOr("id","minecraft:air")))).defaultBlockState();
        var properties = tag.contains("Properties") ? tag.getCompoundOrEmpty("Properties") : tag.getCompoundOrEmpty("properties");
        for (String name : properties.keySet()) {
            var key = state.getBlock().getStateDefinition().getProperty(name);
            if (key != null) state = property(state,key,properties.getStringOr(name,""));
        }
        return state;
    }

    private CompoundTag chunk(int x, int z) throws Exception {
        String name = "r." + Math.floorDiv(x,32) + "." + Math.floorDiv(z,32) + ".mca";
        Path path = directory.resolve(name);
        if (!Files.exists(path)) return null;
        var region = regions.get(name);
        if (region == null) { region = new RandomAccessFile(path.toFile(),"r"); regions.put(name,region); }
        region.seek((Math.floorMod(x,32) + Math.floorMod(z,32) * 32) * 4L);
        int location = region.readInt();
        if ((location >>> 8) == 0) return null;
        region.seek((location >>> 8) * 4096L);
        int length = region.readInt(), compression = region.readUnsignedByte();
        if (length <= 1 || length > 16_000_000) return null;
        byte[] bytes = new byte[length - 1]; region.readFully(bytes);
        var input = new java.io.ByteArrayInputStream(bytes);
        try (var decompressed = compression == 2 ? new InflaterInputStream(input) : compression == 1 ? new GZIPInputStream(input) : input) { return NbtIo.read(new DataInputStream(decompressed),NbtAccounter.unlimitedHeap()); }
    }

    private Surface surface(int chunkX, int chunkZ) throws Exception {
        long key = ((long)chunkX << 32) | (chunkZ & 0xffffffffL);
        if (surfaces.containsKey(key)) return surfaces.get(key);
        var data = chunk(chunkX,chunkZ);
        if (data == null) { surfaces.put(key,null); return null; }
        var starts = data.getCompoundOrEmpty("structures").getCompoundOrEmpty("starts");
        for (String name : starts.keySet()) {
            var structure = starts.getCompoundOrEmpty(name);
            String id = structure.getStringOr("id","");
            if (!id.contains("village")) continue;
            var children = structure.getListOrEmpty("Children");
            int[] bounds = structure.getIntArray("BB").orElse(children.isEmpty() ? new int[0] : ((CompoundTag)children.get(0)).getIntArray("BB").orElse(new int[0]));
            if (bounds.length != 6) continue;
            var marker = new JsonObject(); marker.addProperty("name",id); marker.addProperty("dimension","overworld"); marker.addProperty("x",(bounds[0] + bounds[3]) / 2); marker.addProperty("z",(bounds[2] + bounds[5]) / 2); markers.add(marker);
        }
        var sections = new HashMap<Integer,Section>();
        for (var value : data.getListOrEmpty("sections")) {
            var section = (CompoundTag)value; var states = section.getCompoundOrEmpty("block_states"); var biome = section.getCompoundOrEmpty("biomes");
            var palette = states.getListOrEmpty("palette");
            if (palette.isEmpty()) continue;
            BlockState[] blocks = new BlockState[palette.size()];
            for (int i = 0; i < blocks.length; ++i) blocks[i] = blockState(palette.get(i));
            var names = biome.getListOrEmpty("palette"); String[] biomes = new String[Math.max(1,names.size())];
            for (int i = 0; i < biomes.length; ++i) biomes[i] = i < names.size() ? names.get(i).asString().orElse("minecraft:plains") : "minecraft:plains";
            sections.put(section.getIntOr("Y",0),new Section(blocks,states.getLongArray("data").orElse(new long[0]),biomes,biome.getLongArray("data").orElse(new long[0])));
        }
        var output = new Surface(new TerrainPalette.Material[256],new int[256],new int[256],new int[256],new int[256],new String[256],new BlockState[256]);
        var biomeRegistry = WorldgenAdapter.registries().lookupOrThrow(Registries.BIOME);
        var order = sections.keySet().stream().sorted(Comparator.reverseOrder()).toList();
        for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
            int index = z * 16 + x; boolean found = false;
            for (int sy : order) {
                var section = sections.get(sy);
                for (int dy = 15; dy >= 0; --dy) {
                    int y = sy * 16 + dy; var state = section.block(x,y,z);
                    if (state.isAir()) continue;
                    var texture = material(state);
                    if (!texture.top() && !state.isSolidRender() && !state.hasBlockEntity() && !(state.getBlock() instanceof LeavesBlock)) continue;
                    output.materials()[index] = texture; output.heights()[index] = y; output.ground()[index] = 0x263c29; output.blocks()[index] = state;
                    String biomeName = section.biome(x,y,z); output.biomes()[index] = biomeName;
                    var holder = biomeRegistry.get(net.minecraft.resources.ResourceKey.create(Registries.BIOME,Identifier.parse(biomeName))).orElseThrow(); var biome = holder.value();
                    int tint = 0xffffff;
                    if (texture.tintIndex() >= 0) {
                        if ((texture.flags() & TerrainPalette.WATER) != 0) tint = biome.getWaterColor();
                        else if ((texture.flags() & TerrainPalette.FOLIAGE) != 0) tint = state.is(Blocks.BIRCH_LEAVES) ? 0x80a755 : state.is(Blocks.SPRUCE_LEAVES) ? 0x619961 : biome.getFoliageColor();
                        else tint = biome.getGrassColor(chunkX * 16 + x,chunkZ * 16 + z);
                    }
                    output.tints()[index] = tint;
                    if ((texture.flags() & (TerrainPalette.WATER | TerrainPalette.FOLIAGE | TerrainPalette.TRANSLUCENT)) != 0) for (int depth = 1; depth <= 24; ++depth) {
                        int below = y - depth; var lower = sections.get(Math.floorDiv(below,16)); if (lower == null) break;
                        var block = lower.block(x,below,z); output.depths()[index] = depth;
                        if (block.isAir() || block.getBlock() instanceof LeavesBlock || block.is(Blocks.WATER) || block.is(Blocks.BUBBLE_COLUMN)) continue;
                        var color = block.getMapColor(EmptyBlockGetter.INSTANCE,BlockPos.ZERO);
                        if (color == net.minecraft.world.level.material.MapColor.NONE) continue;
                        output.ground()[index] = color == net.minecraft.world.level.material.MapColor.GRASS ? biome.getGrassColor(chunkX * 16 + x,chunkZ * 16 + z) : color.col; break;
                    }
                    histogram.merge(BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),1,Integer::sum); found = true; break;
                }
                if (found) break;
            }
            if (!found) { output.materials()[index] = TerrainPalette.VOID; output.tints()[index] = 0xffffff; output.heights()[index] = -65; output.biomes()[index] = "minecraft:the_void"; }
        }
        surfaces.put(key,output); return output;
    }

    private JsonObject capture(int centerX, int centerZ, int radius) throws Exception {
        var result = new JsonObject(); var tiles = new JsonArray();
        int baseX = Math.floorDiv(centerX,16), baseZ = Math.floorDiv(centerZ,16);
        for (int dz = -radius; dz <= radius; ++dz) for (int dx = -radius; dx <= radius; ++dx) {
            int x = baseX + dx, z = baseZ + dz;
            if (surface(x,z) == null) continue;
            var tile = new TerrainSurface();
            for (int row = 0; row < TerrainSurface.SIDE; ++row) for (int col = 0; col < TerrainSurface.SIDE; ++col) {
                int lx = col - TerrainSurface.BORDER, lz = row - TerrainSurface.BORDER; var source = surface(x + Math.floorDiv(lx,16),z + Math.floorDiv(lz,16));
                if (source == null) continue;
                int at = Math.floorMod(lz,16) * 16 + Math.floorMod(lx,16), target = row * TerrainSurface.SIDE + col;
                tile.materials[target] = source.materials()[at]; tile.tints[target] = source.tints()[at]; tile.heights[target] = source.heights()[at]; tile.depths[target] = source.depths()[at]; tile.ground[target] = source.ground()[at];
            }
            var encoded = tile.encode(x,z); encoded.addProperty("epoch",1); encoded.addProperty("dimension","overworld"); tiles.add(encoded);
        }
        result.addProperty("x",centerX); result.addProperty("z",centerZ); result.add("terrain",tiles);
        result.add("markers",markers);
        result.addProperty("seed",Long.toString(worldSeed(directory)));
        var center = surface(baseX,baseZ);
        int local = Math.floorMod(centerZ,16) * 16 + Math.floorMod(centerX,16);
        result.addProperty("y",center == null ? 64 : center.heights()[local] + 2); result.addProperty("biome",center == null ? "minecraft:plains" : center.biomes()[local]);
        result.addProperty("scope","saved Minecraft chunks, vanilla block-model textures and biome tints; no Minecraft instance launched");
        System.out.println("terrain chunks=" + tiles.size() + " materials=" + materials.size() + " texture samples=" + textures.size());
        System.out.println(histogram.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).limit(12).toList());
        return result;
    }

    static long worldSeed(Path directory) throws Exception {
        for (Path folder = directory; folder != null; folder = folder.getParent()) {
            Path modern = folder.resolve("data/minecraft/world_gen_settings.dat");
            if (Files.exists(modern)) {
                var data = NbtIo.readCompressed(modern,NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("data");
                if (data.contains("seed")) return data.getLongOr("seed",0);
            }
            if (Files.exists(folder.resolve("level.dat"))) {
                var data = NbtIo.readCompressed(folder.resolve("level.dat"),NbtAccounter.unlimitedHeap()).getCompoundOrEmpty("Data").getCompoundOrEmpty("WorldGenSettings");
                if (data.contains("seed")) return data.getLongOr("seed",0);
            }
        }
        throw new IllegalStateException("Saved world seed metadata was not found");
    }

    static JsonObject compareSurface(Path directory, long seed, SeedTerrain terrain) throws Exception {
        if (worldSeed(directory) != seed) throw new IllegalArgumentException("Saved world has a different seed");
        var probe = new SavedTerrainProbe(directory);
        int columns = 0, heights = 0, materials = 0;
        var included = java.util.Set.of(Blocks.GRASS_BLOCK,Blocks.DIRT,Blocks.SAND,Blocks.GRAVEL,Blocks.STONE,Blocks.WATER,Blocks.RED_SAND,Blocks.PACKED_ICE,Blocks.ICE,Blocks.SNOW_BLOCK,Blocks.COARSE_DIRT,Blocks.PODZOL);
        try {
            for (int z = -2; z <= 2; ++z) for (int x = -2; x <= 2; ++x) {
                var saved = probe.surface(x,z);
                if (saved == null) continue;
                for (int row = 0; row < 16; ++row) for (int col = 0; col < 16; ++col) {
                    int index = row * 16 + col;
                    var state = saved.blocks()[index];
                    if (state == null || !included.contains(state.getBlock())) continue;
                    var predicted = terrain.pixel(x * 16 + col,z * 16 + row,64);
                    ++columns;
                    if (predicted.height() == saved.heights()[index]) ++heights;
                    if (predicted.height() == saved.heights()[index] && predicted.block().getBlock() == state.getBlock()) ++materials;
                }
            }
        } finally { for (var region : probe.regions.values()) region.close(); }
        var result = new JsonObject(); result.addProperty("seed",Long.toString(seed)); result.addProperty("columns",columns); result.addProperty("matchingHeights",heights); result.addProperty("matchingSurfaceBlocks",materials);
        result.addProperty("scope","saved world columns with natural surface blocks; vegetation and structures excluded");
        return result;
    }

    static JsonObject compareDecoration(Path directory, long seed, SeedTerrain terrain, int centerX, int centerZ, int radius) throws Exception {
        if (worldSeed(directory) != seed) throw new IllegalArgumentException("Saved world has a different seed");
        var probe = new SavedTerrainProbe(directory);
        var groups = new java.util.TreeMap<String,int[]>();
        var differences = new JsonArray();
        try {
            for (int z = (centerZ >> 4) - radius; z <= (centerZ >> 4) + radius; ++z) for (int x = (centerX >> 4) - radius; x <= (centerX >> 4) + radius; ++x) {
                var saved = probe.surface(x,z);
                if (saved == null) continue;
                for (int row = 0; row < 16; ++row) for (int col = 0; col < 16; ++col) {
                    int index = row * 16 + col;
                    var state = saved.blocks()[index];
                    if (state == null) continue;
                    var predicted = terrain.pixel(x * 16 + col,z * 16 + row,64);
                    String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
                    String group = state.getBlock() instanceof LeavesBlock ? "leaves" : name.contains("planks") || name.contains("stairs") || name.contains("cobblestone") || name.contains("path") || name.contains("slab") ? "buildings" : "other";
                    var counts = groups.computeIfAbsent(group,ignored -> new int[3]);
                    ++counts[0];
                    if (predicted.height() == saved.heights()[index]) ++counts[1];
                    if (predicted.height() == saved.heights()[index] && predicted.block().getBlock() == state.getBlock()) ++counts[2];
                    else if (differences.size() < 16) {
                        var difference = new JsonObject(); difference.addProperty("x",x * 16 + col); difference.addProperty("z",z * 16 + row);
                        difference.addProperty("saved",name + "@" + saved.heights()[index]); difference.addProperty("predicted",BuiltInRegistries.BLOCK.getKey(predicted.block().getBlock()) + "@" + predicted.height()); differences.add(difference);
                    }
                }
            }
        } finally { for (var region : probe.regions.values()) region.close(); }
        var result = new JsonObject(); result.addProperty("seed",Long.toString(seed));
        groups.forEach((name,counts) -> { var group = new JsonObject(); group.addProperty("columns",counts[0]); group.addProperty("matchingHeights",counts[1]); group.addProperty("matchingSurfaceBlocks",counts[2]); result.add(name,group); });
        result.add("differences",differences); result.addProperty("scope","seed-generated decoration compared with saved-world surface columns, including leaves and buildings");
        return result;
    }

    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
        for (String name : new String[]{"grass","foliage"}) try (var input = SavedTerrainProbe.class.getResourceAsStream("/assets/minecraft/textures/colormap/" + name + ".png")) {
            var image = ImageIO.read(input); int[] colors = image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
            if (name.equals("grass")) net.minecraft.world.level.GrassColor.init(colors); else net.minecraft.world.level.FoliageColor.init(colors);
        }
        var probe = new SavedTerrainProbe(Path.of(args[0]));
        try {
            var capture = probe.capture(Integer.parseInt(args[2]),Integer.parseInt(args[3]),Integer.parseInt(args[4]));
            Files.writeString(Path.of(args[1]),capture.toString());
        } finally { for (var region : probe.regions.values()) region.close(); }
    }
}
