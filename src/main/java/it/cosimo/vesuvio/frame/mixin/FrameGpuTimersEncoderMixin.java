package it.cosimo.vesuvio.frame.mixin;

import org.jspecify.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSubpassBeginInfo;
import org.lwjgl.vulkan.VkSubpassEndInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import it.cosimo.vesuvio.frame.GpuPassTimers;

/**
 * Setting gpuPassTimers ({@link GpuPassTimers}): timestamps right before each render pass begins and right after it
 * ends (outside the pass), at the start and end of the frame's command buffers, and the per-frame pool handling in
 * submit. Only adds timestamp commands, and only while the setting is on; every vanilla call runs unchanged.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class FrameGpuTimersEncoderMixin implements GpuPassTimers.Host {
	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	private long currentSubmitIndex;

	/** Null while not measuring. */
	@Unique
	private GpuPassTimers.@Nullable Recorder vesuvio$gpuTimers;

	@Override
	public GpuPassTimers.@Nullable Recorder vesuvio$gpuTimers() {
		return this.vesuvio$gpuTimers;
	}

	/**
	 * A new frame starts recording: submit has just waited for the frame before last, which used the same slot
	 * (currentSubmitIndex is already the new frame's), and the staging command buffer of the new frame is begun next.
	 */
	@Inject(method = "submit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanTransientMemory;beginSubmit()V"))
	private void vesuvio$gpuTimersFrameStart(CallbackInfo ci) {
		this.vesuvio$gpuTimers = GpuPassTimers.frameStart(this.vesuvio$gpuTimers, this.device,
				(VulkanCommandEncoder) (Object) this, (int) (this.currentSubmitIndex % 2L));
	}

	// Device closing: queued before the encoder waits for the GPU and drains its destroy queue.
	@Inject(method = "destroy", at = @At("HEAD"))
	private void vesuvio$gpuTimersClose(CallbackInfo ci) {
		GpuPassTimers.Recorder r = this.vesuvio$gpuTimers;
		if (r != null) {
			this.vesuvio$gpuTimers = null;
			r.close((VulkanCommandEncoder) (Object) this);
		}
	}

	@WrapOperation(method = "createRenderPass", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCmdBeginRenderPass2(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkRenderPassBeginInfo;Lorg/lwjgl/vulkan/VkSubpassBeginInfo;)V"))
	private void vesuvio$gpuTimePassBegin(VkCommandBuffer commandBuffer, VkRenderPassBeginInfo beginInfo,
			VkSubpassBeginInfo subpassBeginInfo, Operation<Void> original,
			@Local(argsOnly = true) RenderPassDescriptor descriptor) {
		GpuPassTimers.Recorder r = this.vesuvio$gpuTimers;
		if (r != null) {
			r.passBegin(commandBuffer, descriptor.label());
		}
		original.call(commandBuffer, beginInfo, subpassBeginInfo);
	}

	@WrapOperation(method = "submitRenderPass", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCmdEndRenderPass2(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkSubpassEndInfo;)V"))
	private void vesuvio$gpuTimePassEnd(VkCommandBuffer commandBuffer, VkSubpassEndInfo endInfo,
			Operation<Void> original) {
		original.call(commandBuffer, endInfo);
		GpuPassTimers.Recorder r = this.vesuvio$gpuTimers;
		if (r != null) {
			r.passEnd(commandBuffer);
		}
	}

	/** The frame's main command buffer was just begun (never inside a render pass: commandBuffer() refuses that). */
	@ModifyExpressionValue(method = "commandBuffer", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;allocateAndBeginTransientCommandBuffer()Lorg/lwjgl/vulkan/VkCommandBuffer;"))
	private VkCommandBuffer vesuvio$gpuTimeMainBuffer(VkCommandBuffer commandBuffer) {
		GpuPassTimers.Recorder r = this.vesuvio$gpuTimers;
		if (r != null) {
			r.bufferBegin(commandBuffer, false);
		}
		return commandBuffer;
	}

	/** The object-init command buffer (first contents of new textures and buffers) was just begun. */
	@ModifyExpressionValue(method = "objectInitCommandBuffer", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;allocateAndBeginTransientCommandBuffer()Lorg/lwjgl/vulkan/VkCommandBuffer;"))
	private VkCommandBuffer vesuvio$gpuTimeInitBuffer(VkCommandBuffer commandBuffer) {
		GpuPassTimers.Recorder r = this.vesuvio$gpuTimers;
		if (r != null) {
			r.bufferBegin(commandBuffer, true);
		}
		return commandBuffer;
	}

	/** Both vkEndCommandBuffer calls of endCommandBuffer (init, then main); never inside a pass (it throws first). */
	@WrapOperation(method = "endCommandBuffer", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkEndCommandBuffer(Lorg/lwjgl/vulkan/VkCommandBuffer;)I"))
	private int vesuvio$gpuTimeBufferEnd(VkCommandBuffer commandBuffer, Operation<Integer> original) {
		GpuPassTimers.Recorder r = this.vesuvio$gpuTimers;
		if (r != null) {
			r.bufferEnd(commandBuffer);
		}
		return original.call(commandBuffer);
	}
}
