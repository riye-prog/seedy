package dev.seedy.mixin;

import dev.seedy.LoadedObjects;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(LevelChunk.class)
public abstract class ChunkBlocksMixin {
    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void seedyBlockChanged(BlockPos position, BlockState state, int flags, CallbackInfoReturnable<BlockState> callback) {
        var chunk = (LevelChunk)(Object)this;
        if (callback.getReturnValue() != null && chunk.getLevel() instanceof ClientLevel world) LoadedObjects.changed(world, position);
    }
}
