package dev.seedy;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.level.levelgen.structure.placement.ConcentricRingsStructurePlacement;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdPieces;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.HashSet;
import static org.junit.jupiter.api.Assertions.*;

class WorldFeaturesTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void strongholdsFindGeneratedPortalRoomsAtVanillaRingPositions() {
        long seed = 2589511696370800371L;
        var registries = WorldgenAdapter.registries();
        var set = registries.lookupOrThrow(Registries.STRUCTURE_SET).listElements().filter(h -> h.key().identifier().toString().equals("minecraft:strongholds")).findFirst().orElseThrow().value();
        var world = WorldgenAdapter.structureWorld(registries, seed, "overworld");
        var rings = world.placements().getRingPositionsFor((ConcentricRingsStructurePlacement)set.placement());
        assertEquals(128, rings.size());
        var portals = new HashSet<BlockPos>();
        try (var generator = new VanillaStructures((RegistryAccess)registries); var locator = new Locator()) {
            assertTrue(locator.catalog().structures().contains("minecraft:strongholds"));
            for (var chunk : rings) {
                if (Math.hypot(chunk.x() * 16L + 8, chunk.z() * 16L + 8) > 4096) continue;
                var generated = generator.generate(set, world, seed, chunk);
                assertNotNull(generated);
                var room = generated.start().getPieces().stream().filter(piece -> piece instanceof StrongholdPieces.PortalRoom).findFirst().orElseThrow();
                portals.add(room.getBoundingBox().getCenter());
            }
            assertFalse(portals.isEmpty());
            var results = locator.search(seed, "structure", "minecraft:strongholds", 0, 64, 0, 4096);
            assertEquals(portals.size(), results.size());
            for (var result : results) {
                assertEquals("minecraft:stronghold", result.name());
                assertTrue(portals.contains(new BlockPos(result.x(), result.y(), result.z())));
                assertEquals("overworld", result.dimension());
            }
            System.out.println("Stronghold portal rooms " + SharedConstants.getCurrentVersion().name() + ": " + results);
        }
    }

    @Test void treasureSignatureRejectsOrdinaryWaterloggedAndDoubleChests() {
        var chest = Blocks.CHEST.defaultBlockState();
        var stone = Blocks.STONE.defaultBlockState();
        var position = new BlockPos(-7, 52, -23);
        assertTrue(LoadedObjects.treasureShape(position, chest, stone));
        assertFalse(LoadedObjects.treasureShape(position.offset(1,0,0), chest, stone));
        assertFalse(LoadedObjects.treasureShape(position, chest.setValue(ChestBlock.WATERLOGGED, true), stone));
        assertFalse(LoadedObjects.treasureShape(position, chest.setValue(ChestBlock.TYPE, ChestType.LEFT), stone));
        assertFalse(LoadedObjects.treasureShape(position, chest, Blocks.OAK_PLANKS.defaultBlockState()));
        assertFalse(LoadedObjects.treasureShape(position, Blocks.AIR.defaultBlockState(), stone));
    }

    @Test void outlinesClipBehindCameraAndAtViewportEdges() {
        var matrix = new Matrix4f().perspective((float)Math.toRadians(75), 16f / 9f, 0.05f, 1024);
        var lines = new ArrayList<Double>();
        OutlineProjection.box(matrix, -0.5, -0.5, -4, 1920, 1080, 0, lines);
        assertEquals(12 * 5, lines.size());
        lines.clear();
        OutlineProjection.box(matrix, 0, 0, 4, 1920, 1080, 0, lines);
        assertTrue(lines.isEmpty());
        OutlineProjection.box(matrix, -0.5, -0.5, -0.2, 1920, 1080, 1, lines);
        for (int i = 0; i < lines.size(); i += 5) {
            for (int j = 0; j < 4; j++) {
                double coordinate = lines.get(i + j);
                assertTrue(Double.isFinite(coordinate));
                assertTrue(coordinate >= -0.01 && coordinate <= (j % 2 == 0 ? 1920.01 : 1080.01));
            }
        }
        var a = new Vector4f(-2, 0, 0, 1);
        var b = new Vector4f(0, 0, 0, 1);
        assertTrue(OutlineProjection.clip(a, b));
        assertEquals(-1, a.x, 0.0001);
    }
}
