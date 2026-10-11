package it.cosimo.vesuvio.ticks.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.ticks.PathTypeMemo;
import net.minecraft.world.level.block.state.BlockBehaviour;

/** Setting worldPathTypeCache: the per-state slot for {@link PathTypeMemo}. */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStatePathTypeMixin implements PathTypeMemo.Holder {
	@Unique
	private PathTypeMemo.@Nullable Entry vesuvio$pathType;

	@Override
	public PathTypeMemo.@Nullable Entry vesuvio$pathType() {
		return this.vesuvio$pathType;
	}

	@Override
	public void vesuvio$setPathType(PathTypeMemo.Entry entry) {
		this.vesuvio$pathType = entry;
	}
}
