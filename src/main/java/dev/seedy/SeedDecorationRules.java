package dev.seedy;

import java.util.Map;
import java.util.Set;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;

record SeedDecorationRules(Map<String,Integer> salts, Set<String> blocked) {
    static final SeedDecorationRules VANILLA = new SeedDecorationRules(Map.of(),Set.of());
    SeedDecorationRules { salts = Map.copyOf(salts); blocked = Set.copyOf(blocked); }

    HolderLookup.RegistryLookup<StructureSet> placements(HolderLookup.Provider registries) {
        var original = registries.lookupOrThrow(Registries.STRUCTURE_SET);
        if (salts.isEmpty() && blocked.isEmpty()) return original;
        var changed = new MappedRegistry<StructureSet>(Registries.STRUCTURE_SET,com.mojang.serialization.Lifecycle.stable());
        original.listElements().forEach(holder -> {
            var key = holder.key(); var name = key.identifier().toString();
            if (blocked.contains(name)) return;
            var set = holder.value();
            if (salts.containsKey(name) && set.placement() instanceof RandomSpreadStructurePlacement spread) set = new StructureSet(set.structures(),PlacementRules.withSalt(spread,salts.get(name)));
            changed.register(key,set,RegistrationInfo.BUILT_IN);
        });
        return changed.freeze();
    }
}
