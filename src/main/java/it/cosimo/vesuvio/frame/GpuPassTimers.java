package it.cosimo.vesuvio.frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.KHRSynchronization2;
import org.lwjgl.vulkan.VK12;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;
import org.lwjgl.vulkan.VkQueueFamilyProperties;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;

/**
 * Setting gpuPassTimers: where the graphics card's time goes, per render pass. A diagnostic, off by default; the
 * benchmark switches it on for one step of its own and logs a "GPU passes" table.
 *
 * <p>How it measures (FrameGpuTimersEncoderMixin, FrameGpuTimersStagingMixin): each frame in flight (the backend
 * keeps two) has its own Vulkan timestamp query pool. Timestamps are written with vkCmdWriteTimestamp2 (the backend
 * already requires synchronization2), stage ALL_COMMANDS, like vanilla's own writeTimestamp:
 * <ul>
 * <li>right before vkCmdBeginRenderPass2 and right after vkCmdEndRenderPass2 of every render pass, so always
 * outside the render pass instance: on Apple GPUs MoltenVK can only sample time between Metal encoders, and a
 * sample inside a pass could split it;</li>
 * <li>at the start and the end of each command buffer of the frame: the main ones, the object-init one (new
 * textures and buffers) and the transient staging one (both counted as "uploads").</li>
 * </ul>
 * An ALL_COMMANDS timestamp is taken once everything recorded before it has finished, so the time of a pass is
 * from the end of what came before it to its own end. Vanilla already puts a full barrier after every pass, so the
 * passes run one after the other anyway and these times add up. The frame is the sum of its command buffers' spans
 * (any wait for a semaphore between them is left out); "between passes" is what the frame spent outside passes and
 * uploads: copies, clears, barriers. The copy to the window (the swapchain blit, its own command buffer recorded by
 * the surface) is not measured.
 *
 * <p>A pool is reset from the CPU (vkResetQueryPool; the backend requires hostQueryReset) at the start of its frame,
 * which is right after the backend waited for the frame that used it before, and read the same moment, before the
 * reset, without waiting (vkGetQueryPoolResults with no WAIT flag; a frame whose results are not all there is
 * skipped). Pools are made on first use (512 queries, doubled up to 8192 when a frame needs more; a frame that did not
 * fit is skipped), and queued on the backend's own deferred destroy queue when the setting goes off and when the
 * device closes. With the setting off nothing is written, and no pool exists.
 *
 * <p>Passes are grouped by their label (the name each pass is created with, vanilla's and Etna's alike); the first
 * 64 different labels are kept, later ones go to "other". Times are averaged over a second for F3 and over the
 * benchmark step for its table.
 */
public final class GpuPassTimers {
	/** Measure the GPU time of every render pass. Live, off by default. */
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "gpuPassTimers", false, false);

	static final int MAX_LABELS = 64;
	/** The bucket of labels past the first {@link #MAX_LABELS}. */
	static final int OTHER = MAX_LABELS;
	private static final int BUCKETS = MAX_LABELS + 1;
	/** Kinds of measured spans that are not passes: a main command buffer, and an upload command buffer. */
	private static final int KIND_FRAME = -1;
	private static final int KIND_UPLOADS = -2;

	private static final int FIRST_POOL_SIZE = 512;
	private static final int MAX_POOL_SIZE = 8192;
	private static final long STAGE_ALL_COMMANDS = 0x10000L;
	private static final int QUERY_TYPE_TIMESTAMP = 2;
	private static final int QUERY_RESULT_64_BIT = 1;
	private static final long WINDOW_NS = 1_000_000_000L;
	private static final int DEBUG_TOP = 5;
	private static final int TABLE_TOP = 15;

	private static final int UNKNOWN = 0;
	private static final int YES = 1;
	private static final int NO = -1;
	/** Whether the graphics queue can write timestamps (checked once, from the first frame on). */
	private static volatile int support = UNKNOWN;
	/** Nanoseconds per timestamp tick, re-read every second while measuring (MoltenVK refines it as it runs). */
	private static double period = 1.0;
	/** Mask of the timestamp bits that count (timestampValidBits). */
	private static long validMask = -1L;

	// Render thread only.
	private static final Object2IntOpenHashMap<String> LABEL_IDS = new Object2IntOpenHashMap<>();
	private static final String[] LABEL_NAMES = new String[BUCKETS];
	private static int labelCount;

	// Totals since start-up, for the benchmark (differences between two reads).
	private static long framesTotal;
	private static long skippedTotal;
	private static long frameNsTotal;
	private static long uploadsNsTotal;
	private static final long[] PASS_NS_TOTAL = new long[BUCKETS];

	// The F3 window.
	private static long windowStart;
	private static int windowFrames;
	private static long windowFrameNs;
	private static long windowUploadsNs;
	private static final long[] WINDOW_PASS_NS = new long[BUCKETS];
	private static volatile List<String> debugLines = List.of();

	static {
		LABEL_IDS.defaultReturnValue(-1);
		LABEL_NAMES[OTHER] = "other";
	}

	private GpuPassTimers() {
	}

	/** Creates the setting (FrameBoost.init, last on the Frame page). */
	public static void init() {
	}

	/** Added to VulkanCommandEncoder by FrameGpuTimersEncoderMixin: its recorder, null while not measuring. */
	public interface Host {
		@Nullable
		Recorder vesuvio$gpuTimers();
	}

	/** True once the graphics queue was found unable to write timestamps: the setting does nothing. */
	public static boolean unsupported() {
		return support == NO;
	}

	/**
	 * Start of a frame's recording (VulkanCommandEncoder.submit, after it waited for the frame that used this slot
	 * and before the next staging command buffer begins). Returns the recorder to use for the frame, or null.
	 */
	public static @Nullable Recorder frameStart(@Nullable Recorder recorder, VulkanDevice device,
			VulkanCommandEncoder encoder, int slot) {
		boolean supported = detect(device);
		if (!supported || !SETTING.on()) {
			if (recorder != null) {
				recorder.release(encoder);
				resetWindow();
			}
			return null;
		}
		Recorder r = recorder != null ? recorder : new Recorder(device.vkDevice());
		r.frameStart(slot);
		return r;
	}

	/** Reads the queue family's timestampValidBits and the device's timestampPeriod, once. */
	private static boolean detect(VulkanDevice device) {
		if (support != UNKNOWN) {
			return support == YES;
		}
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDevice physical = device.vkDevice().getPhysicalDevice();
			IntBuffer count = stack.mallocInt(1);
			VK12.vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
			VkQueueFamilyProperties.Buffer families = VkQueueFamilyProperties.calloc(count.get(0), stack);
			VK12.vkGetPhysicalDeviceQueueFamilyProperties(physical, count, families);
			int family = device.graphicsQueue().queueFamilyIndex();
			int bits = family >= 0 && family < families.capacity() ? families.get(family).timestampValidBits() : 0;
			float tick = readPeriod(physical);
			if (bits > 0 && tick > 0.0F) {
				validMask = bits >= 64 ? -1L : (1L << bits) - 1L;
				period = tick;
				support = YES;
			} else {
				support = NO;
				Vesuvio.LOGGER.info("Vesuvio gpuPassTimers: this graphics queue cannot write timestamps"
						+ " (timestampValidBits {}, timestampPeriod {}); the setting does nothing", bits, tick);
			}
		} catch (RuntimeException e) {
			support = NO;
			Vesuvio.LOGGER.warn("Vesuvio gpuPassTimers: could not check timestamp support; the setting does nothing", e);
		}
		return support == YES;
	}

	private static float readPeriod(VkPhysicalDevice physical) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
			VK12.vkGetPhysicalDeviceProperties(physical, properties);
			return properties.limits().timestampPeriod();
		}
	}

	/** The bucket of a pass label: one of the first {@link #MAX_LABELS} names, or {@link #OTHER}. */
	private static int labelId(@Nullable Supplier<String> label) {
		String name;
		try {
			name = label == null ? null : label.get();
		} catch (RuntimeException e) {
			name = null;
		}
		if (name == null || name.isEmpty()) {
			name = "unnamed";
		}
		int id = LABEL_IDS.getInt(name);
		if (id >= 0) {
			return id;
		}
		if (labelCount >= MAX_LABELS) {
			return OTHER;
		}
		id = labelCount++;
		LABEL_IDS.put(name, id);
		LABEL_NAMES[id] = name;
		return id;
	}

	// ---- results ----

	/** One frame's times, in nanoseconds (passNs per bucket). */
	private static void measured(long frameNs, long uploadsNs, long[] passNs) {
		framesTotal++;
		frameNsTotal += frameNs;
		uploadsNsTotal += uploadsNs;
		windowFrames++;
		windowFrameNs += frameNs;
		windowUploadsNs += uploadsNs;
		for (int b = 0; b < BUCKETS; b++) {
			PASS_NS_TOTAL[b] += passNs[b];
			WINDOW_PASS_NS[b] += passNs[b];
		}
	}

	private static void skipped() {
		skippedTotal++;
	}

	/** Closes the F3 window when a second is over (render thread, once a frame while measuring). */
	private static void tick(long now) {
		if (windowStart == 0L) {
			windowStart = now;
			return;
		}
		if (now - windowStart < WINDOW_NS) {
			return;
		}
		List<String> lines = new ArrayList<>(DEBUG_TOP + 1);
		int n = windowFrames;
		if (n == 0) {
			lines.add("gpu pass timers: no frame measured in the last second");
		} else {
			long passes = 0L;
			for (long ns : WINDOW_PASS_NS) {
				passes += ns;
			}
			double frame = windowFrameNs / (double) n / 1.0e6;
			double uploads = windowUploadsNs / (double) n / 1.0e6;
			double between = Math.max(0L, windowFrameNs - windowUploadsNs - passes) / (double) n / 1.0e6;
			lines.add(String.format(Locale.ROOT, "gpu frame %.2f ms (passes %.2f, uploads %.2f, between %.2f)", frame,
					passes / (double) n / 1.0e6, uploads, between));
			for (int b : top(WINDOW_PASS_NS, DEBUG_TOP)) {
				double ms = WINDOW_PASS_NS[b] / (double) n / 1.0e6;
				lines.add(String.format(Locale.ROOT, "gpu: %s %.2f ms %d%%", clip(LABEL_NAMES[b], 32), ms,
						Math.round(frame > 0.0 ? ms / frame * 100.0 : 0.0)));
			}
		}
		debugLines = List.copyOf(lines);
		windowStart = now;
		windowFrames = 0;
		windowFrameNs = 0L;
		windowUploadsNs = 0L;
		Arrays.fill(WINDOW_PASS_NS, 0L);
	}

	private static void resetWindow() {
		windowStart = 0L;
		windowFrames = 0;
		windowFrameNs = 0L;
		windowUploadsNs = 0L;
		Arrays.fill(WINDOW_PASS_NS, 0L);
		debugLines = List.of();
	}

	/** The buckets with time in them, largest first, at most {@code max}. */
	private static int[] top(long[] ns, int max) {
		Integer[] order = new Integer[BUCKETS];
		int n = 0;
		for (int b = 0; b < BUCKETS; b++) {
			if (ns[b] > 0L) {
				order[n++] = b;
			}
		}
		Arrays.sort(order, 0, n, (a, b) -> Long.compare(ns[b], ns[a]));
		int[] out = new int[Math.min(n, max)];
		for (int i = 0; i < out.length; i++) {
			out[i] = order[i];
		}
		return out;
	}

	/** F3 lines (VesuvioDebugEntry): empty while the setting is off. */
	public static List<String> debugLines() {
		if (!SETTING.on()) {
			return List.of();
		}
		if (support == NO) {
			return List.of("gpu pass timers: not supported by this graphics driver");
		}
		List<String> lines = debugLines;
		return lines.isEmpty() ? List.of("gpu pass timers: measuring...") : lines;
	}

	// ---- benchmark ----

	/** Totals since start-up; two of them make a measured period ({@link #table}). */
	public record Totals(long frames, long skipped, long frameNs, long uploadsNs, long[] passNs) {
	}

	public static Totals totals() {
		return new Totals(framesTotal, skippedTotal, frameNsTotal, uploadsNsTotal, PASS_NS_TOTAL.clone());
	}

	/** The benchmark's "GPU passes" table for the frames measured between two totals (latest.log). */
	public static String table(String stepName, Totals from, Totals to) {
		String nl = System.lineSeparator();
		long frames = to.frames - from.frames;
		long skippedFrames = to.skipped - from.skipped;
		if (support == NO) {
			return "GPU passes: not measured, this graphics driver cannot write timestamps." + nl;
		}
		if (frames <= 0) {
			return "GPU passes: no frame measured" + (skippedFrames > 0 ? " (" + skippedFrames + " skipped)" : "") + "."
					+ nl;
		}
		long[] pass = new long[BUCKETS];
		long passes = 0L;
		for (int b = 0; b < BUCKETS; b++) {
			pass[b] = to.passNs[b] - from.passNs[b];
			passes += pass[b];
		}
		long frameNs = to.frameNs - from.frameNs;
		long uploadsNs = to.uploadsNs - from.uploadsNs;
		double frame = frameNs / (double) frames / 1.0e6;
		StringBuilder t = new StringBuilder();
		t.append(String.format(Locale.ROOT,
				"GPU passes (step \"%s\", %d frames%s): graphics card time per frame, from Vulkan timestamps around each"
						+ " render pass%n",
				stepName, frames, skippedFrames > 0 ? ", " + skippedFrames + " skipped" : ""));
		t.append(String.format(Locale.ROOT, "  %-44s %8s %7s%n", "pass", "ms", "share"));
		row(t, "whole frame", frame, frame);
		int[] named = top(pass, TABLE_TOP);
		long shown = 0L;
		for (int b : named) {
			if (b == OTHER) {
				continue;
			}
			shown += pass[b];
			row(t, LABEL_NAMES[b], pass[b] / (double) frames / 1.0e6, frame);
		}
		long rest = passes - shown;
		if (rest > 0L) {
			row(t, "other passes", rest / (double) frames / 1.0e6, frame);
		}
		row(t, "uploads (staging, new textures and buffers)", uploadsNs / (double) frames / 1.0e6, frame);
		row(t, "between passes (copies, clears, barriers)",
				Math.max(0L, frameNs - uploadsNs - passes) / (double) frames / 1.0e6, frame);
		return t.toString();
	}

	private static void row(StringBuilder t, String name, double ms, double frame) {
		t.append(String.format(Locale.ROOT, "  %-44s %8.3f %6.1f%%%n", clip(name, 44), ms,
				frame > 0.0 ? ms / frame * 100.0 : 0.0));
	}

	private static String clip(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n - 1) + "~";
	}

	// ---- recording ----

	/** One frame in flight's query pool and what its queries hold. */
	private static final class Slot {
		long pool;
		int size;
		/** Spans recorded; span i owns queries 2i (start) and 2i + 1 (end). */
		int spans;
		/** Each span's pass bucket, or KIND_FRAME / KIND_UPLOADS. */
		int[] kinds = new int[0];
		/** A span did not fit: the frame is skipped and the pool grows. */
		boolean overflow;
		/** The pool was reset and handed to a frame, whose results are read when the slot comes round again. */
		boolean recorded;
	}

	/**
	 * The timestamps of one command encoder (render thread only, like the encoder). Every method is cheap and does
	 * nothing while no frame is being measured.
	 */
	public static final class Recorder {
		private final VkDevice device;
		private final Slot[] slots = {new Slot(), new Slot()};
		private @Nullable Slot current;
		/** The span of the open render pass, or -1. */
		private int passSpan = -1;
		/** Open command buffers (address) and their spans: at most the staging, init and main ones at a time. */
		private final long[] openBuffers = new long[4];
		private final int[] openSpans = new int[4];
		private int openCount;
		private final long[] passNs = new long[BUCKETS];
		private LongBuffer results = directLongs(FIRST_POOL_SIZE);
		private long lastPeriodRead;
		private boolean createFailed;

		Recorder(VkDevice device) {
			this.device = device;
		}

		/** The slot's earlier frame has finished on the GPU: read it, then reset the pool for the new frame. */
		void frameStart(int slotIndex) {
			Slot s = this.slots[slotIndex & 1];
			long now = System.nanoTime();
			if (s.recorded) {
				collect(s);
				tick(now);
			}
			s.recorded = false;
			this.current = null;
			this.passSpan = -1;
			this.openCount = 0;
			if (now - this.lastPeriodRead > WINDOW_NS) {
				this.lastPeriodRead = now;
				float tick = readPeriod(this.device.getPhysicalDevice());
				if (tick > 0.0F) {
					period = tick;
				}
			}
			if (s.pool == 0L || s.overflow && s.size < MAX_POOL_SIZE) {
				int size = s.pool == 0L ? FIRST_POOL_SIZE : Math.min(s.size * 2, MAX_POOL_SIZE);
				if (s.pool != 0L) {
					// Its frame has finished (the backend just waited for it), and nothing else uses this pool.
					VK12.vkDestroyQueryPool(this.device, s.pool, null);
					s.pool = 0L;
				}
				long pool = createPool(size);
				if (pool == 0L) {
					return;
				}
				s.pool = pool;
				s.size = size;
				s.kinds = new int[size / 2];
				if (this.results.capacity() < size) {
					this.results = directLongs(size);
				}
			}
			VK12.vkResetQueryPool(this.device, s.pool, 0, s.size);
			s.spans = 0;
			s.overflow = false;
			s.recorded = true;
			this.current = s;
		}

		private long createPool(int size) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.calloc(stack).sType$Default();
				info.queryType(QUERY_TYPE_TIMESTAMP);
				info.queryCount(size);
				LongBuffer pool = stack.callocLong(1);
				int result = VK12.vkCreateQueryPool(this.device, info, null, pool);
				if (result == VK12.VK_SUCCESS) {
					return pool.get(0);
				}
				if (!this.createFailed) {
					this.createFailed = true;
					Vesuvio.LOGGER.warn("Vesuvio gpuPassTimers: could not create a timestamp query pool (VkResult {})",
							result);
				}
				return 0L;
			}
		}

		private void collect(Slot s) {
			if (s.overflow || s.spans == 0) {
				skipped();
				return;
			}
			int count = s.spans * 2;
			LongBuffer values = this.results;
			values.clear();
			values.limit(count);
			int result = VK12.vkGetQueryPoolResults(this.device, s.pool, 0, count, values, 8L, QUERY_RESULT_64_BIT);
			if (result != VK12.VK_SUCCESS) {
				skipped();
				return;
			}
			long frame = 0L;
			long uploads = 0L;
			Arrays.fill(this.passNs, 0L);
			double tick = period;
			for (int i = 0; i < s.spans; i++) {
				long ticks = (values.get(2 * i + 1) - values.get(2 * i)) & validMask;
				long ns = ticks < 0L ? 0L : (long) (ticks * tick);
				int kind = s.kinds[i];
				if (kind == KIND_FRAME) {
					frame += ns;
				} else if (kind == KIND_UPLOADS) {
					frame += ns;
					uploads += ns;
				} else {
					this.passNs[kind] += ns;
				}
			}
			measured(frame, uploads, this.passNs);
		}

		/** Reserves a span (two queries) and writes its start, or returns -1 when nothing is measured or it is full. */
		private int begin(VkCommandBuffer commandBuffer, int kind) {
			Slot s = this.current;
			if (s == null) {
				return -1;
			}
			if (s.spans * 2 + 2 > s.size) {
				s.overflow = true;
				return -1;
			}
			int span = s.spans++;
			s.kinds[span] = kind;
			KHRSynchronization2.vkCmdWriteTimestamp2KHR(commandBuffer, STAGE_ALL_COMMANDS, s.pool, span * 2);
			return span;
		}

		private void end(VkCommandBuffer commandBuffer, int span) {
			Slot s = this.current;
			if (s != null) {
				KHRSynchronization2.vkCmdWriteTimestamp2KHR(commandBuffer, STAGE_ALL_COMMANDS, s.pool, span * 2 + 1);
			}
		}

		/** Right before vkCmdBeginRenderPass2 (outside the pass). */
		public void passBegin(VkCommandBuffer commandBuffer, @Nullable Supplier<String> label) {
			if (this.current == null || this.passSpan >= 0) {
				return;
			}
			this.passSpan = begin(commandBuffer, labelId(label));
		}

		/** Right after vkCmdEndRenderPass2 (outside the pass). */
		public void passEnd(VkCommandBuffer commandBuffer) {
			if (this.passSpan >= 0) {
				end(commandBuffer, this.passSpan);
				this.passSpan = -1;
			}
		}

		/** A command buffer of the frame was just begun: the main one, or one with uploads. */
		public void bufferBegin(VkCommandBuffer commandBuffer, boolean uploads) {
			if (this.current == null || this.openCount == this.openBuffers.length) {
				return;
			}
			int span = begin(commandBuffer, uploads ? KIND_UPLOADS : KIND_FRAME);
			if (span >= 0) {
				this.openBuffers[this.openCount] = commandBuffer.address();
				this.openSpans[this.openCount] = span;
				this.openCount++;
			}
		}

		/** Right before vkEndCommandBuffer of any command buffer; only those begun while measuring get a timestamp. */
		public void bufferEnd(VkCommandBuffer commandBuffer) {
			long address = commandBuffer.address();
			for (int i = 0; i < this.openCount; i++) {
				if (this.openBuffers[i] == address) {
					end(commandBuffer, this.openSpans[i]);
					this.openCount--;
					this.openBuffers[i] = this.openBuffers[this.openCount];
					this.openSpans[i] = this.openSpans[this.openCount];
					return;
				}
			}
		}

		/**
		 * Gives the pools to the encoder's deferred destroy queue (the frame in flight may still write to one; the
		 * queue destroys them two submits later, or after the GPU went idle when the device closes).
		 */
		void release(VulkanCommandEncoder encoder) {
			this.current = null;
			this.passSpan = -1;
			this.openCount = 0;
			for (Slot s : this.slots) {
				long pool = s.pool;
				if (pool != 0L) {
					VkDevice vkDevice = this.device;
					encoder.queueForDestroy(() -> VK12.vkDestroyQueryPool(vkDevice, pool, null));
				}
				s.pool = 0L;
				s.recorded = false;
			}
		}

		/** Device closing (VulkanCommandEncoder.destroy). */
		public void close(VulkanCommandEncoder encoder) {
			release(encoder);
			resetWindow();
		}

		private static LongBuffer directLongs(int n) {
			return ByteBuffer.allocateDirect(n * Long.BYTES).order(ByteOrder.nativeOrder()).asLongBuffer();
		}
	}
}
