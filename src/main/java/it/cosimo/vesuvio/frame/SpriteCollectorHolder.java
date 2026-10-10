package it.cosimo.vesuvio.frame;

import org.jspecify.annotations.Nullable;

/** Added to SectionCompiler.Results and ModelBlockRenderer: the animated sprites a section compile meets. */
public interface SpriteCollectorHolder {
	@Nullable
	SpriteSet vesuvio$sprites();

	void vesuvio$setSprites(@Nullable SpriteSet sprites);
}
