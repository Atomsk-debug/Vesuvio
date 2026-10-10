package it.cosimo.vesuvio.clears.mixin;

import java.nio.ByteBuffer;

import org.joml.Vector4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
import com.mojang.renderpearl.backend.api.RenderPassBackend;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;

import it.cosimo.vesuvio.clears.ClearFusion;

/**
 * Setting clearFusion: the command encoder's front end, where every caller's clears, passes and copies arrive
 * (vanilla's, Etna's and other mods'). Each hook sits on the backend call, after the front end's own checks, so
 * a call that would throw still throws the same way.
 */
@Mixin(FrontendCommandEncoder.class)
public abstract class ClearsFrontendEncoderMixin {
	@Shadow
	@Final
	private CommandEncoderBackend backend;

	@WrapOperation(method = "clearColorTexture", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;clearColorTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;)V"))
	private void vesuvio$deferColorClear(CommandEncoderBackend instance, GpuTexture texture, Vector4fc color,
			Operation<Void> original) {
		if (!ClearFusion.deferColor(instance, texture, color)) {
			original.call(instance, texture, color);
		}
	}

	@WrapOperation(method = "clearColorAndDepthTextures", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;clearColorAndDepthTextures(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lorg/joml/Vector4fc;Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void vesuvio$deferColorAndDepthClear(CommandEncoderBackend instance, GpuTexture colorTexture, Vector4fc color,
			GpuTexture depthTexture, double depth, Operation<Void> original) {
		if (!ClearFusion.deferColorAndDepth(instance, colorTexture, color, depthTexture, depth)) {
			original.call(instance, colorTexture, color, depthTexture, depth);
		}
	}

	@WrapOperation(method = "clearDepthTexture", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;clearDepthTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;D)V"))
	private void vesuvio$deferDepthClear(CommandEncoderBackend instance, GpuTexture texture, double depth,
			Operation<Void> original) {
		if (!ClearFusion.deferDepth(instance, texture, depth)) {
			original.call(instance, texture, depth);
		}
	}

	@WrapOperation(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/backend/api/RenderPassBackend;"))
	private RenderPassBackend vesuvio$fuseClears(CommandEncoderBackend instance, RenderPassDescriptor descriptor,
			Operation<RenderPassBackend> original) {
		return original.call(instance, ClearFusion.beforePass(instance, descriptor));
	}

	@Inject(method = "writeToTexture", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;writeToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Ljava/nio/ByteBuffer;IIIIII)V"))
	private void vesuvio$beforeWriteToTexture(GpuTexture destination, ByteBuffer source, int mipLevel, int depthOrLayer,
			int destX, int destY, int width, int height, CallbackInfo ci) {
		ClearFusion.beforeTransfer(this.backend, destination, null);
	}

	@Inject(method = "copyBufferToTexture", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;copyBufferToTexture(Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;IIIILcom/mojang/renderpearl/api/textures/GpuTexture;IIIIII)V"))
	private void vesuvio$beforeCopyBufferToTexture(GpuBufferSlice source, int sourceX, int sourceY, int sourceWidth,
			int sourceHeight, GpuTexture destination, int destinationX, int destinationY, int copyWidth, int copyHeight,
			int mipLevel, int arrayLayer, CallbackInfo ci) {
		ClearFusion.beforeTransfer(this.backend, destination, null);
	}

	@Inject(method = "copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JIIIII)V",
			at = @At(value = "INVOKE",
					target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;copyTextureToBuffer(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/buffers/GpuBuffer;JIIIII)V"))
	private void vesuvio$beforeCopyTextureToBuffer(GpuTexture source, GpuBuffer destination, long offset, int mipLevel,
			int x, int y, int width, int height, CallbackInfo ci) {
		ClearFusion.beforeTransfer(this.backend, source, null);
	}

	@Inject(method = "copyTextureToTexture", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;copyTextureToTexture(Lcom/mojang/renderpearl/api/textures/GpuTexture;Lcom/mojang/renderpearl/api/textures/GpuTexture;IIIIIII)V"))
	private void vesuvio$beforeCopyTextureToTexture(GpuTexture source, GpuTexture destination, int mipLevel, int destX,
			int destY, int sourceX, int sourceY, int width, int height, CallbackInfo ci) {
		ClearFusion.beforeTransfer(this.backend, source, destination);
	}

	// The end of the frame: whatever is still pending is sent (dead candidates carry over, see ClearFusion).
	@Inject(method = "submit", at = @At("HEAD"))
	private void vesuvio$beforeSubmit(CallbackInfo ci) {
		ClearFusion.beforeSubmit(this.backend);
	}
}
