package it.cosimo.vesuvio.frame.mixin;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanTransientMemory;

import it.cosimo.vesuvio.frame.GpuPassTimers;

/**
 * Setting gpuPassTimers ({@link GpuPassTimers}): the transient staging command buffer, which runs first in each
 * frame's submit, gets a timestamp at its start and its end (counted as "uploads"). Only adds timestamps while the
 * encoder is measuring.
 */
@Mixin(VulkanTransientMemory.class)
public abstract class FrameGpuTimersStagingMixin {
	@Shadow
	@Final
	private VulkanCommandEncoder encoder;

	@ModifyExpressionValue(method = "beginSubmit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;allocateAndBeginTransientCommandBuffer()Lorg/lwjgl/vulkan/VkCommandBuffer;"))
	private VkCommandBuffer vesuvio$gpuTimeStagingBegin(VkCommandBuffer commandBuffer) {
		if (this.encoder instanceof GpuPassTimers.Host host) {
			GpuPassTimers.Recorder r = host.vesuvio$gpuTimers();
			if (r != null) {
				r.bufferBegin(commandBuffer, true);
			}
		}
		return commandBuffer;
	}

	@WrapOperation(method = "endSubmit", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkEndCommandBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;)I"))
	private int vesuvio$gpuTimeStagingEnd(VkCommandBuffer commandBuffer, Operation<Integer> original) {
		if (this.encoder instanceof GpuPassTimers.Host host) {
			GpuPassTimers.Recorder r = host.vesuvio$gpuTimers();
			if (r != null) {
				r.bufferEnd(commandBuffer);
			}
		}
		return original.call(commandBuffer);
	}
}
