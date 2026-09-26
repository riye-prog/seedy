package dev.seedy;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class NativeGui {
    private static final ConcurrentLinkedQueue<double[]> INPUT = new ConcurrentLinkedQueue<>();
    private static boolean loaded;
    private static boolean failed;
    private static boolean rendered;
    private static boolean vulkanSeen;
    private static native String frame(String state, int width, int height, float density, double[] events, long[] graphics, double[] lines, boolean panel);
    private static native void dispose();

    public static void event(int kind, double a, double b, double c) {
        if (INPUT.size() < 1024) INPUT.add(new double[]{kind, a, b, c});
    }

    private static void load() throws Exception {
        if (loaded) return;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch");
        String target = os.contains("mac") ? "macos-universal/libseedy.dylib" : os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64")) ? "windows-x86_64/seedy.dll" : null;
        if (target == null) throw new IllegalStateException("The native panel supports macOS and Windows x64.");
        try (var input = NativeGui.class.getResourceAsStream("/natives/" + target)) {
            if (input == null) throw new IllegalStateException("The native panel library is missing from this build.");
            var directory = Files.createTempDirectory("seedy-overlay-");
            var library = directory.resolve(target.substring(target.indexOf('/') + 1));
            Files.copy(input, library);
            directory.toFile().deleteOnExit();
            library.toFile().deleteOnExit();
            System.load(library.toAbsolutePath().toString());
            loaded = true;
        }
    }

    public static boolean wantsFrame() {
        return !failed && (Minecraft.getInstance().gui.screen() instanceof OverlayScreen || LoadedObjects.active() || loaded && !INPUT.isEmpty());
    }

    public static void vulkanAvailable() { vulkanSeen = true; }

    public static void checkBackend() {
        if (!vulkanSeen && !failed && Minecraft.getInstance().gui.screen() instanceof OverlayScreen) fail(new IllegalStateException("Select Vulkan in Minecraft's video settings and restart the game to use Seedy's panel."));
    }

    public static void render(long[] graphics, int width, int height) {
        if (!wantsFrame()) return;
        var client = Minecraft.getInstance();
        try {
            load();
            var window = client.getWindow();
            boolean panel = client.gui.screen() instanceof OverlayScreen;
            if (panel) event(0, OverlayScreen.framebufferCoordinate(client.mouseHandler.xpos(), width, window.getScreenWidth()), OverlayScreen.framebufferCoordinate(client.mouseHandler.ypos(), height, window.getScreenHeight()), 0);
            var events = new ArrayList<double[]>();
            double[] event;
            while ((event = INPUT.poll()) != null) events.add(event);
            double[] packed = new double[events.size() * 4];
            for (int i = 0; i < events.size(); i++) System.arraycopy(events.get(i), 0, packed, i * 4, 4);
            String action = frame(Workbench.INSTANCE.snapshot(), width, height, (float)width / Math.max(1, window.getScreenWidth()), packed, graphics, OutlineProjection.frame(width, height), panel);
            if (!rendered) {
                rendered = true;
                org.slf4j.LoggerFactory.getLogger("Seedy").info("Dear ImGui Vulkan overlay rendered inside Minecraft");
            }
            if (action != null && !action.isEmpty()) client.execute(() -> Workbench.INSTANCE.action(action));
        } catch (Exception | LinkageError e) { fail(e); }
    }

    private static void fail(Throwable error) {
        failed = true;
        var client = Minecraft.getInstance();
        client.execute(() -> {
            if (client.gui.screen() instanceof OverlayScreen) client.gui.setScreen(null);
            client.gui.chatListener().handleSystemMessage(Component.literal("Seedy: " + error.getMessage()), false);
            org.slf4j.LoggerFactory.getLogger("Seedy").error("Native Vulkan overlay failed", error);
        });
    }

    public static String readClipboard() { return Minecraft.getInstance().keyboardHandler.getClipboard(); }
    public static void writeClipboard(String value) { Minecraft.getInstance().keyboardHandler.setClipboard(value); }
    public static void shutdown() { if (loaded) dispose(); }
}
