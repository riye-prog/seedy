package dev.seedy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.XoroshiroRandomSource;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunction;
import net.minecraft.world.level.levelgen.densityfunction.DensityFunctionCompiler;
import net.minecraft.world.level.levelgen.densityfunction.DensitySampler;
import net.minecraft.world.level.levelgen.densityfunction.SamplerContext;
import net.minecraft.world.level.levelgen.material.rule.OreVeinRule;
import net.minecraft.world.level.levelgen.synth.Noise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;

public final class OreVeinCheck {
    private final List<BlockPos> positions;
    private final OreVeinRule iron, copper;
    private final LegacyOreVeinCheck legacy;

    public OreVeinCheck(List<BlockPos> positions) {
        this(positions, "26.3");
    }

    public OreVeinCheck(List<BlockPos> positions, String generationVersion) {
        this.positions = List.copyOf(positions);
        legacy = generationVersion.equals("26.2") ? new LegacyOreVeinCheck(positions) : null;
        if (legacy != null) { iron = copper = null; return; }
        if (!generationVersion.equals("26.3")) throw new IllegalArgumentException("Unsupported ore vein generation version: " + generationVersion);
        var rules = WorldgenAdapter.registries().lookupOrThrow(Registries.MATERIAL_RULE);
        iron = (OreVeinRule)rules.getOrThrow(ResourceKey.create(Registries.MATERIAL_RULE, Identifier.withDefaultNamespace("overworld/iron_ore_vein"))).value();
        copper = (OreVeinRule)rules.getOrThrow(ResourceKey.create(Registries.MATERIAL_RULE, Identifier.withDefaultNamespace("overworld/copper_ore_vein"))).value();
    }

    public static OreVeinCheck fromEntries(long[] entries, String generationVersion) {
        var positions = new ArrayList<BlockPos>();
        for (long entry : entries) {
            if ((entry & 15) != 1) continue;
            positions.add(new BlockPos((int)(entry >> 38), (int)(entry >> 4 & 255) - 64, (int)(entry << 26 >> 38)));
        }
        return new OreVeinCheck(positions, generationVersion);
    }

    public boolean matches(long low, long high) {
        if (legacy != null) return legacy.matches(low, high);
        var random = new XoroshiroRandomSource.XoroshiroPositionalRandomFactory(low, high);
        var noiseCache = new HashMap<String, Noise>();
        var compiler = new DensityFunctionCompiler(new DensityFunction.CompileContext() {
            @Override public Noise createNoiseSampler(Holder<NormalNoise> holder) {
                String name = holder.unwrapKey().orElseThrow().identifier().toString();
                return noiseCache.computeIfAbsent(name, key -> holder.value().create(random.fromHashOf(key)));
            }
            @Override public RandomSource createRandom(Identifier name) { return random.fromHashOf(name.toString()); }
            @Override public RandomSource createEndIslandRandom() { throw new IllegalStateException("End island noise is not used for ore veins"); }
        });
        var profiles = new HashMap<OreVeinRule, Samplers>();
        var oreRandom = random.fromHashOf("minecraft:ore").forkPositional();
        for (BlockPos point : positions) {
            var definition = point.getY() < 0 ? iron : copper;
            var samplers = profiles.computeIfAbsent(definition, rule -> new Samplers(compiler.getSampler(rule.density()).bind(SamplerContext.EMPTY_UNCACHED), compiler.getSampler(rule.richness()).bind(SamplerContext.EMPTY_UNCACHED), compiler.getSampler(rule.fillerGap()).bind(SamplerContext.EMPTY_UNCACHED)));
            int x = point.getX(), y = point.getY(), z = point.getZ();
            float density = samplers.density().sampleValue(x, y, z);
            if (density < 0) return false;
            var rolls = oreRandom.at(x, y, z);
            if (rolls.nextFloat() > density || rolls.nextFloat() >= samplers.richness().sampleValue(x, y, z) || samplers.gap().sampleValue(x, y, z) >= 0 || rolls.nextFloat() >= definition.rawOreChance()) return false;
        }
        return true;
    }

    private record Samplers(DensitySampler.Bound density, DensitySampler.Bound richness, DensitySampler.Bound gap) { }
}
