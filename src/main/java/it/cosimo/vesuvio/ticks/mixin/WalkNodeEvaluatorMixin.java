package it.cosimo.vesuvio.ticks.mixin;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.cosimo.vesuvio.ticks.PathTypeMemo;
import it.cosimo.vesuvio.ticks.WorldTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

/**
 * Setting worldPathTypeCache: getPathTypeFromState is the per-block step of all ground/amphibious pathfinding
 * (through ServerLevel's PathTypeCache misses and the neighbour scan of every candidate node). See
 * {@link PathTypeMemo}.
 */
@Mixin(WalkNodeEvaluator.class)
abstract class WalkNodeEvaluatorMixin {
	@WrapMethod(method = "getPathTypeFromState")
	private static PathType vesuvio$memo(BlockGetter level, BlockPos pos, Operation<PathType> original) {
		if (!WorldTicks.PATH_TYPE_CACHE.on()) {
			return original.call(level, pos);
		}
		return PathTypeMemo.get(level, pos, original);
	}
}
