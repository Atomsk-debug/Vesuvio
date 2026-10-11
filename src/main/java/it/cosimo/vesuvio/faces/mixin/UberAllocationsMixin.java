package it.cosimo.vesuvio.faces.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;

import it.cosimo.vesuvio.faces.SectionDrawCache;

/**
 * Tells {@link SectionDrawCache} about every change to where meshes sit in the chunk buffers: an allocation made by
 * an upload, one freed (re-upload, mesh released), and all of them dropped when the buffer closes. Each call comes
 * after the change itself. Counting only; nothing else changes.
 */
@Mixin(UberGpuBuffer.class)
abstract class UberAllocationsMixin {
	@Inject(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
			target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", shift = At.Shift.AFTER))
	private void vesuvio$allocated(GpuDevice device, StagingBuffer.Uploader uploader, CallbackInfoReturnable<Boolean> cir) {
		SectionDrawCache.allocationsChanged();
	}

	@ModifyExpressionValue(method = "freeAllocation", at = @At(value = "INVOKE",
			target = "Ljava/util/Map;remove(Ljava/lang/Object;)Ljava/lang/Object;"))
	private Object vesuvio$freed(Object removed) {
		if (removed != null) {
			SectionDrawCache.allocationsChanged();
		}
		return removed;
	}

	@Inject(method = "close", at = @At("RETURN"))
	private void vesuvio$closed(CallbackInfo ci) {
		SectionDrawCache.allocationsChanged();
	}
}
