package it.cosimo.vesuvio.frame;

/**
 * The "frame" page: per-frame work vanilla does for nothing (animated textures nobody sees, full frame rate in an
 * unfocused or minimized window) and the MoltenVK backend settings.
 */
public final class FrameBoost {
	private FrameBoost() {
	}

	/** Creates this page's settings, in display order. */
	public static void init() {
		AnimatedTextures.init();
		IdleFps.init();
		MoltenVkSettings.init();
	}
}
