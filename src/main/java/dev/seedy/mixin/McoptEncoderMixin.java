package dev.seedy.mixin;

import dev.seedy.McoptFrame;
import dev.seedy.NativeGui;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "mcopt.metal.MetalEncoder", remap = false)
public abstract class McoptEncoderMixin {
    @Shadow @Final private long enc;
    @Shadow private long submitIndex;
    @Unique private long seedyLastSubmit = -1;

    @Inject(method = {"presentTexture", "presentAcquire"}, at = @At(value = "INVOKE", target = "Lmcopt/metal/MetalEncoder;flushClear(Lcom/mojang/renderpearl/api/textures/GpuTexture;)V", shift = At.Shift.AFTER))
    private void seedyBeforePresent(long output, @Coerce Object texture, CallbackInfo callback) {
        NativeGui.metalAvailable();
        if (!NativeGui.wantsFrame() || seedyLastSubmit == submitIndex) return;
        seedyLastSubmit = submitIndex;
        McoptFrame.render(enc,texture);
    }
}
