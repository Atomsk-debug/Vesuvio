package it.cosimo.vesuvio.frame.mixin;

import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;

import it.cosimo.vesuvio.frame.PipelineDiskCache;

/** Setting pipelineCache: opens the disk-backed pipeline cache with the device and saves it when the device closes. */
@Mixin(VulkanDevice.class)
public abstract class FramePipelineCacheDeviceMixin {
	@Shadow
	@Final
	private VkDevice vkDevice;

	// Just before the constructor frees the physical device's properties (the cache header needs them).
	@Inject(method = "<init>", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;close()V"))
	private void vesuvio$openPipelineCache(CallbackInfo ci, @Local(argsOnly = true) VulkanPhysicalDevice physicalDevice) {
		PipelineDiskCache.open(this.vkDevice, physicalDevice.vkPhysicalDeviceProperties());
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void vesuvio$savePipelineCache(CallbackInfo ci) {
		PipelineDiskCache.close(this.vkDevice);
	}
}
