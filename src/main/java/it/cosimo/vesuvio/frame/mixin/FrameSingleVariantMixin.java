package it.cosimo.vesuvio.frame.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.frame.ModelSprites;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.dispatch.SingleVariant;
import net.minecraft.client.renderer.texture.SpriteContents;

/** The animated sprites of a single-variant model: those of its one part. */
@Mixin(SingleVariant.class)
public abstract class FrameSingleVariantMixin implements ModelSprites.Holder {
	@Shadow
	@Final
	private BlockStateModelPart model;

	@Unique
	private volatile SpriteContents @Nullable [] vesuvio$animated;

	@Override
	public SpriteContents @Nullable [] vesuvio$animatedSprites() {
		SpriteContents[] sprites = this.vesuvio$animated;
		if (sprites == null) {
			SpriteSet set = new SpriteSet();
			ModelSprites.addPart(this.model, set);
			sprites = set.toArray();
			this.vesuvio$animated = sprites;
		}
		return sprites;
	}
}
