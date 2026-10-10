package it.cosimo.vesuvio.draws;

import it.cosimo.vesuvio.ui.VesuvioStats;

/**
 * Where the end of a frame goes: two timers in VulkanCommandEncoder.submit, which the game calls once a frame after
 * recording all of it (VulkanCommandEncoderMixin). Measurement only; nothing about the frame changes.
 *
 * <ul>
 * <li><b>encode</b>: the time spent inside vkQueueSubmit (VulkanQueue.Submission.close). On macOS with MoltenVK's
 * default synchronous submits this is where every Vulkan command of the frame is turned into Metal, and also where
 * MoltenVK waits for a free swapchain image when the display is the limit (vsync). With asyncSubmit on, that work
 * moves to MoltenVK's own thread and this timer only sees the hand-over. On Windows and Linux it is the driver's
 * submit, usually small.</li>
 * <li><b>wait</b>: the time spent waiting for the GPU to finish the frame before last (the game keeps two frames in
 * flight), before the next frame can start. Large when the GPU is the limit, near zero when the CPU is.</li>
 * </ul>
 *
 * <p>Shown on F3 and in the settings screen's counters as averages over the last second ("submit encode us",
 * "submit wait us", and "gpu bound" 1 or 0 for {@link #gpuBound}), and in the benchmark table as ms per frame for
 * each step. {@link #gpuBound} turns the same numbers into the face culling split decision on the Mac
 * (FaceCulling).
 */
public final class SubmitTimers {
	private static final long WINDOW_NS = 1_000_000_000L;
	/** The GPU counts as the limit from this share of the frame spent waiting for it, and stops below the second. */
	private static final double GPU_BOUND_ENTER = 0.10;
	private static final double GPU_BOUND_LEAVE = 0.04;
	/** Without a measured window this recent (no frames, or the hook is off), {@link #gpuBound} says true. */
	private static final long STALE_NS = 3_000_000_000L;

	// Render thread only. Totals since start, for the benchmark (differences between two reads).
	/** Nanoseconds inside vkQueueSubmit, all frames. */
	public static long encodeNsTotal;
	/** Nanoseconds waiting for an earlier frame's GPU work, all frames. */
	public static long waitNsTotal;
	/** Frames submitted. */
	public static long submits;

	/** Averages over the last full second, in microseconds per frame (F3). */
	public static int encodeUs;
	public static int waitUs;

	private static long windowStart;
	private static long windowEncode;
	private static long windowWait;
	private static int windowFrames;
	private static long lastWindowEnd;
	private static volatile boolean gpuBound = true;

	private SubmitTimers() {
	}

	static void init() {
		VesuvioStats.register("submit encode us", () -> encodeUs);
		VesuvioStats.register("submit wait us", () -> waitUs);
		VesuvioStats.register("gpu bound", () -> gpuBound() ? 1 : 0);
	}

	/** One vkQueueSubmit of VulkanCommandEncoder.submit took this long. */
	public static void encoded(long ns) {
		encodeNsTotal += ns;
		windowEncode += ns;
	}

	/** One wait for an earlier submit in VulkanCommandEncoder.submit took this long. */
	public static void waited(long ns) {
		waitNsTotal += ns;
		windowWait += ns;
	}

	/** End of VulkanCommandEncoder.submit: one frame more; closes the one-second window when it is over. */
	public static void submitted(long now) {
		submits++;
		windowFrames++;
		if (windowStart == 0L) {
			windowStart = now;
			windowEncode = 0L;
			windowWait = 0L;
			windowFrames = 0;
			return;
		}
		long length = now - windowStart;
		if (length < WINDOW_NS) {
			return;
		}
		encodeUs = (int) (windowEncode / windowFrames / 1000L);
		waitUs = (int) (windowWait / windowFrames / 1000L);
		double waitShare = (double) windowWait / length;
		if (gpuBound ? waitShare < GPU_BOUND_LEAVE : waitShare >= GPU_BOUND_ENTER) {
			gpuBound = !gpuBound;
		}
		lastWindowEnd = now;
		windowStart = now;
		windowEncode = 0L;
		windowWait = 0L;
		windowFrames = 0;
	}

	/**
	 * Whether the GPU was the limit over the last second: the render thread spent at least 10% of the time waiting
	 * for it (and stays "GPU-bound" until that falls under 4%, so the answer does not flip every second). True when
	 * nothing was measured lately, so callers fall back to what they did before this measure existed.
	 */
	public static boolean gpuBound() {
		if (lastWindowEnd == 0L || System.nanoTime() - lastWindowEnd > STALE_NS) {
			return true;
		}
		return gpuBound;
	}
}
