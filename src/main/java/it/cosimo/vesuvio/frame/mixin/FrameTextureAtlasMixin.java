package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/** Hidden sprites are skipped only while the block atlas cycles (never during the first upload after a reload). */
@Mixin(TextureAtlas.class)
public abstract class FrameTextureAtlasMixin {
	@Shadow
	@Final
	private Identifier location;

	@SuppressWarnings("deprecation")
	@Inject(method = "cycleAnimationFrames", at = @At("HEAD"))
	private void vesuvio$beginCycle(CallbackInfo ci) {
		AnimatedTextures.beginAtlas(TextureAtlas.LOCATION_BLOCKS.equals(this.location));
	}

	@Inject(method = "cycleAnimationFrames", at = @At("RETURN"))
	private void vesuvio$endCycle(CallbackInfo ci) {
		AnimatedTextures.endAtlas();
	}
}
