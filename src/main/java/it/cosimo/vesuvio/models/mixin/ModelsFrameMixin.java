package it.cosimo.vesuvio.models.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import it.cosimo.vesuvio.models.ModelFastPath;
import it.cosimo.vesuvio.models.SignTextCache;

/** Settings fastEntityModels and signTextCache: publishes the frame's counters at submit (only counts). */
@Mixin(VulkanCommandEncoder.class)
abstract class ModelsFrameMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void vesuvio$modelCounters(CallbackInfo ci) {
		ModelFastPath.endFrame();
		SignTextCache.endFrame();
	}
}
