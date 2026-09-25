package dev.seedy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path directory;

    @Test void sessionsRoundTripAndStayIsolatedBySeedHashAndVersion() throws Exception {
        var store = new SessionStore(directory, "26.2");
        var evidence = new SessionStore.Evidence("minecraft:trial_chambers", -34, 68, "Detected");
        var location = new SessionStore.SavedLocation("location", "-1234", "minecraft:ancient_city", -128, -40, 512, "overworld", "Vanilla generation");
        var session = new SessionStore.Session(1, "26.2", 8765L, "-1234", true, false, List.of(evidence), List.of(location));
        store.save(session);
        assertEquals(session, store.load(8765));
        assertNull(store.load(8766));
        assertNull(new SessionStore(directory,"26.3").load(8765));
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void malformedSessionsAreReportedWithoutOverwritingThem() throws Exception {
        var store = new SessionStore(directory,"26.2");
        Files.writeString(store.file(1), "broken");
        assertThrows(java.io.IOException.class, () -> store.load(1));
        assertEquals("broken", Files.readString(store.file(1)));
    }
}
