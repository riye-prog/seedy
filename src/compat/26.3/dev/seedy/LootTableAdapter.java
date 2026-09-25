package dev.seedy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.codec.RegistryCodecs;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.RegistryOps;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootPool;
import net.minecraft.world.level.storage.loot.entries.EmptyLootItem;
import net.minecraft.world.level.storage.loot.entries.LootItem;
import net.minecraft.world.level.storage.loot.entries.UniformContainerBase;
import net.minecraft.world.level.storage.loot.functions.EnchantWithLevelsFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctions;
import net.minecraft.world.level.storage.loot.providers.number.ints.ContextIntProviders;
import net.minecraft.world.level.storage.loot.providers.number.floats.ContextFloatProviders;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

final class LootTableAdapter {
    private final RegistryAccess registries;
    private final TreeSet<String> items;

    LootTableAdapter(RegistryAccess registries, TreeSet<String> items) {
        this.registries = registries;
        this.items = items;
    }

    List<LootPool> build(JsonObject definition) {
        var ops = RegistryOps.create(JsonOps.INSTANCE, registries);
        if (definition.has("modifier")) throw new IllegalStateException("Unsupported table-level loot functions");
        var pools = new ArrayList<LootPool>();
        for (var value : definition.getAsJsonArray("pools")) {
            var data = value.getAsJsonObject();
            if (data.has("condition") || data.has("modifier")) throw new IllegalStateException("Unsupported conditional loot pool");
            var pool = LootPool.lootPool().setRolls(ContextIntProviders.CODEC.parse(ops, data.get("rolls")).getOrThrow());
            if (data.has("bonus_rolls")) pool.setBonusRolls(ContextFloatProviders.CODEC.parse(ops, data.get("bonus_rolls")).getOrThrow());
            for (var entryValue : data.getAsJsonArray("entries")) {
                var entry = entryValue.getAsJsonObject();
                if (entry.has("condition")) throw new IllegalStateException("Unsupported conditional loot entry");
                UniformContainerBase.Builder<?> builder;
                String type = entry.get("type").getAsString();
                if (type.equals("minecraft:empty")) builder = EmptyLootItem.emptyItem();
                else if (type.equals("minecraft:item")) {
                    String id = entry.get("name").getAsString();
                    var item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
                    if (item == null) throw new IllegalStateException("Unknown loot item: " + id);
                    builder = LootItem.lootTableItem(item);
                    items.add(id);
                    if (id.equals("minecraft:book") && entry.has("modifier")) items.add("minecraft:enchanted_book");
                } else throw new IllegalStateException("Unsupported loot entry: " + type);
                if (entry.has("weight")) builder.setWeight(entry.get("weight").getAsInt());
                if (entry.has("quality")) builder.setQuality(entry.get("quality").getAsInt());
                if (entry.has("modifier")) for (var function : modifiers(entry.get("modifier"))) {
                    var decoded = function(function.getAsJsonObject(), ops);
                    builder.apply(net.minecraft.core.Holder.direct(decoded));
                }
                pool.add(builder);
            }
            pools.add(pool.build());
        }
        return List.copyOf(pools);
    }

    private LootItemFunction function(JsonObject data, RegistryOps<JsonElement> ops) {
        if (data.has("condition")) throw new IllegalStateException("Unsupported conditional loot function");
        if (!data.get("type").getAsString().equals("minecraft:enchant_with_levels")) return LootItemFunctions.DIRECT_CODEC.parse(ops, data).getOrThrow();
        var levels = ContextIntProviders.CODEC.parse(ops, data.get("levels")).getOrThrow();
        var options = data.has("options") ? Optional.of(RegistryCodecs.holderSet(Registries.ENCHANTMENT).parse(ops, data.get("options")).getOrThrow()) : Optional.<net.minecraft.core.HolderSet<Enchantment>>empty();
        return new LootItemFunction() {
            @Override public MapCodec<? extends LootItemFunction> codec() { return EnchantWithLevelsFunction.MAP_CODEC; }
            @Override public ItemStack apply(ItemStack stack, LootContext context) { return EnchantmentHelper.enchantItem(context.getRandom(), stack, levels.value().getInt(context), registries, options); }
        };
    }

    private static List<JsonElement> modifiers(JsonElement value) {
        if (!value.isJsonArray()) return List.of(value);
        var list = new ArrayList<JsonElement>();
        value.getAsJsonArray().forEach(list::add);
        return list;
    }

    static ContextMap emptyContext() { return ContextMap.EMPTY; }
}
