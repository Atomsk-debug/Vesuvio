package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;

import it.cosimo.vesuvio.frame.FramebufferCache;
import it.cosimo.vesuvio.frame.FramebufferCacheHolder;

/**
 * Setting framebufferCache ({@link FramebufferCache}): a view being closed takes every kept framebuffer naming it
 * with it, queued on the destroy queue just before the view itself (runs whatever the setting, so nothing kept while
 * it was on can outlive its view).
 */
@Mixin(VulkanGpuTextureView.class)
public abstract class FrameTextureViewCloseMixin {
	@Shadow
	@Final
	private VulkanDevice device;

	@Shadow
	private boolean closed;

	@Shadow
	public abstract long vkImageView();

	@Inject(method = "close", at = @At("HEAD"))
	private void vesuvio$dropKeptFramebuffers(CallbackInfo ci) {
		if (this.closed) {
			return;
		}
		VulkanCommandEncoder encoder = this.device.createCommandEncoder();
		FramebufferCache.Table table = ((FramebufferCacheHolder) encoder).vesuvio$framebuffers();
		if (table != null && !table.isEmpty()) {
			table.viewClosed(this.vkImageView(), encoder);
		}
	}
}
