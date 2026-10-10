package it.cosimo.vesuvio.frame;

import org.jspecify.annotations.Nullable;

import net.minecraft.client.renderer.texture.SpriteContents;

/** Added to SpriteContents.AnimationState: its sprite, and whether a redraw was skipped while hidden. */
public interface AnimationStateExt {
	@Nullable
	SpriteContents vesuvio$owner();

	void vesuvio$setOwner(SpriteContents owner);

	boolean vesuvio$stale();

	void vesuvio$setStale(boolean stale);
}
