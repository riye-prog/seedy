package dev.seedy;

import com.google.gson.JsonParser;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.LootPool;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

final class LootEvaluator {
    private final RegistryAccess registries;
    private final Map<String, List<LootPool>> poolsByTable = new java.util.HashMap<>();
    private final TreeSet<String> items = new TreeSet<>();
    private final java.lang.reflect.Constructor<LootContext> contextConstructor;
    private final java.lang.reflect.Method availableSlots;
    private final java.lang.reflect.Method shuffle;

    LootEvaluator(RegistryAccess registries) {
        this.registries = registries;
        try {
            contextConstructor = LootContext.class.getDeclaredConstructor(LootParams.class, RandomSource.class, HolderGetter.Provider.class);
            contextConstructor.setAccessible(true);
            availableSlots = LootTable.class.getDeclaredMethod("getAvailableSlots", net.minecraft.world.Container.class, RandomSource.class);
            availableSlots.setAccessible(true);
            shuffle = LootTable.class.getDeclaredMethod("shuffleAndSplitItems", ObjectArrayList.class, int.class, RandomSource.class);
            shuffle.setAccessible(true);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("This version's loot API is not supported", e); }
        try (var resources = WorldgenAdapter.resources()) {
            for (String name : List.of("ancient_city", "ancient_city_ice_box", "desert_pyramid")) {
                try (var reader = resources.getResourceOrThrow(Identifier.withDefaultNamespace("loot_table/chests/" + name + ".json")).openAsReader()) {
                    poolsByTable.put("minecraft:chests/" + name, new LootTableAdapter(registries, items).build(JsonParser.parseReader(reader).getAsJsonObject()));
                }
            }
        } catch (java.io.IOException e) { throw new IllegalStateException("Could not read vanilla chest loot tables", e); }
    }

    List<String> items() { return List.copyOf(items); }

    LootContext context(long seed) {
        if (seed == 0) throw new IllegalArgumentException("This chest has an unseeded loot table and cannot be predicted from the world seed.");
        try {
            var params = new LootParams(null, LootTableAdapter.emptyContext(), Map.of(), 0);
            return contextConstructor.newInstance(params, RandomSource.create(seed), registries);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not create a seeded loot context", e); }
    }

    List<ItemStack> stacks(String tableId, long seed) {
        var pools = poolsByTable.get(tableId);
        if (pools == null) throw new IllegalArgumentException("Unsupported chest loot table: " + tableId);
        var context = context(seed);
        var stacks = new ObjectArrayList<ItemStack>();
        java.util.function.Consumer<ItemStack> collect = stack -> {
            if (stack.isEmpty()) return;
            for (int remaining = stack.getCount(); remaining > 0;) {
                int count = Math.min(remaining, stack.getMaxStackSize());
                stacks.add(stack.copyWithCount(count));
                remaining -= count;
            }
        };
        for (var pool : pools) pool.addRandomItems(collect, context);
        try {
            var random = context.getRandom();
            var slots = (List<?>)availableSlots.invoke(LootTable.EMPTY, new SimpleContainer(27), random);
            shuffle.invoke(LootTable.EMPTY, stacks, slots.size(), random);
            return stacks.stream().limit(slots.size()).toList();
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Could not arrange predicted chest contents", e); }
    }

    List<ChestLoot.Item> evaluate(String table, long seed) { return describe(stacks(table, seed)); }

    static List<ChestLoot.Item> describe(List<ItemStack> stacks) {
        var counts = new java.util.LinkedHashMap<String, ChestLoot.Item>();
        for (var stack : stacks) {
            if (stack.isEmpty()) continue;
            String id = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            var details = new ArrayList<String>();
            for (var component : List.of(DataComponents.ENCHANTMENTS, DataComponents.STORED_ENCHANTMENTS)) {
                var enchantments = stack.get(component);
                if (enchantments != null) for (var entry : enchantments.entrySet()) details.add(Enchantment.getFullname(entry.getKey(), entry.getIntValue()).getString());
            }
            if (stack.isDamageableItem()) details.add((stack.getMaxDamage() - stack.getDamageValue()) + "/" + stack.getMaxDamage() + " durability");
            var potion = stack.get(DataComponents.POTION_CONTENTS);
            if (potion != null) for (var effect : potion.getAllEffects()) details.add(effect.getEffect().value().getDisplayName().getString() + " " + (effect.getAmplifier() + 1) + " (" + effect.getDuration() / 20 + "s)");
            var stew = stack.get(DataComponents.SUSPICIOUS_STEW_EFFECTS);
            if (stew != null) for (var effect : stew.effects()) details.add(effect.effect().value().getDisplayName().getString() + " (" + effect.duration() / 20 + "s)");
            String description = String.join(", ", details);
            String key = id + "|" + description;
            var previous = counts.get(key);
            counts.put(key, new ChestLoot.Item(id, stack.getHoverName().getString(), stack.getCount() + (previous == null ? 0 : previous.count()), description));
        }
        return List.copyOf(counts.values());
    }
}
