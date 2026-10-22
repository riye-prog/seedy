package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BedrockRecoveryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void rawOreCountsTrackChunkRefreshWithoutDuplicates() {
        var factory = PalettedContainerFactory.create((RegistryAccess)WorldgenAdapter.registries());
        var chunk = new ProtoChunk(new ChunkPos(3, -2), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), factory, null);
        var iron = new BlockPos(51, -30, -29);
        var copper = new BlockPos(52, 20, -28);
        var ordinaryOre = new BlockPos(53, -29, -27);
        var deepslateCopper = new BlockPos(54, 4, -26);
        chunk.setBlockState(iron, Blocks.RAW_IRON_BLOCK.defaultBlockState(), 0);
        chunk.setBlockState(copper, Blocks.RAW_COPPER_BLOCK.defaultBlockState(), 0);
        chunk.setBlockState(ordinaryOre, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 0);
        chunk.setBlockState(deepslateCopper, Blocks.DEEPSLATE_COPPER_ORE.defaultBlockState(), 0);
        var recovery = BedrockRecovery.INSTANCE;
        recovery.reset(1L);
        try {
            recovery.scan(chunk);
            assertEquals(1, recovery.scannedChunks());
            assertEquals(2, recovery.rawBlocks());
            assertEquals(1, recovery.rawChunks());
            assertEquals(1, recovery.oreCandidates());
            recovery.scan(chunk);
            assertEquals(2, recovery.rawBlocks());
            assertEquals(1, recovery.oreCandidates());
            chunk.setBlockState(iron, Blocks.STONE.defaultBlockState(), 0);
            recovery.scan(chunk);
            assertEquals(1, recovery.rawBlocks());
            assertEquals(1, recovery.rawChunks());
            chunk.setBlockState(copper, Blocks.STONE.defaultBlockState(), 0);
            recovery.scan(chunk);
            assertEquals(0, recovery.rawBlocks());
            assertEquals(0, recovery.rawChunks());
        } finally {
            recovery.reset(null);
        }
    }

    @Test void compactInputKeepsEveryBlockTheVeinHeuristicCanRead() {
        long raw = entry(-16, -30, 0, 1);
        long nearbyOre = entry(-12, -26, 0, 2);
        long nearbyFiller = entry(-20, -34, 4, 3);
        long distantOre = entry(-11, -30, 0, 2);
        long bedrock = entry(80, -62, 80, 0);
        long[] compact = BedrockRecovery.retainNearbyVeins(new long[]{raw, nearbyOre, nearbyFiller, distantOre, bedrock});
        assertArrayEquals(new long[]{raw, nearbyOre, nearbyFiller, bedrock}, compact);
        long[] sampled = BedrockRecovery.sampleAbundant(compact, 3);
        assertTrue(java.util.Arrays.stream(sampled).anyMatch(value -> value == raw));
        assertTrue(java.util.Arrays.stream(sampled).anyMatch(value -> value == nearbyOre));
        assertTrue(java.util.Arrays.stream(sampled).anyMatch(value -> value == nearbyFiller));
        assertArrayEquals(sampled, BedrockRecovery.sampleAbundant(compact, 3));
    }

    @Test void ordinaryDepositsDoNotCountAsVeinEvidence() {
        assertEquals(-1, BedrockRecovery.classify(Blocks.DEEPSLATE_COPPER_ORE, 4));
        assertEquals(-1, BedrockRecovery.classify(Blocks.DEEPSLATE_IRON_ORE, 4));
        assertEquals(-1, BedrockRecovery.classify(Blocks.COPPER_ORE, -14));
        assertEquals(-1, BedrockRecovery.classify(Blocks.GRANITE, -30));
        assertEquals(-1, BedrockRecovery.classify(Blocks.TUFF, 20));
        assertEquals(-1, BedrockRecovery.classify(Blocks.RAW_IRON_BLOCK, 20));
        assertEquals(-1, BedrockRecovery.classify(Blocks.RAW_COPPER_BLOCK, -30));
        assertEquals(1, BedrockRecovery.classify(Blocks.RAW_IRON_BLOCK, -60));
        assertEquals(1, BedrockRecovery.classify(Blocks.RAW_COPPER_BLOCK, 50));
        assertEquals(2, BedrockRecovery.classify(Blocks.DEEPSLATE_IRON_ORE, -8));
        assertEquals(2, BedrockRecovery.classify(Blocks.COPPER_ORE, 0));
        assertEquals(3, BedrockRecovery.classify(Blocks.TUFF, -30));
        assertEquals(3, BedrockRecovery.classify(Blocks.GRANITE, 20));
    }

    private static long entry(int x, int y, int z, int type) {
        long coordinateMask = (1L << 26) - 1;
        return ((long)x & coordinateMask) << 38 | ((long)z & coordinateMask) << 12 | ((long)(y + 64) & 255) << 4 | type;
    }
}
