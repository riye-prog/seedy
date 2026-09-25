package dev.seedy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdStructure;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(StrongholdStructure.class)
public abstract class StrongholdMixin {
    @WrapMethod(method = "generatePieces")
    private static void seedySerializeGeneration(StructurePiecesBuilder builder, Structure.GenerationContext context, Operation<Void> original) {
        synchronized (StrongholdStructure.class) { original.call(builder, context); }
    }
}
