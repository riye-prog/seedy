package dev.seedy;

import dev.seedy.recovery.PlacementRule;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;

public final class PlacementRules {
    public static PlacementRule read(String id, RandomSpreadStructurePlacement placement) {
        try {
            var salt = RandomSpreadStructurePlacement.class.getSuperclass().getDeclaredField("salt");
            salt.setAccessible(true);
            return new PlacementRule(id, placement.spacing(), placement.separation(), salt.getInt(placement), placement.spreadType() == RandomSpreadType.TRIANGULAR);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("This Minecraft version's placement metadata is unsupported.", e); }
    }
}
