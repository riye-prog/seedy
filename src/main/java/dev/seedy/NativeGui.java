package dev.seedy;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class NativeGui {
    private static final ConcurrentLinkedQueue<double[]> INPUT = new ConcurrentLinkedQueue<>();
    private static boolean loaded;
    private static boolean failed;
    private static boolean rendered;
    private static boolean rendererSeen;
    private static native String frame(String state, int width, int height, float density, double[] events, long[] graphics, double[] lines, boolean panel, boolean hud);
    private static native void dispose();
    private static native void mapData(int channel, String data);
    private static native void mapPosition(double x, double z, float yaw);

    public static void event(int kind, double a, double b, double c) {
        if (INPUT.size() < 1024) INPUT.add(new double[]{kind, a, b, c});
    }

    private static void load() throws Exception {
        if (loaded) return;
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT), arch = System.getProperty("os.arch");
        String target = os.contains("mac") ? "macos-universal/libseedy.dylib" : os.contains("win") && (arch.equals("amd64") || arch.equals("x86_64")) ? "windows-x86_64/seedy.dll" : null;
        if (target == null) throw new IllegalStateException("The native panel supports macOS and Windows x64.");
        String platform = target.substring(0, target.indexOf('/'));
        if (platform.equals("windows-x86_64")) for (String dependency : new String[]{"libgcc_s_seh-1.dll", "libwinpthread-1.dll", "libstdc++-6.dll"}) NativeLibraries.loadIfPresent(platform, dependency);
        NativeLibraries.load(platform, target.substring(target.indexOf('/') + 1));
        loaded = true;
    }

    private static final double[] NO_INPUT = new double[0];
    private static String sentSnapshot;


    public static boolean wantsFrame() {
        return !failed && (Minecraft.getInstance().gui.screen() instanceof OverlayScreen || hudVisible() || LoadedObjects.active() || loaded && !INPUT.isEmpty());
    }

    private static boolean hudVisible() {
        var client = Minecraft.getInstance();
        return client.level != null && client.gui.screen() == null && !client.gui.hud.isHidden() && Workbench.INSTANCE.overlaysEnabled();
    }

    public static void vulkanAvailable() { rendererSeen = true; }
    public static void metalAvailable() { rendererSeen = true; }

    public static void checkBackend() {
        if (!rendererSeen && !failed && (Minecraft.getInstance().gui.screen() instanceof OverlayScreen || hudVisible())) fail(new IllegalStateException("Use Vulkan or mcopt's Metal renderer and restart the game to use Seedy's panel."));
    }

    public static void render(long[] graphics, int width, int height) {
        if (!wantsFrame()) return;
        var client = Minecraft.getInstance();
        try {
            load();
            var window = client.getWindow();
            boolean panel = client.gui.screen() instanceof OverlayScreen;
            if (panel) event(0, OverlayScreen.framebufferCoordinate(client.mouseHandler.xpos(), width, window.getScreenWidth()), OverlayScreen.framebufferCoordinate(client.mouseHandler.ypos(), height, window.getScreenHeight()), 0);
            double[] packed = NO_INPUT;
            if (!INPUT.isEmpty()) {
                var events = new ArrayList<double[]>();
                double[] event;
                while ((event = INPUT.poll()) != null) events.add(event);
                packed = new double[events.size() * 4];
                for (int i = 0; i < events.size(); i++) System.arraycopy(events.get(i), 0, packed, i * 4, 4);
            }
            for (int channel = panel ? 0 : 1; channel < 2; ++channel) for (int i = 0; i < 2; ++i) {
                String data = Workbench.INSTANCE.mapSnapshot(channel);
                if (data == null) break;
                mapData(channel,data);
            }
            for (int i = 0; i < 4; ++i) { String data = Workbench.INSTANCE.terrainUpdate(); if (data == null) break; mapData(2,data); }
            if (client.player != null) {
                float partial = client.getDeltaTracker().getGameTimeDeltaPartialTick(true);
                var position = client.player.getPosition(partial);
                mapPosition(position.x(),position.z(),client.player.getYRot(partial));
            }
            String snapshot = panel ? Workbench.INSTANCE.snapshot() : Workbench.INSTANCE.hudSnapshot();
            String action = frame(snapshot == sentSnapshot ? null : snapshot, width, height, (float)width / Math.max(1, window.getScreenWidth()), packed, graphics, OutlineProjection.frame(width, height), panel, hudVisible());
            sentSnapshot = snapshot;
            if (!rendered) {
                rendered = true;
                org.slf4j.LoggerFactory.getLogger("Seedy").info("Dear ImGui {} overlay rendered inside Minecraft",graphics.length == 3 ? "mcopt Metal" : "Vulkan");
            }
            if (action != null && !action.isEmpty()) client.execute(() -> Workbench.INSTANCE.action(action));
        } catch (Exception | LinkageError e) { fail(e); }
    }

    public static void fail(Throwable error) {
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
    public static void shutdown() { sentSnapshot = null; if (loaded) dispose(); }
}
