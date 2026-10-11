package it.cosimo.vesuvio.models;

import it.cosimo.vesuvio.ui.VesuvioStats;

/**
 * Less CPU work per frame to write entity models and sign text, with the same vertices as vanilla (Entities page):
 * {@link ModelFastPath} transforms each model cube's corners once, {@link SignTextCache} keeps each sign line's glyph
 * layout instead of redoing it every frame.
 */
public final class Models {
	private Models() {
	}

	/** Creates the settings, in display order, and registers the counters. */
	public static void init() {
		VesuvioStats.register("model cubes fast", () -> ModelFastPath.cubesLastFrame);
		VesuvioStats.register("sign text cache hits", () -> SignTextCache.hitsLastFrame);
		// Touch both classes so their settings exist now, in this order.
		ModelFastPath.FAST_MODELS.get();
		SignTextCache.SIGN_TEXT_CACHE.get();
	}
}
