package it.cosimo.vesuvio.frame;

/** Added to VulkanCommandEncoder by FrameFramebufferCacheMixin: its kept framebuffers (null until the first one). */
public interface FramebufferCacheHolder {
	FramebufferCache.Table vesuvio$framebuffers();
}
