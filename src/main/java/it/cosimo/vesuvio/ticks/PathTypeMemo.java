package it.cosimo.vesuvio.ticks;

import java.util.concurrent.atomic.AtomicInteger;

import org.jspecify.annotations.Nullable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.pathfinder.PathType;

/**
 * Setting worldPathTypeCache. WalkNodeEvaluator.getPathTypeFromState(level, pos) reads one block state and then
 * decides from that state alone: block tags (PathType's tag list, FIRE, CAMPFIRES, FENCES, WALLS), fluid tags of
 * the state's fluid, the DoorBlock / FenceGateBlock properties, and isPathfindable(state, LAND), whose vanilla
 * versions only use the state (the default one asks the collision shape with EmptyBlockGetter at BlockPos.ZERO).
 * The method never uses level or pos again. So its result is a function of the state and the bound tags: we keep
 * it on the state, stamped with a tag generation that MappedRegistry.refreshTagsInHolders bumps before and after
 * it rebinds any registry's tags (/reload, joining a world). A stale stamp means recompute.
 *
 * <p>Misses are computed by the vanilla method itself, given a getter that returns that state for any position,
 * so the stored value is exactly what vanilla returns for the state.
 */
public final class PathTypeMemo {
	private static final AtomicInteger GENERATION = new AtomicInteger();

	/** Implemented by BlockStateBase (mixin): the remembered result, or null. */
	public interface Holder {
		@Nullable
		Entry vesuvio$pathType();

		void vesuvio$setPathType(Entry entry);
	}

	/** Immutable, so a racing reader sees either the old or the new entry, never a mix. */
	public record Entry(int generation, PathType type) {
	}

	private PathTypeMemo() {
	}

	/** Tags of some registry are being (or were just) rebound. */
	public static void tagsChanged() {
		GENERATION.incrementAndGet();
	}

	public static PathType get(BlockGetter level, BlockPos pos, Operation<PathType> original) {
		BlockState state = level.getBlockState(pos);
		Holder holder = (Holder) state;
		int generation = GENERATION.get(); // read before computing: a rebind during the computation makes it stale
		Entry entry = holder.vesuvio$pathType();
		if (entry != null && entry.generation() == generation) {
			TickStats.pathTypeHits++;
			return entry.type();
		}
		TickStats.pathTypeMisses++;
		PathType type = original.call(new OneState(state), pos);
		holder.vesuvio$setPathType(new Entry(generation, type));
		return type;
	}

	/** A getter that answers {@code state} everywhere: the vanilla method then computes the state-only result. */
	private record OneState(BlockState state) implements BlockGetter {
		@Override
		public @Nullable BlockEntity getBlockEntity(BlockPos pos) {
			return null;
		}

		@Override
		public BlockState getBlockState(BlockPos pos) {
			return this.state;
		}

		@Override
		public FluidState getFluidState(BlockPos pos) {
			return this.state.getFluidState();
		}

		@Override
		public int getHeight() {
			return 384;
		}

		@Override
		public int getMinY() {
			return -64;
		}
	}
}
