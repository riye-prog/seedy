package dev.seedy.mixin;

import dev.seedy.NativeGui;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSurface;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.lwjgl.vulkan.VK;
import org.lwjgl.vulkan.VK12;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanSurfaceMixin {
    @Shadow @Final private VulkanDevice device;
    @Unique private long seedyLastSubmit = -1;
    @Unique private long[] seedyGraphics;

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void seedyBeforeBlit(CommandEncoderBackend encoder, GpuTextureView texture, CallbackInfo callback) {
        NativeGui.vulkanAvailable();
        if (!NativeGui.wantsFrame()) return;
        var vulkan = (VulkanCommandEncoder)encoder;
        long submission = ((VulkanEncoderAccessor)encoder).seedySubmitIndex();
        if (seedyLastSubmit == submission) return;
        seedyLastSubmit = submission;
        var view = (VulkanGpuTextureView)texture;
        if (seedyGraphics == null) {
            var queue = device.graphicsQueue();
            seedyGraphics = new long[]{VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"), device.instance().vkInstance().address(), device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(), queue.vkQueue().address(), queue.queueFamilyIndex(), 0, 0, 0, 0};
        }
        var commands = vulkan.allocateAndBeginTransientCommandBuffer();
        seedyGraphics[6] = view.texture().vkImage();
        seedyGraphics[7] = view.vkImageView();
        seedyGraphics[8] = VulkanConst.toVk(view.texture().getFormat());
        seedyGraphics[9] = commands.address();
        NativeGui.render(seedyGraphics, view.getWidth(0), view.getHeight(0));
        int result = VK12.vkEndCommandBuffer(commands);
        if (result != VK12.VK_SUCCESS) throw new IllegalStateException("Could not finish Seedy commands: " + result);
        vulkan.execute(commands);
    }
}
