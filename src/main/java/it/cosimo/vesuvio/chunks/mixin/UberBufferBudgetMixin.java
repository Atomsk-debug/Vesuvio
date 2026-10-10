package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.blaze3d.vertex.UberGpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;

import it.cosimo.vesuvio.chunks.ChunkUploads;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * UberGpuBuffer.uploadStagedAllocations (render thread, under the chunk dispatcher's lock): with the upload budget
 * active ({@link ChunkUploads}), the staged entries that don't fit this frame are set aside before vanilla's code
 * runs and put back just before it empties its "skipped" set, so vanilla allocates, copies and calls back exactly as
 * usual, only for fewer entries. Entries of meshes released meanwhile are closed instead of put back, as vanilla
 * would have skipped them. With the budget off nothing is set aside and both hooks do nothing.
 */
@Mixin(UberGpuBuffer.class)
public abstract class UberBufferBudgetMixin {
	@Shadow
	@Final
	private Object2ObjectOpenHashMap<Object, Object> stagedAllocations;

	@Unique
	private final Object2ObjectOpenHashMap<Object, Object> vesuvio$held = new Object2ObjectOpenHashMap<>();

	@Inject(method = "uploadStagedAllocations", at = @At("HEAD"))
	private void vesuvio$selectWithinBudget(GpuDevice device, StagingBuffer.Uploader uploader,
			CallbackInfoReturnable<Boolean> cir) {
		ChunkUploads.select(stagedAllocations, vesuvio$held);
	}

	@WrapOperation(method = "uploadStagedAllocations", at = @At(value = "INVOKE",
			target = "Lit/unimi/dsi/fastutil/objects/ObjectOpenHashSet;clear()V"))
	private void vesuvio$restoreHeld(ObjectOpenHashSet<Object> skipped, Operation<Void> original) {
		ChunkUploads.restore(stagedAllocations, vesuvio$held, skipped);
		original.call(skipped);
	}
}
