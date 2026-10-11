package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import it.cosimo.vesuvio.frame.MappedVertexPools;

/** Setting mappedVertexPools: publishes the frame's counter at submit (only counts). */
@Mixin(VulkanCommandEncoder.class)
public abstract class FrameVertexCountersMixin {
	@Inject(method = "submit", at = @At("HEAD"))
	private void vesuvio$vertexCounters(CallbackInfo ci) {
		MappedVertexPools.endFrame();
	}
}
