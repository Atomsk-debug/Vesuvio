package it.cosimo.vesuvio.clears.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.frontend.FrontendRenderPass;
import com.mojang.renderpearl.util.TextureViewAndSampler;

import it.cosimo.vesuvio.clears.ClearFusion;

/**
 * Setting clearFusion: every texture a pass samples is bound here (the public setUniform for textures and
 * setPipeline's re-send of stored uniforms both end in this private method), before any draw can use it.
 */
@Mixin(FrontendRenderPass.class)
public abstract class ClearsFrontendRenderPassMixin {
	@Inject(method = "setUniform(Ljava/lang/String;Ljava/lang/Object;)V", at = @At("HEAD"))
	private void vesuvio$beforeSample(String name, Object value, CallbackInfo ci) {
		if (value instanceof TextureViewAndSampler texture) {
			ClearFusion.beforeSample(texture.view());
		}
	}
}
