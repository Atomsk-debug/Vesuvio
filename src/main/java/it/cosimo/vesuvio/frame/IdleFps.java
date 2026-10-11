package it.cosimo.vesuvio.frame;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

import com.mojang.blaze3d.platform.Window;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.ui.VesuvioStats;
import net.minecraft.client.FramerateLimiter;
import net.minecraft.client.Minecraft;

/**
 * Less work while nobody is looking (idea from Dynamic FPS by juliand665 and LostLuma, MIT; own code).
 * <ul>
 * <li>Window open but not focused: the frame rate is capped at {@link #LIMIT} (vanilla keeps the full rate).</li>
 * <li>Window minimized: no frames are drawn, except one a second (vanilla draws 10 a second). The loop keeps
 * running 20 times a second, so ticks, network packets and sounds go on as usual; the one frame a second keeps
 * vanilla's per-frame work alive, including "pause on lost focus".</li>
 * <li>Window hidden or fully covered ({@link #HIDDEN}): the same as minimized. SDL keeps the window's "occluded" and
 * "hidden" flags (macOS reports Cmd+H, another Space and windows covering it completely; other systems report what
 * they can); they are read once per loop. The flag must stay set for {@link #HIDDEN_CONFIRM_NS} of loops in a row
 * before it counts, each loop crediting at most {@link #HIDDEN_LOOP_CREDIT_NS}: events are polled at the start of
 * every loop, so a flag that a long stall left behind (Wayland can report a visible window as occluded while a world
 * loads) gets several polls to clear before the frame rate drops.</li>
 * <li>Single player paused with a menu open ({@link #PAUSED}): capped at {@link #PAUSED_LIMIT}; the world behind the
 * menu stands still anyway.</li>
 * </ul>
 * Focused play is never touched. Coming back is immediate: the next loop sees the focus, restore, expose or unpause
 * and runs at full rate (the wait in between is at most one idle frame, 1/limit of a second, or 50 ms when minimized
 * or hidden).
 */
public final class IdleFps {
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "idleFps", true, false);
	/** At least 3: vanilla runs at most 10 client ticks per frame, so fewer than 2 fps would slow the client down. */
	public static final VesuvioConfig.Number LIMIT = VesuvioConfig.number("frame", "idleFpsLimit", 3, 60, 10, false);
	/** A hidden or fully covered window counts as minimized. */
	public static final VesuvioConfig.Toggle HIDDEN = VesuvioConfig.toggle("frame", "hiddenWindowFps", true, false);
	/** Cap while single player is paused behind a menu. */
	public static final VesuvioConfig.Toggle PAUSED = VesuvioConfig.toggle("frame", "pausedFps", true, false);
	public static final VesuvioConfig.Number PAUSED_LIMIT = VesuvioConfig.number("frame", "pausedFpsLimit", 10, 240, 60,
			false);

	/** Loop rate while minimized: 20 a second keeps one client tick per loop (vanilla runs at most 10 a loop). */
	private static final int MINIMIZED_LOOP_RATE = 20;
	/** While minimized, still draw one real frame this often. */
	private static final long MINIMIZED_FRAME_INTERVAL_NS = 1_000_000_000L;
	/** Frame cap for the one real frame a second while hidden (vanilla's own cap for a minimized window). */
	private static final int HIDDEN_LIMIT = 10;
	/** How long the hidden or occluded flag must last, in loop time (see {@link #HIDDEN_LOOP_CREDIT_NS}). */
	private static final long HIDDEN_CONFIRM_NS = 500_000_000L;
	/**
	 * Most of one loop's time that counts towards {@link #HIDDEN_CONFIRM_NS}: a loop that stalled (a world loading)
	 * polled events only once, so it counts as a short one. At least five polls see the flag before it counts.
	 */
	private static final long HIDDEN_LOOP_CREDIT_NS = 100_000_000L;

	public static final int FULL = 0;
	public static final int UNFOCUSED = 1;
	public static final int MINIMIZED = 2;
	public static final int HIDDEN_WINDOW = 3;
	public static final int PAUSED_MENU = 4;

	/** Why the frame rate is capped: {@link #FULL}, {@link #UNFOCUSED}, ... For the settings screen and F3. */
	public static int state;
	/** Frames skipped while minimized or hidden since start-up. */
	public static long skippedFrames;

	private static long lastFrameNs = System.nanoTime();
	private static boolean skipping;
	/** The window was hidden or fully covered at the start of this loop, for long enough (see beginLoop). */
	private static boolean hidden;
	/** SDL reported the window hidden or occluded at the start of the last loop. */
	private static boolean hiddenFlagLastLoop;
	/** Loop time the flag has been set in a row, each loop counted up to HIDDEN_LOOP_CREDIT_NS. */
	private static long hiddenForNs;
	private static long lastLoopNs = System.nanoTime();

	private IdleFps() {
	}

	public static void init() {
		// Touching the class creates the settings.
		VesuvioStats.register("frame cap reason", () -> state);
		VesuvioStats.register("idle frames skipped", () -> (int) Math.min(Integer.MAX_VALUE, skippedFrames));
	}

	/**
	 * FramerateLimitTracker.getFramerateLimit: vanilla's limit, capped while unfocused, hidden or paused (not during the
	 * benchmark, which may run with the window watched from another screen).
	 */
	public static int limit(int vanilla, Minecraft minecraft) {
		Window window = minecraft.getWindow();
		boolean benchmark = it.cosimo.vesuvio.ui.VesuvioBenchmark.running();
		boolean idle = SETTING.on() && !benchmark;
		if (idle && window.isIconified()) {
			state = MINIMIZED;
			return vanilla;
		}
		if (hidden && HIDDEN.on() && !benchmark) {
			state = HIDDEN_WINDOW;
			return Math.min(vanilla, HIDDEN_LIMIT);
		}
		if (idle && !window.isFocused()) {
			state = UNFOCUSED;
			return Math.min(vanilla, LIMIT.get());
		}
		if (PAUSED.on() && !benchmark && minecraft.isPaused() && minecraft.gui.screen() != null) {
			state = PAUSED_MENU;
			return Math.min(vanilla, PAUSED_LIMIT.get());
		}
		state = FULL;
		return vanilla;
	}

	/**
	 * Minecraft.runTick, start: decide whether this loop draws. While minimized or hidden, a loop that does not draw
	 * also skips the texture-animation tick, as vanilla does one animation tick per drawn frame at most (so no GPU work
	 * piles up between submits).
	 */
	public static void beginLoop(Window window) {
		long now = System.nanoTime();
		boolean flag = HIDDEN.on() && !it.cosimo.vesuvio.ui.VesuvioBenchmark.running() && WindowFlags.hiddenOrOccluded(window);
		if (!flag) {
			hiddenForNs = 0L;
		} else if (hiddenFlagLastLoop) {
			hiddenForNs += Math.min(now - lastLoopNs, HIDDEN_LOOP_CREDIT_NS);
		}
		hiddenFlagLastLoop = flag;
		lastLoopNs = now;
		hidden = flag && hiddenForNs >= HIDDEN_CONFIRM_NS;
		boolean away = SETTING.on() && window.isIconified() || hidden;
		skipping = away && now - lastFrameNs < MINIMIZED_FRAME_INTERVAL_NS;
		if (skipping) {
			state = hidden && !window.isIconified() ? HIDDEN_WINDOW : MINIMIZED;
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

	/**
	 * SDL_GetWindowFlags, looked up at run time (LWJGL's SDL binding ships with the game). Render thread, which is the
	 * thread that owns the window. Any failure means "visible", which is vanilla's behaviour.
	 */
	static final class WindowFlags {
		/** SDL3: SDL_WINDOW_OCCLUDED 0x4, SDL_WINDOW_HIDDEN 0x8 (read from LWJGL when it has them). */
		private static final long OCCLUDED = constant("SDL_WINDOW_OCCLUDED", 0x4L);
		private static final long HIDDEN_FLAG = constant("SDL_WINDOW_HIDDEN", 0x8L);
		private static final MethodHandle GET_FLAGS = find();
		private static boolean failed;

		private WindowFlags() {
		}

		private static Class<?> video() throws ClassNotFoundException {
			return Class.forName("org.lwjgl.sdl.SDLVideo", false, IdleFps.class.getClassLoader());
		}

		private static long constant(String name, long fallback) {
			try {
				return video().getField(name).getLong(null);
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				return fallback;
			}
		}

		private static MethodHandle find() {
			try {
				return MethodHandles.publicLookup().findStatic(video(), "SDL_GetWindowFlags",
						MethodType.methodType(long.class, long.class));
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				Vesuvio.LOGGER.info("Vesuvio: window flags not readable, hidden windows are treated as visible");
				return null;
			}
		}

		static boolean hiddenOrOccluded(Window window) {
			if (GET_FLAGS == null || failed) {
				return false;
			}
			try {
				long flags = (long) GET_FLAGS.invokeExact(window.handle());
				return (flags & (OCCLUDED | HIDDEN_FLAG)) != 0L;
			} catch (Throwable t) {
				failed = true;
				Vesuvio.LOGGER.warn("Vesuvio: reading the window flags failed, hidden windows are treated as visible", t);
				return false;
			}
		}
	}
}
