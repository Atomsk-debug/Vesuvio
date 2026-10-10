package it.cosimo.vesuvio.ticks;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntitySectionStorage;

/** Implemented by Level (mixin). */
public interface LevelEntityStorage {
	/** The section storage behind Level.getEntities(), or null if it is not the vanilla adapter (another mod). */
	EntitySectionStorage<Entity> vesuvio$entityStorage();
}
