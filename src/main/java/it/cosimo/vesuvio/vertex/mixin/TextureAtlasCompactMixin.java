package it.cosimo.vesuvio.vertex.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.vertex.CompactVertices;
import net.minecraft.client.renderer.texture.SpriteLoader;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.resources.Identifier;

/**
 * Setting compactTerrainVertices (only in the game when it was on at start-up): tells CompactVertices the size of
 * each new block atlas, so that one too large for 16-bit texture coordinates switches the next start to float
 * ones. Only reads.
 */
@Mixin(TextureAtlas.class)
public abstract class TextureAtlasCompactMixin {
	@Shadow
	@Final
	private Identifier location;

	@Inject(method = "upload", at = @At("HEAD"))
	private void vesuvio$noteAtlasSize(SpriteLoader.Preparations preparations, CallbackInfo ci) {
		if (TextureAtlas.LOCATION_BLOCKS.equals(this.location)) {
			CompactVertices.noteBlockAtlas(preparations.width(), preparations.height());
		}
	}
}
