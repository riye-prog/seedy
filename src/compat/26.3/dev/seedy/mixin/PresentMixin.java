package dev.seedy.mixin;

import dev.seedy.NativeGui;
import dev.seedy.recovery.RecoveryWorkload;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class PresentMixin {
    @Inject(method = "renderFrame", at = @At("HEAD"))
    private void seedyFrameStarted(CallbackInfo callback) { RecoveryWorkload.frameStarted(); }

    @Inject(method = "renderFrame", at = @At("RETURN"))
    private void seedyFrameFinished(CallbackInfo callback) { RecoveryWorkload.frameFinished(); }

    @Inject(method = "renderFrame", at = @At(value = "INVOKE", target = "Lcom/mojang/renderpearl/api/device/GpuSurface;present()V"))
    private void seedyPresent(CallbackInfo callback) { NativeGui.checkBackend(); }
}
