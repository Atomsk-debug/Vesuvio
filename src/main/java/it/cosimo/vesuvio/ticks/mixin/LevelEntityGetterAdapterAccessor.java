package it.cosimo.vesuvio.ticks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.level.entity.LevelEntityGetterAdapter;

/** The section storage behind a level's entity getter (server: PersistentEntitySectionManager, client: Transient). */
@Mixin(LevelEntityGetterAdapter.class)
public interface LevelEntityGetterAdapterAccessor {
	@Accessor("sectionStorage")
	EntitySectionStorage<?> vesuvio$sectionStorage();
}
