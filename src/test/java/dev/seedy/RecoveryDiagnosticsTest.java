package dev.seedy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.seedy.recovery.RecoveryDiagnostics;
import java.io.DataInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RecoveryDiagnosticsTest {
    @TempDir Path directory;

    @Test void exportsTheExactSignedNativeInput() throws Exception {
        var context = new JsonObject();
        context.addProperty("clientVersion", "26.3");
        context.addProperty("generationVersion", "26.2");
        long mask = (1L << 26) - 1;
        long first = ((-123L & mask) << 38) | ((-456L & mask) << 12) | (4L << 4) | 1;
        long second = (789L << 38) | (12L << 12) | (84L << 4) | 3;
        RecoveryDiagnostics.writeChunks(directory, java.util.List.of(new long[]{first},new long[0],new long[]{second}), context);
        try (var input = new DataInputStream(new GZIPInputStream(Files.newInputStream(directory.resolve("last-blocks.bin.gz"))))) {
            assertEquals(-123, input.readInt());
            assertEquals(-60, input.readInt());
            assertEquals(-456, input.readInt());
            assertEquals(1, input.readInt());
            assertEquals(789, input.readInt());
            assertEquals(20, input.readInt());
            assertEquals(12, input.readInt());
            assertEquals(3, input.readInt());
            assertEquals(-1, input.read());
        }
        var metadata = JsonParser.parseString(Files.readString(directory.resolve("last-blocks.json"))).getAsJsonObject();
        assertEquals(2, metadata.get("observations").getAsInt());
        assertEquals("26.2", metadata.get("generationVersion").getAsString());
        assertFalse(Files.exists(directory.resolve("last-blocks.bin.gz.tmp")));
    }
}
