package dev.seedy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public final class NativeLibraries {
    private static final Map<String, Path> DIRECTORIES = new HashMap<>();
    private static final Set<String> LOADED = new HashSet<>();

    private NativeLibraries() { }

    public static synchronized void load(String platform, String name) throws IOException {
        if (!loadIfPresent(platform, name)) throw new IOException("Missing " + platform + "/" + name);
    }

    public static synchronized boolean loadIfPresent(String platform, String name) throws IOException {
        String resource = platform + "/" + name;
        if (LOADED.contains(resource)) return true;
        try (var input = NativeLibraries.class.getResourceAsStream("/natives/" + resource)) {
            if (input == null) return false;
            Path directory = DIRECTORIES.get(platform);
            if (directory == null) {
                directory = Files.createTempDirectory("seedy-native-");
                directory.toFile().deleteOnExit();
                DIRECTORIES.put(platform, directory);
            }
            Path library = directory.resolve(name);
            Files.copy(input, library);
            library.toFile().deleteOnExit();
            System.load(library.toAbsolutePath().toString());
            LOADED.add(resource);
            return true;
        }
    }
}
