package dev.seedy;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import javax.imageio.ImageIO;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

final class SeedTerrainPalette {
    private record Model(Map<String,String> textures, JsonArray elements) { }
    private final Map<String,Model> models = new HashMap<>();
    private final IdentityHashMap<BlockState,TerrainPalette.Material> materials = new IdentityHashMap<>();
    private final Map<String,int[]> textures = new HashMap<>();
    private static JsonObject json(String path) {
        try (var input = SeedTerrainPalette.class.getResourceAsStream(path)) {
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
            try (var input = SeedTerrainPalette.class.getResourceAsStream("/assets/" + id.getNamespace() + "/textures/" + id.getPath() + ".png")) {
                if (input == null) return TerrainPalette.solid(0x808080,0).texels();
                var image = ImageIO.read(input);
                return TerrainPalette.downsample(image.getWidth(),Math.min(image.getWidth(),image.getHeight()),image::getRGB);
            } catch (Exception error) { throw new IllegalStateException(value,error); }
        });
    }

    TerrainPalette.Material material(BlockState state) {
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

}
