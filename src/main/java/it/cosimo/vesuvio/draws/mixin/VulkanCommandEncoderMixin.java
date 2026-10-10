package it.cosimo.vesuvio.draws.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanQueue;

import it.cosimo.vesuvio.draws.RedundantBinds;
import it.cosimo.vesuvio.draws.SubmitTimers;

/**
 * The frame's submit ({@link SubmitTimers}): times vkQueueSubmit ("encode") and the wait for the frame before last
 * ("wait"), and closes the frame's draw and bind counters ({@link RedundantBinds}). Timing only; the calls run as in
 * vanilla, always (the timers cost four clock reads a frame).
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class VulkanCommandEncoderMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void vesuvio$frameRecorded(CallbackInfo ci) {
		RedundantBinds.endFrame();
	}

	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanQueue$Submission;close()V"))
	private void vesuvio$timeEncode(VulkanQueue.Submission submission, Operation<Void> original) {
		long start = System.nanoTime();
		try {
			original.call(submission);
		} finally {
			SubmitTimers.encoded(System.nanoTime() - start);
		}
	}

	/** Both waits of submit (the second only runs after a 5 s timeout). */
	@WrapOperation(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;awaitSubmitCompletion(JJ)Z"))
	private boolean vesuvio$timeWait(VulkanCommandEncoder self, long submitIndex, long timeoutNs,
			Operation<Boolean> original) {
		long start = System.nanoTime();
		try {
			return original.call(self, submitIndex, timeoutNs);
		} finally {
			SubmitTimers.waited(System.nanoTime() - start);
		}
	}

	@Inject(method = "submit", at = @At("RETURN"))
	private void vesuvio$frameSubmitted(CallbackInfo ci) {
		SubmitTimers.submitted(System.nanoTime());
	}
}
