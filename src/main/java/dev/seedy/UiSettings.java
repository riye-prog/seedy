package dev.seedy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

final class UiSettings {
    private final Path path;
    UiSettings(Path path) { this.path = path; }

    JsonObject load() throws IOException {
        if (!Files.exists(path)) return normalize(new JsonObject());
        if (Files.size(path) > 32_768) throw new IOException("Window settings file is too large.");
        try { return normalize(JsonParser.parseString(Files.readString(path)).getAsJsonObject()); }
        catch (RuntimeException error) { throw new IOException("Window settings could not be read.", error); }
    }

    JsonObject save(JsonObject input) throws IOException {
        var settings = normalize(input);
        Files.createDirectories(path.getParent());
        var temporary = Files.createTempFile(path.getParent(), "ui-", ".tmp");
        try {
            Files.writeString(temporary, settings.toString());
            try { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (java.nio.file.AtomicMoveNotSupportedException ignored) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
        return settings;
    }

    static JsonObject normalize(JsonObject input) {
        var result = new JsonObject();
        number(input,result,"width",1040,520,7680);
        number(input,result,"height",760,420,4320);
        number(input,result,"x",-1,-1,7680);
        number(input,result,"y",-1,-1,4320);
        number(input,result,"sidebarWidth",240,180,400);
        number(input,result,"opacity",1,0.65,1);
        number(input,result,"markerSize",4,2,9);
        number(input,result,"zoomSpeed",0.5,0.125,1);
        number(input,result,"detail",1,0,2);
        number(input,result,"grid",1,0,2);
        number(input,result,"section",5,0,6);
        for (String key : java.util.List.of("maximized","controls","biomeLabels","structureLabels","showPlayer")) {
            boolean fallback = !key.equals("maximized");
            result.addProperty(key, input.has(key) && input.get(key).isJsonPrimitive() && input.get(key).getAsJsonPrimitive().isBoolean() ? input.get(key).getAsBoolean() : fallback);
        }
        return result;
    }

    private static void number(JsonObject input, JsonObject output, String key, double fallback, double min, double max) {
        double value = fallback;
        try { if (input.has(key)) value = input.get(key).getAsDouble(); } catch (RuntimeException ignored) { }
        output.addProperty(key, Double.isFinite(value) ? Math.clamp(value,min,max) : fallback);
    }
}
