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
    private static KeyMapping toggle, hudToggle, miniToggle;
    private static int smokeTicks;
    private static boolean smokeOpened;
    public static boolean matchesToggle(KeyEvent event) { return toggle != null && toggle.matches(event); }
    public static boolean matchesToggle(MouseButtonEvent event) { return toggle != null && toggle.matchesMouse(event); }
    public static boolean matchesHudToggle(KeyEvent event) { return hudToggle != null && hudToggle.matches(event); }
    public static void rescan() { COLLECTOR.rescan(); BedrockRecovery.INSTANCE.rescan(); }
    public static int pendingChunks() { return COLLECTOR.pending(); }
    record Controls(KeyMapping panel, KeyMapping overlay, KeyMapping minimap) { }
    static Controls createControls() {
        var category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("seedy", "controls"));
        return new Controls(
            new KeyMapping("key.seedy.toggle", InputConstants.KEY_F8, category),
            new KeyMapping("key.seedy.hud", InputConstants.KEY_F9, category),
            new KeyMapping("key.seedy.minimap",InputConstants.KEY_F10,category));
    }
    @Override public void onInitializeClient() {
        var controls = createControls();
        toggle = KeyMappingHelper.registerKeyMapping(controls.panel());
        hudToggle = KeyMappingHelper.registerKeyMapping(controls.overlay());
        miniToggle = KeyMappingHelper.registerKeyMapping(controls.minimap());
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
            NetherBedrockRecovery.INSTANCE.loaded(world, chunk.getPos());
            if (world.dimension().equals(net.minecraft.world.level.Level.OVERWORLD)) {
                COLLECTOR.loaded(world, chunk.getPos(), Workbench.INSTANCE);
                BedrockRecovery.INSTANCE.loaded(world, chunk.getPos());
            }
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
            while (hudToggle.consumeClick()) if (client.level != null) Workbench.INSTANCE.toggleHud();
            while (miniToggle.consumeClick()) if (client.level != null) Workbench.INSTANCE.toggleMinimap();
            if (client.level != null && !Workbench.INSTANCE.recovering()) COLLECTOR.tick(client.level, Workbench.INSTANCE);
            Workbench.INSTANCE.tick(client);
            BedrockRecovery.INSTANCE.tick(client);
            NetherBedrockRecovery.INSTANCE.tick(client);
            int frameLimit = client.getFramerateLimitTracker().getFramerateLimit();
            dev.seedy.recovery.RecoveryWorkload.frameLimit(frameLimit >= net.minecraft.client.Options.UNLIMITED_FRAMERATE_CUTOFF ? 0 : frameLimit);
            dev.seedy.recovery.RecoveryWorkload.active(Workbench.INSTANCE.recovering() || Workbench.INSTANCE.mapping());
            BedrockRecovery.updateWorkBudget();
            NetherBedrockRecovery.updateWorkBudget();
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            sessionHash = null;
            COLLECTOR.clear();
            BedrockRecovery.INSTANCE.reset(null);
            NetherBedrockRecovery.INSTANCE.reset(null);
            LoadedObjects.clear();
            Workbench.INSTANCE.session(null);
        });
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> { Workbench.INSTANCE.close(); BedrockRecovery.INSTANCE.close(); NetherBedrockRecovery.INSTANCE.close(); NativeGui.shutdown(); });
    }
    public static void beginSession(long hash, boolean login) {
        if (login || sessionHash == null || sessionHash != hash) {
            sessionHash = hash;
            COLLECTOR.clear();
            BedrockRecovery.INSTANCE.reset(hash);
            NetherBedrockRecovery.INSTANCE.reset(hash);
            LoadedObjects.clear();
            Workbench.INSTANCE.session(hash);
        }
    }
}
