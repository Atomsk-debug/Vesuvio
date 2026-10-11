package it.cosimo.vesuvio.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.Vesuvio;
import net.minecraft.client.renderer.extract.LevelExtractor;

/**
 * LevelExtractor.setLevel: the client joins a world, changes dimension, or leaves (null). Vesuvio's per-world render
 * state is let go here, so nothing keeps the old world's sections, meshes or render targets alive. Render thread,
 * outside any render pass. Only releases references; vanilla's call runs unchanged.
 */
@Mixin(LevelExtractor.class)
public abstract class LevelChangeMixin {
	@Inject(method = "setLevel", at = @At("HEAD"))
	private void vesuvio$levelChanged(CallbackInfo ci) {
		Vesuvio.levelChanged();
	}
}
