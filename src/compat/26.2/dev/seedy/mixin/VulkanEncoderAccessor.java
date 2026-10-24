package dev.seedy.mixin;

import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VulkanCommandEncoder.class)
public interface VulkanEncoderAccessor {
    @Accessor("currentSubmitIndex") long seedySubmitIndex();
    @Accessor("completedSubmitIndex") long seedyCompletedIndex();
    @Accessor("submitSemaphore") long seedySubmitSemaphore();
}
