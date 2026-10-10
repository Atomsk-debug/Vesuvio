package it.cosimo.vesuvio.frame.mixin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.frame.ModelSprites;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.multipart.MultiPartModel;
import net.minecraft.client.renderer.texture.SpriteContents;

/** The animated sprites of a multipart model (one per block state): those of every part this state selects. */
@Mixin(MultiPartModel.class)
public abstract class FrameMultiPartModelMixin implements ModelSprites.Holder {
	@Shadow
	private @Nullable List<BlockStateModel> models;

	@Unique
	private volatile SpriteContents @Nullable [] vesuvio$animated;
	@Unique
	private volatile boolean vesuvio$unknown;

	@Override
	public SpriteContents @Nullable [] vesuvio$animatedSprites() {
		SpriteContents[] sprites = this.vesuvio$animated;
		if (sprites == null && !this.vesuvio$unknown) {
			// The selected parts are filled in on the first collectParts (vanilla does the same, lazily).
			ModelSprites.partsOf((BlockStateModel) this);
			List<BlockStateModel> selected = this.models;
			if (selected == null) {
				this.vesuvio$unknown = true;
				return null;
			}
			SpriteSet set = new SpriteSet();
			for (BlockStateModel model : selected) {
				if (!ModelSprites.addModel(model, set)) {
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
