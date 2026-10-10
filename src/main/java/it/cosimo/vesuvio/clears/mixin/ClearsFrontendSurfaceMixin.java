package it.cosimo.vesuvio.clears.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import com.mojang.renderpearl.frontend.FrontendGpuSurface;

import it.cosimo.vesuvio.clears.ClearFusion;

/** Setting clearFusion: the copy to the window reads the texture, so its pending clear is sent first. */
@Mixin(FrontendGpuSurface.class)
public abstract class ClearsFrontendSurfaceMixin {
	@Inject(method = "blitFromTexture", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/backend/api/GpuSurfaceBackend;blitFromTexture(Lcom/mojang/renderpearl/backend/api/CommandEncoderBackend;Lcom/mojang/renderpearl/api/textures/GpuTextureView;)V"))
	private void vesuvio$beforeBlit(CommandEncoder commandEncoder, GpuTextureView textureView, CallbackInfo ci) {
		// The front end has already checked that the encoder is a FrontendCommandEncoder.
		ClearFusion.beforeTransfer(((FrontendCommandEncoder) commandEncoder).backend(), textureView.texture(), null);
	}
}
