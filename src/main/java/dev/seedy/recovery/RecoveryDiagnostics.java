package dev.seedy.recovery;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.zip.GZIPOutputStream;

public final class RecoveryDiagnostics {
    private RecoveryDiagnostics() { }

    public static void write(Path directory, long[] entries, JsonObject context) throws IOException {
        Files.createDirectories(directory);
        Path temporary = directory.resolve("last-blocks.bin.gz.tmp");
        try (var output = new DataOutputStream(new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)), 65536))) {
            for (long packed : entries) {
                output.writeInt((int)(packed >> 38));
                output.writeInt((int)(packed >> 4 & 255) - 64);
                output.writeInt((int)(packed << 26 >> 38));
                output.writeInt((int)(packed & 15));
            }
        }
        Files.move(temporary, directory.resolve("last-blocks.bin.gz"), StandardCopyOption.REPLACE_EXISTING);
        context.addProperty("format", "seedy-blocks-v1");
        context.addProperty("recordByteOrder", "big-endian");
        context.addProperty("recordFields", "x,y,z,type; four signed 32-bit integers");
        context.addProperty("observations", entries.length);
        context.addProperty("capturedAt", Instant.now().toString());
        Path metadata = directory.resolve("last-blocks.json.tmp");
        Files.writeString(metadata, new GsonBuilder().setPrettyPrinting().create().toJson(context));
        Files.move(metadata, directory.resolve("last-blocks.json"), StandardCopyOption.REPLACE_EXISTING);
    }
}
