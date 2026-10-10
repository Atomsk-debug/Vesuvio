package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.entities.CullTarget;
import net.minecraft.world.entity.Entity;

/** Holds the entity's last occlusion result (written by the cull worker, read by the render thread). */
@Mixin(Entity.class)
public abstract class EntityCullStateMixin implements CullTarget {
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
