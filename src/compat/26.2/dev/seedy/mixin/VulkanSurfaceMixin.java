package dev.seedy.mixin;

import dev.seedy.NativeGui;
import com.mojang.blaze3d.vulkan.VulkanGpuSurface;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
import com.mojang.blaze3d.vulkan.VulkanConst;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.vulkan.VK;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanGpuSurface.class)
public abstract class VulkanSurfaceMixin {
    @Shadow @Final private VulkanDevice device;

    @Inject(method = "blitFromTexture", at = @At("HEAD"))
    private void seedyBeforeBlit(CommandEncoderBackend encoder, GpuTextureView texture, CallbackInfo callback) {
        NativeGui.vulkanAvailable();
        if (!NativeGui.wantsFrame()) return;
        ((VulkanCommandEncoder)encoder).submit();
        var view = (VulkanGpuTextureView)texture;
        var queue = device.graphicsQueue();
        NativeGui.render(new long[]{VK.getFunctionProvider().getFunctionAddress("vkGetInstanceProcAddr"), device.instance().vkInstance().address(), device.vkDevice().getPhysicalDevice().address(), device.vkDevice().address(), queue.vkQueue().address(), queue.queueFamilyIndex(), view.texture().vkImage(), view.vkImageView(), VulkanConst.toVk(view.texture().getFormat())}, view.getWidth(0), view.getHeight(0));
    }
}
