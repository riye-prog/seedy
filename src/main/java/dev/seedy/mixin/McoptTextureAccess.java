package dev.seedy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "mcopt.metal.MetalTexture$View", remap = false)
public interface McoptTextureAccess {
    @Accessor("handle") long seedyTexture();
}
