package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.resources.model.sprite.AtlasManager;

/** One texture tick starts: advance the "seen" clock. */
@Mixin(AtlasManager.class)
public abstract class FrameAtlasManagerMixin {
	@Inject(method = "tick", at = @At("HEAD"))
	private void vesuvio$beginTextureTick(CallbackInfo ci) {
		AnimatedTextures.beginTick();
	}
}
