package dev.seedy;

import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

final class ChestPlacementOracle {
    final Map<BlockPos, BlockState> blocks = new HashMap<>();
    final Map<BlockPos, BlockEntity> entities = new HashMap<>();
    final WorldGenLevel level;

    ChestPlacementOracle(RegistryAccess registries, long seed) {
        level = (WorldGenLevel)Proxy.newProxyInstance(WorldGenLevel.class.getClassLoader(), new Class<?>[]{WorldGenLevel.class}, (proxy, method, arguments) -> {
            return switch (method.getName()) {
                case "registryAccess" -> registries;
                case "enabledFeatures" -> net.minecraft.world.flag.FeatureFlags.DEFAULT_FLAGS;
                case "getSeed" -> seed;
                case "getRandom" -> net.minecraft.util.RandomSource.create(seed);
                case "getMinY" -> -64;
                case "getHeight" -> arguments == null || arguments.length == 0 ? 384 : 80;
                case "getMaxY" -> 319;
                case "getBlockState" -> state((BlockPos)arguments[0]);
                case "getFluidState" -> state((BlockPos)arguments[0]).getFluidState();
                case "getBlockEntity" -> arguments.length == 1 ? entities.get((BlockPos)arguments[0]) : InvocationHandler.invokeDefault(proxy, method, arguments);
                case "setBlock" -> {
                    var position = ((BlockPos)arguments[0]).immutable();
                    var state = (BlockState)arguments[1];
                    blocks.put(position, state);
                    if (state.getBlock() instanceof EntityBlock block) entities.put(position, block.newBlockEntity(position, state));
                    else entities.remove(position);
                    yield true;
                }
                case "getLevel" -> null;
                case "ensureCanWrite", "addFreshEntity", "isStateAtPosition" -> true;
                case "isClientSide" -> false;
                case "blockUpdated", "scheduleTick", "addFreshEntityWithPassengers", "levelEvent", "gameEvent" -> null;
                case "toString" -> "Chest placement test level";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == arguments[0];
                default -> {
                    if (method.isDefault()) yield InvocationHandler.invokeDefault(proxy, method, arguments);
                    throw new UnsupportedOperationException(method.toString());
                }
            };
        });
    }

    private BlockState state(BlockPos position) { return blocks.getOrDefault(position, position.getY() < 80 ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState()); }
}
