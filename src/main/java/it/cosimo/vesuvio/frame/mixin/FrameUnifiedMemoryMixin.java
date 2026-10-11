package it.cosimo.vesuvio.frame.mixin;

import org.lwjgl.vulkan.VkDevice;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import it.cosimo.vesuvio.frame.MappedVertexPools;

/** Setting mappedVertexPools: reads the GPU's memory heaps once the device exists (only reads). */
@Mixin(VulkanDevice.class)
public abstract class FrameUnifiedMemoryMixin {
	@Shadow
	@Final
	private VkDevice vkDevice;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void vesuvio$readMemoryHeaps(CallbackInfo ci) {
		MappedVertexPools.deviceCreated(this.vkDevice);
	}
}
