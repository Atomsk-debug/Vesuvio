package it.cosimo.vesuvio.frame;

import com.mojang.blaze3d.platform.Window;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.client.FramerateLimiter;

/**
 * Less work while nobody is looking (idea from Dynamic FPS by juliand665 and LostLuma, MIT; own code).
 * <ul>
 * <li>Window open but not focused: the frame rate is capped at {@link #LIMIT} (vanilla keeps the full rate).</li>
 * <li>Window minimized: no frames are drawn, except one a second (vanilla draws 10 a second). The loop keeps
 * running 20 times a second, so ticks, network packets and sounds go on as usual; the one frame a second keeps
 * vanilla's per-frame work alive, including "pause on lost focus".</li>
 * </ul>
 * Focused play is never touched. Coming back is immediate: the next loop sees the focus or restore event and runs
 * at full rate (the wait in between is at most one idle frame, 1/limit of a second, or 50 ms when minimized).
 */
public final class IdleFps {
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "idleFps", true, false);
	/** At least 3: vanilla runs at most 10 client ticks per frame, so fewer than 2 fps would slow the client down. */
	public static final VesuvioConfig.Number LIMIT = VesuvioConfig.number("frame", "idleFpsLimit", 3, 60, 10, false);

	/** Loop rate while minimized: 20 a second keeps one client tick per loop (vanilla runs at most 10 a loop). */
	private static final int MINIMIZED_LOOP_RATE = 20;
	/** While minimized, still draw one real frame this often. */
	private static final long MINIMIZED_FRAME_INTERVAL_NS = 1_000_000_000L;

	/** 0 = full rate, 1 = unfocused cap, 2 = minimized (frames skipped). For the settings screen. */
	public static int state;
	/** Frames skipped while minimized since start-up. */
	public static long skippedFrames;

	private static long lastFrameNs = System.nanoTime();
	private static boolean skipping;

	private IdleFps() {
	}

	public static void init() {
		// Touching the class creates the settings.
	}

	/**
	 * FramerateLimitTracker.getFramerateLimit: vanilla's limit, capped while unfocused (not during the benchmark, which
	 * may run with the window watched from another screen).
	 */
	public static int limit(int vanilla, Window window) {
		boolean on = SETTING.on() && !it.cosimo.vesuvio.ui.VesuvioBenchmark.running();
		state = !on ? 0 : window.isIconified() ? 2 : window.isFocused() ? 0 : 1;
		return state == 1 ? Math.min(vanilla, LIMIT.get()) : vanilla;
	}

	/**
	 * Minecraft.runTick, start: decide whether this loop draws. While minimized, a loop that does not draw also skips
	 * the texture-animation tick, as vanilla does one animation tick per drawn frame at most (so no GPU work piles up
	 * between submits).
	 */
	public static void beginLoop(Window window) {
		skipping = SETTING.on() && window.isIconified()
				&& System.nanoTime() - lastFrameNs < MINIMIZED_FRAME_INTERVAL_NS;
		if (skipping) {
			state = 2;
		}
	}

	/** True when this loop draws nothing. */
	public static boolean skipping() {
		return skipping;
	}

	/** Minecraft.runTick, instead of renderFrame: wait as a frame would (vanilla's limiter, continuous pacing). */
	public static void skipFrame() {
		skippedFrames++;
		FramerateLimiter.limitDisplayFPS(MINIMIZED_LOOP_RATE);
	}

	public static void frameRendered() {
		lastFrameNs = System.nanoTime();
	}
}
