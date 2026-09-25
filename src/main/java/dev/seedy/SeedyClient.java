package dev.seedy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.resources.Identifier;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class SeedyClient implements ClientModInitializer {
    private static final ObservationCollector COLLECTOR = new ObservationCollector();
    private static Long sessionHash;
    private static KeyMapping toggle;
    private static int smokeTicks;
    private static boolean smokeOpened;
    public static boolean matchesToggle(KeyEvent event) { return toggle != null && toggle.matches(event); }
    public static boolean matchesToggle(MouseButtonEvent event) { return toggle != null && toggle.matchesMouse(event); }
    public static void rescan() { COLLECTOR.rescan(); }
    public static int pendingChunks() { return COLLECTOR.pending(); }
    @Override public void onInitializeClient() {
        toggle = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.seedy.toggle", InputConstants.KEY_F8, KeyMapping.Category.register(Identifier.fromNamespaceAndPath("seedy", "controls"))));
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registry) -> dispatcher.register(literal("seedy").executes(context -> {
            Minecraft.getInstance().execute(() -> Minecraft.getInstance().gui.setScreen(new OverlayScreen()));
            return 1;
        })));
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Workbench.INSTANCE.initialize();
            COLLECTOR.initialize();
            if (Boolean.getBoolean("seedy.lootSmoke") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment()) LootSmokeCheck.run(client);
        });
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) -> {
            LoadedObjects.loaded(world, chunk);
            if (world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) COLLECTOR.loaded(world, chunk.getPos(), Workbench.INSTANCE);
        });
        ClientChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> {
            LoadedObjects.unload(world, chunk.getPos());
            if (world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) COLLECTOR.unload(chunk.getPos());
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (!smokeOpened && Boolean.getBoolean("seedy.smoke") && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment() && client.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen && ++smokeTicks >= 20) { smokeOpened = true; client.gui.setScreen(new OverlayScreen()); }
            while (toggle.consumeClick()) {
                if (client.gui.screen() instanceof OverlayScreen) client.gui.setScreen(null);
                else if (client.gui.screen() == null && client.level != null) client.gui.setScreen(new OverlayScreen());
            }
            if (client.level != null) COLLECTOR.tick(client.level, Workbench.INSTANCE);
            Workbench.INSTANCE.tick(client);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            sessionHash = null;
            COLLECTOR.clear();
            LoadedObjects.clear();
            Workbench.INSTANCE.session(null);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { Workbench.INSTANCE.close(); NativeGui.shutdown(); });
    }
    public static void beginSession(long hash, boolean login) {
        if (login || sessionHash == null || sessionHash != hash) {
            sessionHash = hash;
            COLLECTOR.clear();
            LoadedObjects.clear();
            Workbench.INSTANCE.session(hash);
        }
    }
}
