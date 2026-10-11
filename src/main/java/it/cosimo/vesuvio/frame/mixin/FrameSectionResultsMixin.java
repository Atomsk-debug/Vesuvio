package it.cosimo.vesuvio.frame.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.frame.SpriteCollectorHolder;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.chunk.SectionCompiler;

/** Carries the compile's animated sprites to the mesh. */
@Mixin(SectionCompiler.Results.class)
public abstract class FrameSectionResultsMixin implements SpriteCollectorHolder {
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
}
