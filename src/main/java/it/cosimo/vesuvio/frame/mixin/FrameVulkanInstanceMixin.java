package it.cosimo.vesuvio.frame.mixin;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.VulkanInstance;

import it.cosimo.vesuvio.frame.MoltenVkSettings;

/** macOS / MoltenVK: pass MoltenVK settings through VK_EXT_layer_settings when the instance is created. */
@Mixin(VulkanInstance.class)
public abstract class FrameVulkanInstanceMixin {
	@WrapOperation(method = "<init>", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateInstance(Lorg/lwjgl/vulkan/VkInstanceCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Lorg/lwjgl/PointerBuffer;)I"))
	private int vesuvio$createWithMoltenVkSettings(VkInstanceCreateInfo info, VkAllocationCallbacks allocator,
			PointerBuffer instance, Operation<Integer> original) {
		return MoltenVkSettings.createInstance(info, i -> original.call(i, allocator, instance));
	}
}
