package it.cosimo.vesuvio.clears.mixin;

import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSubpassBeginInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import it.cosimo.vesuvio.clears.ClearFusion;

/**
 * Setting clearFusion: remembers the framebuffer and render area of the pass being begun, so a clear that has
 * to be recorded in the middle of it can begin the same pass again (ClearFusion.splitPass). Only reads.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class ClearsVulkanEncoderMixin {
	@WrapOperation(method = "createRenderPass", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCmdBeginRenderPass2(Lorg/lwjgl/vulkan/VkCommandBuffer;Lorg/lwjgl/vulkan/VkRenderPassBeginInfo;Lorg/lwjgl/vulkan/VkSubpassBeginInfo;)V"))
	private void vesuvio$rememberPassBegin(VkCommandBuffer commandBuffer, VkRenderPassBeginInfo beginInfo,
			VkSubpassBeginInfo subpassBeginInfo, Operation<Void> original,
			@Local(argsOnly = true) RenderPassDescriptor descriptor) {
		ClearFusion.onVulkanPassBegin(descriptor, beginInfo);
		original.call(commandBuffer, beginInfo, subpassBeginInfo);
	}
}
