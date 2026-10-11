package it.cosimo.vesuvio.frame.mixin;

import java.nio.ByteBuffer;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;

import it.cosimo.vesuvio.frame.TransferBarriers;

/**
 * Setting mergeTransferBarriers ({@link TransferBarriers}): the barrier after writeToBuffer, copyToBuffer,
 * writeToTexture and copyBufferToTexture is held back, and the held barrier is recorded, unchanged, before every
 * other command the encoder records into the frame's command buffer and before that buffer ends. With the setting
 * off nothing is held and every method runs exactly as vanilla.
 */
@Mixin(VulkanCommandEncoder.class)
public abstract class FrameTransferBarrierMixin {
	@Shadow
	@Nullable
	private VkCommandBuffer currentCommandBuffer;

	@Unique
	private final TransferBarriers.Tracker vesuvio$barriers = new TransferBarriers.Tracker();

	/** Records the held barrier, if any: the same call vanilla makes after each copy. */
	@Unique
	private void vesuvio$flushBarrier() {
		if (!this.vesuvio$barriers.pending) {
			return;
		}
		this.vesuvio$barriers.clear();
		// A barrier is only held after a copy was recorded into the current command buffer, and only
		// endCommandBuffer (which flushes first) clears it, so this is never null in practice.
		if (this.currentCommandBuffer != null) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VulkanCommandEncoder.memoryBarrier(this.currentCommandBuffer, stack);
			}
		}
	}

	// Everything that is not a tracked copy: the held barrier goes first. endCommandBuffer covers submit, execute
	// (the swapchain blit and other mods' command buffers) and the semaphore operations.
	@Inject(method = {"clearColorTexture", "clearColorAndDepthTextures", "clearDepthTexture", "copyTextureToBuffer",
			"copyTextureToTexture", "writeTimestamp", "endCommandBuffer"}, at = @At("HEAD"))
	private void vesuvio$barrierBeforeCommand(CallbackInfo ci) {
		vesuvio$flushBarrier();
	}

	@Inject(method = "createRenderPass", at = @At("HEAD"))
	private void vesuvio$barrierBeforePass(CallbackInfoReturnable<RenderPassBackend> cir) {
		vesuvio$flushBarrier();
	}

	@Inject(method = "submit", at = @At("HEAD"))
	private void vesuvio$countFrame(CallbackInfo ci) {
		this.vesuvio$barriers.endFrame();
	}

	// The tracked copies. Each one says what it reads and writes before its command is recorded; the held barrier
	// is recorded first when it overlaps (or the setting is off).

	@Inject(method = "copyToBuffer", at = @At("HEAD"))
	private void vesuvio$beforeCopyToBuffer(GpuBufferSlice source, GpuBufferSlice target, CallbackInfo ci) {
		TransferBarriers.Tracker t = this.vesuvio$barriers;
		if (t.begin()) {
			long src = ((VulkanGpuBuffer) source.buffer()).vkBuffer();
			long dst = ((VulkanGpuBuffer) target.buffer()).vkBuffer();
			t.source(src, source.offset(), source.offset() + source.length());
			// Vanilla copies source.length() bytes.
			t.destination(dst, target.offset(), target.offset() + source.length());
			if (t.mustFlush()) {
				vesuvio$flushBarrier();
			}
		} else {
			vesuvio$flushBarrier();
		}
	}

	@Inject(method = "copyBufferToTexture", at = @At("HEAD"))
	private void vesuvio$beforeCopyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth,
			int sourceHeight, GpuTexture destination, int destinationX, int destinationY, int copyWidth, int copyHeight,
			int mipLevel, int arrayLayer, CallbackInfo ci) {
		TransferBarriers.Tracker t = this.vesuvio$barriers;
		if (t.begin()) {
			// The rows read can reach past the slice (row length and skipped texels), so the whole rest of the
			// buffer counts as read.
			t.source(((VulkanGpuBuffer) source.buffer()).vkBuffer(), source.offset(), Long.MAX_VALUE);
			t.destinationImage(((VulkanGpuTexture) destination).vkImage(), mipLevel, arrayLayer, destinationX,
					destinationY, copyWidth, copyHeight);
			if (t.mustFlush()) {
				vesuvio$flushBarrier();
			}
		} else {
			vesuvio$flushBarrier();
		}
	}

	@Inject(method = "writeToBuffer", at = @At("HEAD"))
	private void vesuvio$beforeWriteToBuffer(GpuBufferSlice destination, ByteBuffer data, CallbackInfo ci) {
		TransferBarriers.Tracker t = this.vesuvio$barriers;
		if (t.begin()) {
			long dst = ((VulkanGpuBuffer) destination.buffer()).vkBuffer();
			t.destination(dst, destination.offset(), destination.offset() + data.remaining());
			// The source (fresh transient staging memory) is known after uploadStaging, below.
		} else {
			vesuvio$flushBarrier();
		}
	}

	@Inject(method = "writeToTexture", at = @At("HEAD"))
	private void vesuvio$beforeWriteToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer,
			int destX, int destY, int width, int height, CallbackInfo ci) {
		TransferBarriers.Tracker t = this.vesuvio$barriers;
		if (t.begin()) {
			t.destinationImage(((VulkanGpuTexture) destination).vkImage(), mipLevel, depthOrLayer, destX, destY, width,
					height);
		} else {
			vesuvio$flushBarrier();
		}
	}

	// writeToBuffer / writeToTexture: uploadStaging only copies the bytes into transient memory on the CPU (no
	// command), so the check can run after it and before vkCmdCopyBuffer / vkCmdCopyBufferToImage.
	@ModifyExpressionValue(method = {"writeToBuffer", "writeToTexture"}, at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanTransientMemory;uploadStaging(Ljava/nio/ByteBuffer;JI)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
	private GpuBufferSlice vesuvio$checkStagingSource(GpuBufferSlice staging) {
		TransferBarriers.Tracker t = this.vesuvio$barriers;
		if (t.defer) {
			long src = ((VulkanGpuBuffer) staging.buffer()).vkBuffer();
			t.source(src, staging.offset(), staging.offset() + staging.length());
			if (t.mustFlush()) {
				vesuvio$flushBarrier();
			}
		}
		return staging;
	}

	// The barrier each tracked copy records after its command: held back when the copy was set up to defer it.
	// (Per copy and upload, so these two hooks are a WrapWithCondition and a ModifyExpressionValue: no Operation
	// argument array per call.)
	@WrapWithCondition(method = {"writeToBuffer", "copyToBuffer", "writeToTexture", "copyBufferToTexture"},
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/vulkan/VulkanCommandEncoder;memoryBarrier(Lorg/lwjgl/system/MemoryStack;)V"))
	private boolean vesuvio$holdBarrier(VulkanCommandEncoder encoder, MemoryStack stack) {
		TransferBarriers.Tracker t = this.vesuvio$barriers;
		if (t.defer) {
			t.commit();
			return false;
		}
		return true;
	}
}
