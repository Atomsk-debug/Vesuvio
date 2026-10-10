package it.cosimo.vesuvio.ticks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ticks.SectionLookup;
import it.cosimo.vesuvio.ticks.WorldTicks;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;

/**
 * Setting worldEntitySectionLookup: every box-based entity lookup (Level.getEntities, getEntitiesOfClass, entity
 * collisions, pushing, hoppers picking up items, AI sensors...) goes through forEachAccessibleNonEmptySection, on
 * the server thread and on the client thread. See {@link SectionLookup}: same sections, same order.
 */
@Mixin(EntitySectionStorage.class)
abstract class EntitySectionStorageMixin<T extends EntityAccess> {
	@Shadow
	@Final
	private Long2ObjectMap<EntitySection<T>> sections;

	@Inject(method = "forEachAccessibleNonEmptySection", at = @At("HEAD"), cancellable = true)
	private void vesuvio$directLookup(AABB bb, AbortableIterationConsumer<EntitySection<T>> output, CallbackInfo ci) {
		if (WorldTicks.SECTION_LOOKUP.on() && SectionLookup.forEach(this.sections, bb, output)) {
			ci.cancel();
		}
	}
}
