package it.cosimo.vesuvio.frame.mixin;

import java.nio.LongBuffer;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import it.cosimo.vesuvio.frame.FramebufferCache;
import it.cosimo.vesuvio.frame.FramebufferCacheHolder;

/**
 * Setting framebufferCache ({@link FramebufferCache}): createRenderPass begins on a kept framebuffer when one with the
 * same render pass, views and size exists, and keeps the one it creates instead of queueing it for destruction. With
 * the setting off the calls run as vanilla (and whatever is still kept is queued for destruction).
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class FrameFramebufferCacheMixin implements FramebufferCacheHolder {
	@Shadow
	@Final
	private VulkanDevice device;

	@Unique
	private FramebufferCache.Table vesuvio$framebuffers;
	/** The framebuffer of the pass being begun belongs to the table: vanilla's destroy right after must not run. */
	@Unique
	private boolean vesuvio$framebufferKept;

	@Override
	public FramebufferCache.Table vesuvio$framebuffers() {
		return this.vesuvio$framebuffers;
	}

	@WrapOperation(method = "createRenderPass", at = @At(value = "INVOKE",
			target = "Lorg/lwjgl/vulkan/VK12;vkCreateFramebuffer(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkFramebufferCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private int vesuvio$keptFramebuffer(VkDevice vkDevice, VkFramebufferCreateInfo info, VkAllocationCallbacks allocator,
			LongBuffer framebuffer, Operation<Integer> original) {
		this.vesuvio$framebufferKept = false;
		FramebufferCache.Table table = this.vesuvio$framebuffers;
		if (!FramebufferCache.SETTING.on() || !FramebufferCache.Table.cacheable(info)) {
			if (table != null && !table.isEmpty() && !FramebufferCache.SETTING.on()) {
				table.clear((VulkanCommandEncoder) (Object) this);
			}
			return original.call(vkDevice, info, allocator, framebuffer);
		}
		if (table == null) {
			table = new FramebufferCache.Table(this.device.vkDevice());
			this.vesuvio$framebuffers = table;
		}
		long kept = table.find(info);
		if (kept != VK10.VK_NULL_HANDLE) {
			framebuffer.put(framebuffer.position(), kept);
			this.vesuvio$framebufferKept = true;
			return VK10.VK_SUCCESS;
		}
		int result = original.call(vkDevice, info, allocator, framebuffer);
		if (result == VK10.VK_SUCCESS) {
			table.put(info, framebuffer.get(framebuffer.position()), (VulkanCommandEncoder) (Object) this);
			this.vesuvio$framebufferKept = true;
		}
		return result;
	}

	// The only queueForDestroy in createRenderPass: vanilla's destroy of the pass's framebuffer.
	@WrapOperation(method = "createRenderPass", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;queueForDestroy(Lcom/mojang/renderpearl/backend/vulkan/Destroyable;)V"))
	private void vesuvio$keepFramebuffer(VulkanCommandEncoder self, Destroyable destroyable, Operation<Void> original) {
		if (this.vesuvio$framebufferKept) {
			this.vesuvio$framebufferKept = false;
			return;
		}
		original.call(self, destroyable);
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void vesuvio$framebufferCounters(CallbackInfo ci) {
		if (this.vesuvio$framebuffers != null) {
			this.vesuvio$framebuffers.endFrame();
		}
	}

	// Device closing: queued before the encoder waits for the GPU and drains its destroy queue.
	@Inject(method = "destroy", at = @At("HEAD"))
	private void vesuvio$destroyFramebuffers(CallbackInfo ci) {
		if (this.vesuvio$framebuffers != null) {
			this.vesuvio$framebuffers.clear((VulkanCommandEncoder) (Object) this);
		}
	}
}
