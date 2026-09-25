package dev.seedy;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

final class SessionStore {
    record Evidence(String structure, int chunkX, int chunkZ, String source) { }
    record SavedLocation(String id, String seed, String name, int x, Integer y, int z, String dimension, String confidence) { }
    record Session(int schema, String version, long seedHash, String seed, boolean verified, boolean collecting, List<Evidence> observations, List<SavedLocation> locations) { }
    private static final Gson JSON = new Gson();
    private final Path directory;
    private final String version;

    SessionStore(Path directory, String version) {
        this.directory = directory;
        this.version = version.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    Path file(long hash) { return directory.resolve(version + "-" + Long.toUnsignedString(hash, 16) + ".json"); }

    Session load(long hash) throws IOException {
        Path file = file(hash);
        if (!Files.exists(file)) return null;
        if (Files.size(file) > 1_048_576) throw new IOException("Saved session is too large");
        try {
            var session = JSON.fromJson(Files.readString(file), Session.class);
            if (session == null || session.schema() != 1 || !version.equals(session.version()) || session.seedHash() != hash || session.observations() == null || session.locations() == null || session.observations().size() > 512 || session.locations().size() > 256) throw new IOException("Saved session is invalid");
            if (session.seed() != null) Long.parseLong(session.seed());
            return session;
        } catch (JsonParseException | NumberFormatException e) { throw new IOException("Saved session could not be read", e); }
    }

    void save(Session session) throws IOException {
        if (!version.equals(session.version())) throw new IOException("Session version does not match");
        Files.createDirectories(directory);
        Path target = file(session.seedHash());
        Path temporary = Files.createTempFile(directory, ".seedy-", ".tmp");
        try {
            Files.writeString(temporary, JSON.toJson(session));
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
