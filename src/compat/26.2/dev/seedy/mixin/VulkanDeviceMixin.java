package dev.seedy.mixin;

import dev.seedy.NativeGui;
import com.mojang.blaze3d.vulkan.VulkanDevice;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VulkanDevice.class)
public abstract class VulkanDeviceMixin {
    @Inject(method = "close", at = @At("HEAD"))
    private void seedyRelease(CallbackInfo callback) { NativeGui.shutdown(); }
}
