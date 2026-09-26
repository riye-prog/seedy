package dev.seedy;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class UiSettingsTest {
    @TempDir Path directory;

    @Test void preferencesRoundTripAndStayWithinBounds() throws Exception {
        var store = new UiSettings(directory.resolve("seedy/ui.json"));
        var defaults = store.load();
        assertEquals(1040,defaults.get("width").getAsInt());
        var input = new JsonObject();
        input.addProperty("width",1280); input.addProperty("height",920); input.addProperty("maximized",true);
        input.addProperty("sidebarWidth",310); input.addProperty("detail",2); input.addProperty("grid",2); input.addProperty("biomeLabels",false);
        assertEquals(store.save(input),store.load());
        assertTrue(store.load().get("maximized").getAsBoolean());
        assertFalse(store.load().get("biomeLabels").getAsBoolean());
        input.addProperty("width",-500); input.addProperty("opacity",Double.NaN); input.addProperty("markerSize",500); input.addProperty("grid","invalid"); input.addProperty("section",99);
        var result = store.save(input);
        assertEquals(520,result.get("width").getAsInt());
        assertEquals(1,result.get("opacity").getAsDouble());
        assertEquals(9,result.get("markerSize").getAsInt());
        assertEquals(1,result.get("grid").getAsInt());
        assertEquals(6,result.get("section").getAsInt());
        assertEquals(result,store.load());
    }

    @Test void corruptSettingsAreNotOverwrittenOnRead() throws Exception {
        var file = directory.resolve("ui.json");
        Files.writeString(file,"unfinished settings");
        assertThrows(java.io.IOException.class,() -> new UiSettings(file).load());
        assertEquals("unfinished settings",Files.readString(file));
    }
}
