package it.cosimo.vesuvio.draws.mixin;

import java.nio.IntBuffer;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;

import it.cosimo.vesuvio.draws.RedundantBinds;
import it.unimi.dsi.fastutil.objects.ReferenceList;
import org.jspecify.annotations.Nullable;

/**
 * Setting skipRedundantBinds ({@link RedundantBinds}): in one Vulkan render pass, no second vkCmdBindPipeline for
 * the pipeline already bound, and no new descriptor set when the bound one already holds the same values. Also
 * counts the frame's draws (each indirect sub-draw is one; on MoltenVK each is one Metal draw).
 *
 * <p>The state kept here belongs to this pass object, which vanilla makes new for each render pass, so nothing is
 * assumed about what an earlier pass left bound.
 */
@Mixin(VulkanRenderPass.class)
public abstract class VulkanRenderPassMixin {
	@Shadow
	@Nullable
	protected VulkanRenderPipeline pipeline;

	@Shadow
	private boolean anyDescriptorDirty;

	@Shadow
	@Final
	protected ReferenceList<Object> uniforms;

	/** setPipeline was asked for the pipeline this pass already bound. */
	@Unique
	private boolean vesuvio$samePipeline;

	/** The pipeline the bound descriptor set was made for, or null when none may be reused. */
	@Unique
	private @Nullable VulkanRenderPipeline vesuvio$setPipeline;

	/** The values written into the bound descriptor set, in the order of that pipeline's uniforms. */
	@Unique
	private Object @Nullable [] vesuvio$setValues;

	/** The descriptor push under way is a real one, to be remembered when it returns. */
	@Unique
	private boolean vesuvio$pushing;

	@Inject(method = "setPipeline", at = @At("HEAD"))
	private void vesuvio$checkSamePipeline(BackendRenderPipeline next, CallbackInfo ci) {
		// this.pipeline is only ever set by setPipeline of this pass, right before it binds it.
		vesuvio$samePipeline = next != null && next == this.pipeline;
	}

	@WrapWithCondition(method = "setPipeline", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCmdBindPipeline(Lorg/lwjgl/vulkan/VkCommandBuffer;IJ)V"))
	private boolean vesuvio$skipSamePipeline(VkCommandBuffer commandBuffer, int bindPoint, long vkPipeline) {
		if (vesuvio$samePipeline && RedundantBinds.SKIP.on()) {
			RedundantBinds.pipelineBindSkipped();
			return false;
		}
		// A real bind: from here on only a set made after it may be reused.
		vesuvio$setPipeline = null;
		return true;
	}

	/**
	 * pushDescriptors' first step, its "anything dirty?" test: answering false makes vanilla return right there,
	 * as a cancel at HEAD would, but without a CallbackInfo per draw (the RETURN hook below then sees
	 * vesuvio$pushing false and does nothing).
	 */
	@ModifyExpressionValue(method = "pushDescriptors", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanRenderPass;anyDescriptorDirty:Z"))
	private boolean vesuvio$skipSameDescriptors(boolean dirty) {
		vesuvio$pushing = false;
		if (!dirty || this.pipeline == null) {
			return dirty;
		}
		if (!RedundantBinds.SKIP.on()) {
			vesuvio$setPipeline = null;
			return true;
		}
		if (vesuvio$setPipeline == this.pipeline
				&& RedundantBinds.sameValues(this.pipeline.uniforms(), this.uniforms, vesuvio$setValues)) {
			this.anyDescriptorDirty = false;
			RedundantBinds.descriptorSetSkipped();
			return false;
		}
		vesuvio$pushing = true;
		return true;
	}

	@Inject(method = "pushDescriptors", at = @At("RETURN"))
	private void vesuvio$rememberDescriptors(CallbackInfo ci) {
		if (vesuvio$pushing) {
			vesuvio$pushing = false;
			vesuvio$setPipeline = this.pipeline;
			vesuvio$setValues = RedundantBinds.snapshot(this.pipeline.uniforms(), this.uniforms, vesuvio$setValues);
		}
	}

	// Draw counter (each method calls pushDescriptors first, so it runs for every draw recorded).

	@Inject(method = "drawIndexed", at = @At("HEAD"))
	private void vesuvio$countDrawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset,
			int firstInstance, CallbackInfo ci) {
		RedundantBinds.drew(1);
	}

	@Inject(method = "draw", at = @At("HEAD"))
	private void vesuvio$countDraw(int vertexCount, int instanceCount, int firstVertex, int firstInstance,
			CallbackInfo ci) {
		RedundantBinds.drew(1);
	}

	@Inject(method = "drawIndexedIndirect", at = @At("HEAD"))
	private void vesuvio$countDrawIndexedIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		RedundantBinds.drew(drawCount);
	}

	@Inject(method = "drawIndirect", at = @At("HEAD"))
	private void vesuvio$countDrawIndirect(GpuBufferSlice commands, int drawCount, CallbackInfo ci) {
		RedundantBinds.drew(drawCount);
	}

	@Inject(method = "multiDrawIndexed(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void vesuvio$countMultiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance,
			int drawCount, CallbackInfo ci) {
		RedundantBinds.drew(drawCount);
	}

	@Inject(method = "multiDraw(Ljava/nio/IntBuffer;III)V", at = @At("HEAD"))
	private void vesuvio$countMultiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount,
			CallbackInfo ci) {
		RedundantBinds.drew(drawCount);
	}
}
