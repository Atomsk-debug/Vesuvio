package it.cosimo.vesuvio.clears.mixin;

import org.joml.Vector4fc;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;

/** Setting clearFusion: what a clear recorded in the middle of a pass needs from the Vulkan encoder. */
@Mixin(VulkanCommandEncoder.class)
public interface ClearsVulkanEncoderAccessor {
	@Invoker("commandBuffer")
	VkCommandBuffer vesuvio$commandBuffer();

	@Invoker("clearColorTextureUnsynced")
	void vesuvio$clearColorTextureUnsynced(MemoryStack stack, GpuTexture colorTexture, Vector4fc clearColor);

	@Accessor("device")
	VulkanDevice vesuvio$device();

	@Accessor("currentRenderPass")
	@Nullable
	VulkanRenderPass vesuvio$currentRenderPass();
}
