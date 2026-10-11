package it.cosimo.vesuvio.ticks.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.ticks.LevelEntityStorage;
import it.cosimo.vesuvio.ticks.RareEntities;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.entity.EntitySectionStorage;
import net.minecraft.world.phys.AABB;

/**
 * Setting worldRareEntityIndex: a hopper with no block container above it (or in front of it) looks for a
 * container entity (chest/hopper minecart, chest boat) every tick. Vanilla walks every entity around; the
 * container list holds exactly the entities that can pass {@code instanceof Container}, in the same order, so the
 * list, and the random pick from it (same size, same single nextInt call), are vanilla's.
 */
@Mixin(HopperBlockEntity.class)
abstract class HopperBlockEntityMixin {
	@Inject(method = "getEntityContainer", at = @At("HEAD"), cancellable = true)
	private static void vesuvio$rareContainers(Level level, double x, double y, double z,
			CallbackInfoReturnable<Container> cir) {
		if (!RareEntities.active()) {
			return;
		}
		EntitySectionStorage<Entity> storage = ((LevelEntityStorage) level).vesuvio$entityStorage();
		if (storage == null) {
			return;
		}
		List<Entity> entities = RareEntities.getEntities(level, storage, null,
				new AABB(x - 0.5, y - 0.5, z - 0.5, x + 0.5, y + 0.5, z + 0.5), EntitySelector.CONTAINER_ENTITY_SELECTOR,
				RareEntities.CONTAINER);
		cir.setReturnValue(!entities.isEmpty() ? (Container) entities.get(level.getRandom().nextInt(entities.size())) : null);
	}
}
