package it.cosimo.vesuvio.ticks.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.ticks.RareEntities;
import it.cosimo.vesuvio.ticks.RareEntitySection;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;

/**
 * Setting worldRareEntityIndex (restart): next to its ClassInstanceMultiMap, each section keeps the sub-lists of
 * {@link RareEntities} kinds. add() appends to the multimap's list and to ours; remove() does List.remove(Object)
 * on the multimap's list and on ours. Both lists see the same calls in the same order, so ours is always the
 * multimap's list filtered by class, in the same order. Entity sections are only touched on the thread that owns
 * the level (server thread / client thread), like the multimap itself.
 */
@Mixin(EntitySection.class)
abstract class EntitySectionMixin implements RareEntitySection {
	/** Per kind, null until the first entity of that kind arrives. Null array: none yet / index off. */
	@Unique
	private List<EntityAccess>[] vesuvio$rareLists;

	@Inject(method = "add", at = @At("TAIL"))
	@SuppressWarnings("unchecked")
	private void vesuvio$addRare(EntityAccess entity, CallbackInfo ci) {
		if (!RareEntities.MAINTAIN) {
			return;
		}
		int kinds = RareEntities.kinds(entity);
		if (kinds != 0 && this.vesuvio$rareLists == null) {
			this.vesuvio$rareLists = new List[RareEntities.KINDS];
		}
		for (int k = 0; kinds != 0; k++, kinds >>>= 1) {
			if ((kinds & 1) != 0) {
				List<EntityAccess> list = this.vesuvio$rareLists[k];
				if (list == null) {
					list = this.vesuvio$rareLists[k] = new ArrayList<>(2);
				}
				list.add(entity);
			}
		}
	}

	@Inject(method = "remove", at = @At("RETURN"))
	private void vesuvio$removeRare(EntityAccess entity, CallbackInfoReturnable<Boolean> cir) {
		if (this.vesuvio$rareLists == null) {
			return;
		}
		int kinds = RareEntities.kinds(entity);
		for (int k = 0; kinds != 0; k++, kinds >>>= 1) {
			if ((kinds & 1) != 0 && this.vesuvio$rareLists[k] != null) {
				this.vesuvio$rareLists[k].remove(entity);
			}
		}
	}

	@Override
	public List<EntityAccess> vesuvio$rare(int kind) {
		List<EntityAccess> list = this.vesuvio$rareLists == null ? null : this.vesuvio$rareLists[kind];
		return list == null ? RareEntities.empty() : list;
	}
}
