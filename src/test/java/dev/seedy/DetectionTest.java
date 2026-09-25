package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class DetectionTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    private static Map<BlockPos, Block> place(TemplateSignatures.Layout layout, Rotation rotation, BlockPos pivot, BlockPos origin) {
        var blocks = new HashMap<BlockPos, Block>();
        for (var sample : layout.blocks()) blocks.put(origin.offset(StructureTemplate.transform(sample.position(), Mirror.NONE, rotation, pivot)), sample.block());
        return blocks;
    }

    @Test void everyShipwreckTemplateAndRotationIdentifiesItsStartChunk() {
        var signatures = new TemplateSignatures();
        var origin = new BlockPos(-32, 47, 48);
        for (var pattern : signatures.shipPatterns()) {
            var blocks = place(TemplateSignatures.load(pattern.name()), pattern.rotation(), new BlockPos(4, 0, 15), origin);
            var chest = origin.offset(pattern.anchor());
            assertEquals(java.util.Set.of(new ChunkPos(-2, 3)), signatures.shipwrecks(p -> blocks.getOrDefault(p, Blocks.AIR), chest), pattern.name() + " " + pattern.rotation());
        }
    }

    @Test void bothTrialStartRoomsAndAllRotationsIdentifyOnlyTheirStartChunk() {
        var signatures = new TemplateSignatures();
        for (var pattern : signatures.chamberPatterns()) {
            var origin = new BlockPos(-48, -31, -32);
            var blocks = place(TemplateSignatures.load(pattern.name()), pattern.rotation(), BlockPos.ZERO, origin);
            TemplateSignatures.BlocksView view = p -> blocks.getOrDefault(p, Blocks.AIR);
            assertTrue(signatures.trialChamber(view, new ChunkPos(-3, -2)), pattern.name() + " " + pattern.rotation());
            assertFalse(signatures.trialChamber(view, new ChunkPos(-2, -2)));
            assertFalse(signatures.trialChamber(view, new ChunkPos(-3, -1)));
        }
    }

    @Test void MissingBlocksAndUnloadedNeighborsDoNotProduceEvidence() {
        var signatures = new TemplateSignatures();
        assertFalse(signatures.trialChamber(p -> null, new ChunkPos(0,0)));
        assertTrue(signatures.shipwrecks(p -> Blocks.AIR, new BlockPos(4,40,15)).isEmpty());
        for (var pattern : signatures.chamberPatterns()) assertFalse(pattern.matches(p -> Blocks.TUFF_BRICKS, new BlockPos(0,-31,0)));
    }
}
