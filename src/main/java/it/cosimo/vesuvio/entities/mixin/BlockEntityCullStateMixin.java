package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.entities.CullTarget;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Holds the block entity's last occlusion result (written by the cull worker, read by the render thread). */
@Mixin(BlockEntity.class)
public abstract class BlockEntityCullStateMixin implements CullTarget {
	@Unique
	private volatile long vesuvio$cull;

	@Override
	public long vesuvio$cullStamp() {
		return vesuvio$cull;
	}

	@Override
	public void vesuvio$setCullStamp(long stamp) {
		vesuvio$cull = stamp;
	}
}
