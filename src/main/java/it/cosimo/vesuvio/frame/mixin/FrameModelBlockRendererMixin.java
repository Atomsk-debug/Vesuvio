package it.cosimo.vesuvio.frame.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import it.cosimo.vesuvio.frame.SpriteCollectorHolder;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/**
 * Every block quad of a section compile passes here; the section compiler's renderer carries a sprite set (other
 * renderers, e.g. moving blocks, carry none and are covered where their quads are written).
 */
@Mixin(ModelBlockRenderer.class)
public abstract class FrameModelBlockRendererMixin implements SpriteCollectorHolder {
	@Unique
	private @Nullable SpriteSet vesuvio$spriteSet;

	@Override
	public @Nullable SpriteSet vesuvio$sprites() {
		return this.vesuvio$spriteSet;
	}

	@Override
	public void vesuvio$setSprites(@Nullable SpriteSet sprites) {
		this.vesuvio$spriteSet = sprites;
	}

	@ModifyVariable(method = "putQuadWithTint", at = @At("HEAD"), argsOnly = true)
	private BakedQuad vesuvio$collectSprite(BakedQuad quad) {
		SpriteSet sprites = this.vesuvio$spriteSet;
		if (sprites != null) {
			sprites.add(quad.materialInfo().sprite());
		}
		return quad;
	}
}
