package it.cosimo.vesuvio.draws;

/**
 * Per-draw cost in the Vulkan backend: binds and descriptor sets the GPU already has ({@link RedundantBinds}), and
 * the submit timers that show where the end of each frame goes ({@link SubmitTimers}).
 */
public final class Draws {
	private Draws() {
	}

	/** Creates the settings and registers the counters (Vesuvio.onInitializeClient). */
	public static void init() {
		RedundantBinds.init();
		SubmitTimers.init();
	}
}
