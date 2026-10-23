package dev.seedy.recovery;

import java.util.regex.Pattern;

public final class WorldVersionCheck {
    private static final Pattern SUPPORTED = Pattern.compile("(?<![\\d.])26\\.[23](?!\\d)");
    private WorldVersionCheck() { }

    public static String warning(String selected, String reported) {
        if (reported == null) return "";
        var match = SUPPORTED.matcher(reported);
        if (!match.find() || match.group().equals(selected)) return "";
        return "Server reports " + match.group() + "; selected generation version is " + selected + ". Confirm which version generated these chunks.";
    }

    public static String translationTarget() {
        try {
            Class<?> implementation = Class.forName("com.viaversion.viafabricplus.ViaFabricPlusImpl");
            Object api = implementation.getMethod("impl").invoke(null);
            Object version = implementation.getMethod("targetVersion").invoke(api);
            return (String)version.getClass().getMethod("getName").invoke(version);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) { return ""; }
    }
}
