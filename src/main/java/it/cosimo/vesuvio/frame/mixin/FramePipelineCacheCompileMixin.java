package it.cosimo.vesuvio.frame.mixin;

import java.nio.LongBuffer;

import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;

import it.cosimo.vesuvio.frame.PipelineDiskCache;

/** Setting pipelineCache: every render pipeline is compiled through the disk-backed cache, and timed. */
@Mixin(VulkanRenderPipeline.class)
public abstract class FramePipelineCacheCompileMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private static int vesuvio$compileWithCache(VkDevice device, long pipelineCache, VkGraphicsPipelineCreateInfo.Buffer info,
			VkAllocationCallbacks allocator, LongBuffer out, Operation<Integer> original) {
		long cache = pipelineCache == 0L ? PipelineDiskCache.handle(device) : pipelineCache;
		long start = System.nanoTime();
		int result = original.call(device, cache, info, allocator, out);
		PipelineDiskCache.compiled(System.nanoTime() - start);
		return result;
	}
}
