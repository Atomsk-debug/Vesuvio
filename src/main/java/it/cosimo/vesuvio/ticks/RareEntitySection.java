package it.cosimo.vesuvio.ticks;

import java.util.List;

import net.minecraft.world.level.entity.EntityAccess;

/** Implemented by EntitySection (mixin): the section's rare entities of one kind, in the section's own order. */
public interface RareEntitySection {
	/** Entities of the given {@link RareEntities} kind, in insertion order; empty list when none. Never modify it. */
	List<EntityAccess> vesuvio$rare(int kind);
}
