package dev.seedy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

public final class McoptLoaderProbe {
    public static void main(String[] arguments) throws Exception {
        var loader = new Knot(EnvType.CLIENT).init(new String[]{"--gameDir",arguments[0],"--version",arguments[1]});
        boolean expected = Boolean.getBoolean("seedy.expectMcopt");
        if (expected) {
            var encoder = Class.forName("mcopt.metal.MetalEncoder",false,loader);
            if (Arrays.stream(encoder.getDeclaredMethods()).noneMatch(method -> method.getName().contains("seedyBeforePresent"))) throw new IllegalStateException("mcopt encoder hook was not applied");
            var device = Class.forName("mcopt.metal.MetalDevice",false,loader);
            for (String hook : new String[]{"seedyMetalReady","seedyMetalClosed"}) if (Arrays.stream(device.getDeclaredMethods()).noneMatch(method -> method.getName().contains(hook))) throw new IllegalStateException(hook + " was not applied");
            var view = Class.forName("mcopt.metal.MetalTexture$View",false,loader);
            var accessor = Class.forName("dev.seedy.mixin.McoptTextureAccess",false,loader);
            if (!accessor.isAssignableFrom(view)) throw new IllegalStateException("mcopt texture accessor was not applied");
        } else Class.forName("dev.seedy.NativeGui",false,loader);
        String prefix = arguments[1].equals("26.3") ? "com.mojang.renderpearl.backend.vulkan." : "com.mojang.blaze3d.vulkan.";
        var vulkanEncoder = Class.forName(prefix + "VulkanCommandEncoder",false,loader);
        var vulkanAccessor = Class.forName("dev.seedy.mixin.VulkanEncoderAccessor",false,loader);
        if (!vulkanAccessor.isAssignableFrom(vulkanEncoder)) throw new IllegalStateException("Vulkan encoder accessor was not applied");
        var surface = Class.forName(prefix + "VulkanGpuSurface",false,loader);
        if (Arrays.stream(surface.getDeclaredMethods()).noneMatch(method -> method.getName().contains("seedyBeforeBlit"))) throw new IllegalStateException("Vulkan surface hook was not applied");
        String result = "{\"mcopt\":" + expected + ",\"fabric_mixin_application\":true,\"minecraft_main_invoked\":false}";
        Files.writeString(Path.of(arguments[2]),result + "\n");
        System.out.println(result);
    }
}
