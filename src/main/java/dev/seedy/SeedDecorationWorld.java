package dev.seedy;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;

final class SeedDecorationWorld implements InvocationHandler, AutoCloseable {
    final RegistryAccess registries;
    final Locator.StructureWorld world;
    final long seed;
    final WorldGenLevel level;
    final StructureManager structures;
    final TerrainGenerator generator;
    private final TerrainDecoration decoration;
    private final VanillaStructures templates;
    private final Map<Long,Map<Structure,StructureStart>> startData = bounded(8192);
    private final Map<Long,ProtoChunk> starts = bounded(128);
    private final Map<Long,Map<Structure,it.unimi.dsi.fastutil.longs.LongSet>> references = bounded(512);
    private final Map<Long,ProtoChunk> terrain = bounded(64);
    private final Map<Long,ProtoChunk> region = new java.util.HashMap<>();
    private final Map<Long,ProtoChunk> placementStarts = new java.util.HashMap<>();
    private boolean decorating;
    private final net.minecraft.world.level.border.WorldBorder border = new net.minecraft.world.level.border.WorldBorder();
    private final net.minecraft.world.level.storage.LevelData data;
    private final net.minecraft.world.attribute.EnvironmentAttributeReader attributes;
    private final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    private final net.minecraft.world.level.dimension.DimensionType dimensionType;
    private int sourceX, sourceZ;
    private net.minecraft.util.RandomSource random;

    SeedDecorationWorld(RegistryAccess registries, Locator.StructureWorld world, long seed) {
        this.registries = registries; this.world = world; this.seed = seed;
        dimension = world.dimension().equals("the_nether") ? net.minecraft.world.level.Level.NETHER : world.dimension().equals("the_end") ? net.minecraft.world.level.Level.END : net.minecraft.world.level.Level.OVERWORLD;
        dimensionType = registries.lookupOrThrow(Registries.DIMENSION_TYPE).getOrThrow(net.minecraft.resources.ResourceKey.create(Registries.DIMENSION_TYPE,dimension.identifier())).value();
        data = (net.minecraft.world.level.storage.LevelData)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{net.minecraft.world.level.storage.LevelData.class},(proxy,method,args) -> switch (method.getName()) {
            case "getGameTime", "getDayTime" -> 0L;
            case "getDifficulty" -> net.minecraft.world.Difficulty.NORMAL;
            case "isHardcore", "isDifficultyLocked" -> false;
            default -> null;
        });
        level = (WorldGenLevel)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{WorldGenLevel.class},this);
        structures = new StructureManager(level,new WorldOptions(seed,true,false),null);
        generator = new TerrainGenerator(registries,world,seed,structures);
        decoration = new TerrainDecoration(world.generator(),registries);
        templates = new VanillaStructures(registries);
        attributes = TerrainGenerator.environmentAttributes(level);
    }

    private static <K,V> Map<K,V> bounded(int limit) {
        return new LinkedHashMap<>(limit,0.75f,true) {
            @Override protected boolean removeEldestEntry(Map.Entry<K,V> entry) { return size() > limit; }
        };
    }

    private Map<Structure,StructureStart> startData(int x, int z) {
        long key = ChunkPos.pack(x,z);
        var cached = startData.get(key);
        if (cached == null) {
            GeneratorAccess.checkpoint();
            var chunk = generator.empty(x,z);
            world.generator().createStructures(registries,world.placements(),structures,chunk,templates.templates(),dimension);
            cached = Map.copyOf(chunk.getAllStarts());
            startData.put(key,cached);
        }
        return cached;
    }

    private ProtoChunk starts(int x, int z) {
        long key = ChunkPos.pack(x,z);
        var cache = decorating ? placementStarts : starts;
        return cache.computeIfAbsent(key,ignored -> {
            var copy = generator.empty(x,z);
            var original = startData(x,z);
            if (!decorating) { copy.setAllStarts(original); return copy; }
            var context = templates.serializationContext();
            var copied = new java.util.HashMap<Structure,StructureStart>();
            original.forEach((structure,start) -> copied.put(structure,SeedStructurePieces.withoutEntities(StructureStart.loadStaticStart(context,start.createTag(context,copy.getPos()),seed),context)));
            copy.setAllStarts(copied);
            return copy;
        });
    }

    private ProtoChunk references(int x, int z) {
        long key = ChunkPos.pack(x,z);
        var cached = references.get(key);
        if (cached == null) {
            cached = new java.util.HashMap<>();
            for (int dx = -8; dx <= 8; ++dx) for (int dz = -8; dz <= 8; ++dz) {
                GeneratorAccess.checkpoint();
                for (var start : startData(x + dx,z + dz).values()) if (start.isValid() && start.getBoundingBox().intersects(x * 16,z * 16,x * 16 + 15,z * 16 + 15))
                    cached.computeIfAbsent(start.getStructure(),ignored -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet()).add(ChunkPos.pack(x + dx,z + dz));
            }
            references.put(key,cached);
        }
        var chunk = starts(x,z);
        chunk.setAllReferences(cached);
        return chunk;
    }

    Map<Long,ProtoChunk> generate(int x, int z) {
        return generate(x,z,false);
    }

    Map<Long,ProtoChunk> generate(int x, int z, boolean vanilla) {
        int groupX = Math.floorDiv(x,2) * 2, groupZ = Math.floorDiv(z,2) * 2;
        region.clear();
        for (int dx = -2; dx <= 3; ++dx) for (int dz = -2; dz <= 3; ++dz) {
            int cx = groupX + dx, cz = groupZ + dz; long key = ChunkPos.pack(cx,cz);
            var base = terrain.get(key);
            if (base == null) {
                base = generator.generate(cx,cz);
                generator.fillBiomes(base);
                Heightmap.primeHeightmaps(base,EnumSet.allOf(Heightmap.Types.class));
                terrain.put(key,base);
            }
            region.put(key,generator.copy(base));
        }
        try {
            decorating = true;
            for (int cx = groupX - 1; cx <= groupX + 2; ++cx) for (int cz = groupZ - 1; cz <= groupZ + 2; ++cz) {
                GeneratorAccess.checkpoint(); sourceX = cx; sourceZ = cz;
                random = world.placements().randomState().getOrCreateRandomFactory(net.minecraft.resources.Identifier.withDefaultNamespace("worldgen_region_random")).at(cx * 16,0,cz * 16);
                if (vanilla) world.generator().applyBiomeDecoration(level,region.get(ChunkPos.pack(cx,cz)),structures);
                else decoration.decorate(level,region.get(ChunkPos.pack(cx,cz)),structures);
            }
            var result = new java.util.HashMap<Long,ProtoChunk>();
            for (int dx = 0; dx < 2; ++dx) for (int dz = 0; dz < 2; ++dz) {
                long key = ChunkPos.pack(groupX + dx,groupZ + dz); result.put(key,region.get(key));
            }
            return result;
        } finally { decorating = false; placementStarts.clear(); region.clear(); }
    }

    private ProtoChunk chunk(int x, int z) {
        var chunk = region.get(ChunkPos.pack(x,z));
        if (chunk == null) throw new IllegalStateException("Feature read outside the prediction region: " + x + ", " + z);
        return chunk;
    }

    private BlockState block(BlockPos pos) { return chunk(pos.getX() >> 4,pos.getZ() >> 4).getBlockState(pos); }
    private boolean writable(BlockPos pos) { return Math.abs((pos.getX() >> 4) - sourceX) <= 1 && Math.abs((pos.getZ() >> 4) - sourceZ) <= 1 && pos.getY() >= world.height().getMinY() && pos.getY() <= world.height().getMaxY(); }

    private boolean set(BlockPos pos, BlockState state, int flags) {
        if (!writable(pos)) return false;
        var chunk = chunk(pos.getX() >> 4,pos.getZ() >> 4);
        var old = chunk.getBlockState(pos); chunk.setBlockState(pos,state,flags);
        for (var heightmap : chunk.getHeightmaps()) heightmap.getValue().update(pos.getX() & 15,pos.getY(),pos.getZ() & 15,state);
        if (state.hasBlockEntity()) {
            if (!old.is(state.getBlock())) chunk.removeBlockEntity(pos);
            if (chunk.getBlockEntity(pos) == null && state.getBlock() instanceof EntityBlock entity) {
                var created = entity.newBlockEntity(pos.immutable(),state);
                if (created != null) chunk.setBlockEntity(created);
            }
        } else if (old.hasBlockEntity()) chunk.removeBlockEntity(pos);
        return true;
    }

    @Override public Object invoke(Object proxy, Method method, Object[] values) throws Throwable {
        Object[] args = values == null ? new Object[0] : values;
        switch (method.getName()) {
            case "toString": return "Seedy prediction world";
            case "hashCode": return System.identityHashCode(proxy);
            case "equals": return proxy == args[0];
            case "getSeed": return seed;
            case "registryAccess": return registries;
            case "dimensionType": return dimensionType;
            case "getLevelData": return data;
            case "getLevel", "getServer", "getNearestPlayer": return null;
            case "isClientSide", "isOldChunkAround": return false;
            case "getSeaLevel": return world.generator().getSeaLevel();
            case "getMinY": return world.height().getMinY();
            case "getHeight":
                if (args.length == 0) return world.height().getHeight();
                int x = args[1] instanceof BlockPos p ? p.getX() : (int)args[1], z = args[1] instanceof BlockPos p ? p.getZ() : (int)args[2];
                return chunk(x >> 4,z >> 4).getHeight((Heightmap.Types)args[0],x & 15,z & 15) + 1;
            case "getChunk":
                if (args.length == 1 && args[0] instanceof BlockPos p) return chunk(p.getX() >> 4,p.getZ() >> 4);
                int cx = (int)args[0], cz = (int)args[1];
                if (args.length >= 3 && args[2] == ChunkStatus.STRUCTURE_STARTS) return starts(cx,cz);
                if (args.length >= 3 && args[2] == ChunkStatus.STRUCTURE_REFERENCES) return references(cx,cz);
                return chunk(cx,cz);
            case "hasChunk": return region.containsKey(ChunkPos.pack((int)args[0],(int)args[1]));
            case "getBiomeManager": return generator.biomes;
            case "getUncachedNoiseBiome", "getNoiseBiome": return generator.noiseBiome((int)args[0],(int)args[1],(int)args[2]);
            case "getBlockState": return block((BlockPos)args[0]);
            case "getFluidState": return block((BlockPos)args[0]).getFluidState();
            case "isStateAtPosition": return ((java.util.function.Predicate<BlockState>)args[1]).test(block((BlockPos)args[0]));
            case "isFluidAtPosition": return ((java.util.function.Predicate<net.minecraft.world.level.material.FluidState>)args[1]).test(block((BlockPos)args[0]).getFluidState());
            case "setBlock": return set((BlockPos)args[0],(BlockState)args[1],(int)args[2]);
            case "ensureCanWrite": return writable((BlockPos)args[0]);
            case "removeBlock": return set((BlockPos)args[0],block((BlockPos)args[0]).getFluidState().createLegacyBlock(),3);
            case "destroyBlock": return set((BlockPos)args[0],Blocks.AIR.defaultBlockState(),3);
            case "getBlockEntity":
                var pos = (BlockPos)args[0]; var entity = chunk(pos.getX() >> 4,pos.getZ() >> 4).getBlockEntity(pos);
                if (args.length == 2) return ((net.minecraft.world.level.block.entity.BlockEntityType<?>)args[1]).isValid(block(pos)) ? java.util.Optional.ofNullable(entity) : java.util.Optional.empty();
                return entity;
            case "getRandom": return random;
            case "getWorldBorder": return border;
            case "environmentAttributes": return attributes;
            case "enabledFeatures": return net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
            case "getSkyDarken": return 0;
            case "nextSubTickCount": return 0L;
            case "getGameTime": return 0L;
            case "getCurrentDifficultyAt": return new net.minecraft.world.DifficultyInstance(net.minecraft.world.Difficulty.NORMAL,0,0,0);
            case "getBlockTicks", "getFluidTicks": return net.minecraft.world.ticks.BlackholeTickAccess.emptyLevelList();
            case "getEntities", "players": return List.of();
            case "addFreshEntity": return false;
            case "getLightEngine": return null;
            case "getRawBrightness", "getMaxLocalRawBrightness", "getBrightness":
                var lightPos = args[0] instanceof BlockPos p ? p : (BlockPos)args[1];
                return lightPos.getY() > chunk(lightPos.getX() >> 4,lightPos.getZ() >> 4).getHeight(Heightmap.Types.WORLD_SURFACE,lightPos.getX() & 15,lightPos.getZ() & 15) ? 15 : block(lightPos).getLightEmission();
            case "canSeeSky":
                var sky = (BlockPos)args[0]; return sky.getY() > chunk(sky.getX() >> 4,sky.getZ() >> 4).getHeight(Heightmap.Types.WORLD_SURFACE,sky.getX() & 15,sky.getZ() & 15);
            case "setCurrentlyGenerating", "addFreshEntityWithPassengers", "playSound", "addParticle", "levelEvent", "gameEvent": return null;
            default:
                if (method.isDefault()) return InvocationHandler.invokeDefault(proxy,method,args);
                throw new UnsupportedOperationException("Prediction world does not implement " + method);
        }
    }

    @Override public void close() { templates.close(); region.clear(); placementStarts.clear(); terrain.clear(); startData.clear(); starts.clear(); references.clear(); }
}
