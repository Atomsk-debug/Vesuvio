package it.cosimo.vesuvio.culling;

/**
 * Culling of what can't be seen for other reasons than walls: fog (Rendering page) and small details such as sign
 * text, item frames and beacon beams (Entities page). The wall-based culling of entities is in the entities package.
 */
public final class CullingBoost {
	private CullingBoost() {
	}

	/** Creates the settings, in display order, and registers the counters. */
	public static void init() {
		FogCulling.init();
		DetailCulling.init();
	}
}
