package dev.seedy.mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import org.spongepowered.asm.mixin.Mixin;
import java.util.List;

@Mixin(EndCityPieces.class)
public abstract class EndCityMixin {
    @WrapMethod(method = "startHouseTower")
    private static void seedySerializeGeneration(StructureTemplateManager templates, BlockPos position, Rotation rotation, List<StructurePiece> pieces, RandomSource random, Operation<Void> original) {
        synchronized (EndCityPieces.class) { original.call(templates, position, rotation, pieces, random); }
    }
}
