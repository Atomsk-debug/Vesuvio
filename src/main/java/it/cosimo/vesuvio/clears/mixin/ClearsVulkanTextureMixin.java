package it.cosimo.vesuvio.clears.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;

import it.cosimo.vesuvio.clears.ClearFusion;

/**
 * Setting clearFusion: the image is queued for destruction once the texture and its last view are closed. From
 * then on nothing can read it, so a clear still pending for it is dropped (never recorded on a freed image).
 */
@Mixin(VulkanGpuTexture.class)
public abstract class ClearsVulkanTextureMixin {
	@Inject(method = "removeViews", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;queueForDestroy(Lcom/mojang/renderpearl/backend/vulkan/Destroyable;)V"))
	private void vesuvio$dropPendingClear(CallbackInfo ci) {
		ClearFusion.onTextureDestroyed((GpuTexture) (Object) this);
	}
}
