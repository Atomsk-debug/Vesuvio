package it.cosimo.vesuvio.frame.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.frame.ModelSprites;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.WeightedVariants;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.util.random.Weighted;
import net.minecraft.util.random.WeightedList;

/** The animated sprites of a weighted model: those of every variant it may pick. */
@Mixin(WeightedVariants.class)
public abstract class FrameWeightedVariantsMixin implements ModelSprites.Holder {
	@Shadow
	@Final
	private WeightedList<BlockStateModel> list;

	@Unique
	private volatile SpriteContents @Nullable [] vesuvio$animated;
	@Unique
	private volatile boolean vesuvio$unknown;

	@Override
	public SpriteContents @Nullable [] vesuvio$animatedSprites() {
		SpriteContents[] sprites = this.vesuvio$animated;
		if (sprites == null && !this.vesuvio$unknown) {
			SpriteSet set = new SpriteSet();
			for (Weighted<BlockStateModel> entry : this.list.unwrap()) {
				if (!ModelSprites.addModel(entry.value(), set)) {
					this.vesuvio$unknown = true;
					return null;
				}
			}
			sprites = set.toArray();
			this.vesuvio$animated = sprites;
		}
		return sprites;
	}
}
