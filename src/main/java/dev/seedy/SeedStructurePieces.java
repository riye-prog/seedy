package dev.seedy;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RailBlock;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.levelgen.structure.pieces.PiecesContainer;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;
import net.minecraft.world.level.levelgen.structure.structures.MineshaftPieces;
import net.minecraft.world.level.levelgen.structure.structures.OceanMonumentPieces;
import net.minecraft.world.level.levelgen.structure.structures.SwampHutPiece;
import net.minecraft.world.level.levelgen.structure.structures.EndCityPieces;
import net.minecraft.world.level.levelgen.structure.structures.WoodlandMansionPieces;
import net.minecraft.world.level.storage.loot.LootTable;

final class SeedStructurePieces {
    private static final Field MONUMENT_CHILDREN = field(OceanMonumentPieces.MonumentBuilding.class,"childPieces");
    private static final Field WING_DESIGN = field(OceanMonumentPieces.OceanMonumentWingRoom.class,"mainDesign");

    private static Field field(Class<?> owner, String name) {
        try { var field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException failure) { throw new IllegalStateException("Unsupported structure piece layout",failure); }
    }

    static StructureStart withoutEntities(StructureStart start, StructurePieceSerializationContext context) {
        if (!start.isValid()) return start;
        var pieces = new ArrayList<StructurePiece>();
        for (var piece : start.getPieces()) {
            if (piece instanceof MineshaftPieces.MineShaftCorridor) piece = new Corridor(piece.createTag(context));
            else if (piece instanceof EndCityPieces.EndCityPiece) piece = new City(context.structureTemplateManager(),piece.createTag(context));
            else if (piece instanceof WoodlandMansionPieces.WoodlandMansionPiece) piece = new Mansion(context.structureTemplateManager(),piece.createTag(context));
            else if (piece instanceof SwampHutPiece) {
                var tag = piece.createTag(context); tag.putBoolean("Witch",true); tag.putBoolean("Cat",true); piece = new SwampHutPiece(tag);
            } else if (piece instanceof OceanMonumentPieces.MonumentBuilding) {
                try {
                    var children = (List<StructurePiece>)MONUMENT_CHILDREN.get(piece);
                    for (int i = 0; i < children.size(); ++i) {
                        var child = children.get(i);
                        if (child instanceof OceanMonumentPieces.OceanMonumentWingRoom) children.set(i,new Wing(child.getOrientation(),child.getBoundingBox(),WING_DESIGN.getInt(child)));
                        else if (child instanceof OceanMonumentPieces.OceanMonumentPenthouse) children.set(i,new Penthouse(child.getOrientation(),child.getBoundingBox()));
                    }
                } catch (IllegalAccessException failure) { throw new IllegalStateException("Could not prepare monument blocks",failure); }
            }
            pieces.add(piece);
        }
        return new StructureStart(start.getStructure(),start.getChunkPos(),start.getReferences(),new PiecesContainer(pieces));
    }

    private static final class Corridor extends MineshaftPieces.MineShaftCorridor {
        Corridor(CompoundTag tag) { super(tag); }
        @Override protected boolean createChest(WorldGenLevel level, BoundingBox bounds, RandomSource random, int x, int y, int z, ResourceKey<LootTable> loot) {
            var position = getWorldPos(x,y,z);
            if (!bounds.isInside(position) || !level.getBlockState(position).isAir() || level.getBlockState(position.below()).isAir()) return false;
            var rail = Blocks.RAIL.defaultBlockState().setValue(RailBlock.SHAPE,random.nextBoolean() ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST);
            placeBlock(level,rail,x,y,z,bounds); random.nextLong(); return true;
        }
    }

    private static final class Wing extends OceanMonumentPieces.OceanMonumentWingRoom {
        Wing(net.minecraft.core.Direction orientation, BoundingBox bounds, int design) { super(orientation,bounds,design); }
        @Override protected void spawnElder(WorldGenLevel level, BoundingBox bounds, int x, int y, int z) { }
    }

    private static final class Penthouse extends OceanMonumentPieces.OceanMonumentPenthouse {
        Penthouse(net.minecraft.core.Direction orientation, BoundingBox bounds) { super(orientation,bounds); }
        @Override protected void spawnElder(WorldGenLevel level, BoundingBox bounds, int x, int y, int z) { }
    }

    private static final class City extends EndCityPieces.EndCityPiece {
        City(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates, CompoundTag tag) { super(templates,tag); }
        @Override protected void handleDataMarker(String name, BlockPos pos, net.minecraft.world.level.ServerLevelAccessor level, RandomSource random, BoundingBox bounds) {
            if (name.startsWith("Chest")) super.handleDataMarker(name,pos,level,random,bounds);
        }
    }

    private static final class Mansion extends WoodlandMansionPieces.WoodlandMansionPiece {
        Mansion(net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager templates, CompoundTag tag) { super(templates,tag); }
        @Override protected void handleDataMarker(String name, BlockPos pos, net.minecraft.world.level.ServerLevelAccessor level, RandomSource random, BoundingBox bounds) {
            if (name.startsWith("Chest")) super.handleDataMarker(name,pos,level,random,bounds);
            else {
                if (name.equals("Group of Allays")) level.getRandom().nextInt(3);
                level.setBlock(pos,Blocks.AIR.defaultBlockState(),2);
            }
        }
    }
}
