package dev.seedy;

import dev.seedy.recovery.PlacementRule;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadType;
import net.minecraft.core.Vec3i;
import java.util.Optional;

public final class PlacementRules {
    public static PlacementRule read(String id, RandomSpreadStructurePlacement placement) {
        try {
            var salt = RandomSpreadStructurePlacement.class.getSuperclass().getDeclaredField("salt");
            salt.setAccessible(true);
            return new PlacementRule(id, placement.spacing(), placement.separation(), salt.getInt(placement), placement.spreadType() == RandomSpreadType.TRIANGULAR);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("This Minecraft version's placement metadata is unsupported.", e); }
    }

    static RandomSpreadStructurePlacement withSalt(RandomSpreadStructurePlacement placement, int salt) {
        try {
            var type = placement.getClass().getSuperclass();
            var locateOffset = type.getDeclaredField("locateOffset");
            var frequencyMethod = type.getDeclaredField("frequencyReductionMethod");
            var frequency = type.getDeclaredField("frequency");
            var exclusionZone = type.getDeclaredField("exclusionZone");
            locateOffset.setAccessible(true);
            frequencyMethod.setAccessible(true);
            frequency.setAccessible(true);
            exclusionZone.setAccessible(true);
            var constructor = RandomSpreadStructurePlacement.class.getDeclaredConstructor(Vec3i.class, frequencyMethod.getType(), float.class, int.class, Optional.class, int.class, int.class, RandomSpreadType.class);
            return constructor.newInstance(locateOffset.get(placement), frequencyMethod.get(placement), frequency.getFloat(placement), salt, exclusionZone.get(placement), placement.spacing(), placement.separation(), placement.spreadType());
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("This Minecraft version's placement metadata is unsupported.", e); }
    }
}
