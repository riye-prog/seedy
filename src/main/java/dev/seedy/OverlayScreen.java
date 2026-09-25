package dev.seedy;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class OverlayScreen extends Screen {
    public OverlayScreen() { super(Component.literal("Seedy")); NativeGui.event(5, 1, 0, 0); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) { }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void removed() { NativeGui.event(5, 0, 0, 0); }
    @Override public void mouseMoved(double x, double y) {
        var window = Minecraft.getInstance().getWindow();
        NativeGui.event(0, framebufferCoordinate(x, window.getWidth(), window.getGuiScaledWidth()), framebufferCoordinate(y, window.getHeight(), window.getGuiScaledHeight()), 0);
    }
    static double framebufferCoordinate(double position, int pixels, int scaledSize) { return position * pixels / Math.max(1, scaledSize); }
    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (SeedyClient.matchesToggle(event)) { onClose(); return true; }
        mouseMoved(event.x(), event.y()); modifiers(event.modifiers()); NativeGui.event(1, mapMouseButton(event.button()), 1, 0); return true;
    }
    @Override public boolean mouseReleased(MouseButtonEvent event) { mouseMoved(event.x(), event.y()); modifiers(event.modifiers()); NativeGui.event(1, mapMouseButton(event.button()), 0, 0); return true; }
    @Override public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) { mouseMoved(event.x(), event.y()); return true; }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) { NativeGui.event(2, horizontal, vertical, 0); return true; }
    @Override public boolean charTyped(CharacterEvent event) { NativeGui.event(4, event.codepoint(), 0, 0); return true; }
    @Override public boolean keyPressed(KeyEvent event) {
        if (event.key() == InputConstants.KEY_ESCAPE || SeedyClient.matchesToggle(event)) { onClose(); return true; }
        modifiers(event.modifiers());
        NativeGui.event(3, mapKey(event.key()), 1, 0);
        return true;
    }
    @Override public boolean keyReleased(KeyEvent event) { modifiers(event.modifiers()); NativeGui.event(3, mapKey(event.key()), 0, 0); return true; }
    private static void modifiers(int modifiers) {
        NativeGui.event(3, 11, (modifiers & InputConstants.MOD_CONTROL) != 0 ? 1 : 0, 0);
        NativeGui.event(3, 12, (modifiers & InputConstants.MOD_SHIFT) != 0 ? 1 : 0, 0);
        NativeGui.event(3, 18, (modifiers & InputConstants.MOD_SUPER) != 0 ? 1 : 0, 0);
        NativeGui.event(3, 19, (modifiers & InputConstants.MOD_ALT) != 0 ? 1 : 0, 0);
    }
    static int mapMouseButton(int button) {
        if (button == InputConstants.MOUSE_BUTTON_LEFT) return 0;
        if (button == InputConstants.MOUSE_BUTTON_RIGHT) return 1;
        if (button == InputConstants.MOUSE_BUTTON_MIDDLE) return 2;
        if (button == InputConstants.MOUSE_BUTTON_4) return 3;
        if (button == InputConstants.MOUSE_BUTTON_5) return 4;
        return -1;
    }
    static int mapKey(int key) {
        if (key == InputConstants.KEY_TAB) return 1;
        if (key == InputConstants.KEY_LEFT) return 2;
        if (key == InputConstants.KEY_RIGHT) return 3;
        if (key == InputConstants.KEY_UP) return 4;
        if (key == InputConstants.KEY_DOWN) return 5;
        if (key == InputConstants.KEY_BACKSPACE) return 6;
        if (key == InputConstants.KEY_DELETE) return 7;
        if (key == InputConstants.KEY_RETURN) return 8;
        if (key == InputConstants.KEY_HOME) return 9;
        if (key == InputConstants.KEY_END) return 10;
        if (key == InputConstants.KEY_LCONTROL || key == InputConstants.KEY_RCONTROL) return 11;
        if (key == InputConstants.KEY_LSHIFT || key == InputConstants.KEY_RSHIFT) return 12;
        if (key == InputConstants.KEY_A) return 13;
        if (key == InputConstants.KEY_C) return 14;
        if (key == InputConstants.KEY_V) return 15;
        if (key == InputConstants.KEY_X) return 16;
        if (key == InputConstants.KEY_Z) return 17;
        if (key == InputConstants.KEY_Y) return 20;
        return 0;
    }
}
