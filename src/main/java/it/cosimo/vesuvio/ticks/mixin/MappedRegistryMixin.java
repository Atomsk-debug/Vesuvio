package it.cosimo.vesuvio.ticks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ticks.PathTypeMemo;
import net.minecraft.core.MappedRegistry;

/**
 * Setting worldPathTypeCache: refreshTagsInHolders is where every registry rebinds each holder's tag set (the set
 * BlockState.is(TagKey) and FluidState.is(TagKey) read), on freeze and on every tag reload. Bumping the memo's
 * generation at both ends invalidates anything computed before or during the rebind. Runs always (it only
 * increments a counter), so turning the setting on later never sees stale entries.
 */
@Mixin(MappedRegistry.class)
abstract class MappedRegistryMixin {
	@Inject(method = "refreshTagsInHolders", at = @At("HEAD"))
	private void vesuvio$tagsChanging(CallbackInfo ci) {
		PathTypeMemo.tagsChanged();
	}

	@Inject(method = "refreshTagsInHolders", at = @At("TAIL"))
	private void vesuvio$tagsChanged(CallbackInfo ci) {
		PathTypeMemo.tagsChanged();
	}
}
