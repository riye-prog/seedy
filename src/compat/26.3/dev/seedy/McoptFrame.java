package dev.seedy;

import com.mojang.renderpearl.api.textures.GpuTextureView;
import dev.seedy.mixin.McoptTextureAccess;

public final class McoptFrame {
    public static void render(long encoder, Object view) {
        try {
            var texture = (GpuTextureView)view;
            var graphics = McoptBridge.graphics(encoder,((McoptTextureAccess)view).seedyTexture());
            NativeGui.render(graphics,texture.getWidth(0),texture.getHeight(0));
        } catch (Exception | LinkageError error) { NativeGui.fail(error); }
    }
}
