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

import static org.junit.jupiter.api.Assertions.assertEquals;

class BedrockRecoveryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void rawOreCountsTrackChunkRefreshWithoutDuplicates() {
        var factory = PalettedContainerFactory.create((RegistryAccess)WorldgenAdapter.registries());
        var chunk = new ProtoChunk(new ChunkPos(3, -2), UpgradeData.EMPTY, LevelHeightAccessor.create(-64, 384), factory, null);
        var iron = new BlockPos(51, -30, -29);
        var copper = new BlockPos(52, 20, -28);
        var ordinaryOre = new BlockPos(53, -29, -27);
        chunk.setBlockState(iron, Blocks.RAW_IRON_BLOCK.defaultBlockState(), 0);
        chunk.setBlockState(copper, Blocks.RAW_COPPER_BLOCK.defaultBlockState(), 0);
        chunk.setBlockState(ordinaryOre, Blocks.DEEPSLATE_IRON_ORE.defaultBlockState(), 0);
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
}
