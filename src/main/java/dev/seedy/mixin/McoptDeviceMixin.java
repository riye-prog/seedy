package dev.seedy.mixin;

import dev.seedy.NativeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mcopt.metal.MetalDevice", remap = false)
public abstract class McoptDeviceMixin {
    @Inject(method = "<init>", at = @At("RETURN"))
    private void seedyMetalReady(CallbackInfo callback) { NativeGui.metalAvailable(); }

    @Inject(method = "close", at = @At("RETURN"))
    private void seedyMetalClosed(CallbackInfo callback) { NativeGui.shutdown(); }
}
