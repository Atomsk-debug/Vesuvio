package it.cosimo.vesuvio.memory;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.sun.management.GcInfo;

import it.cosimo.vesuvio.Vesuvio;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The memory check: how much memory the game really needs, against how much the launcher lets it use (the
 * "maximum memory" setting, -Xmx) and how much the computer has. Too little makes the game collect garbage over
 * and over (stutter, then a crash); far too much leaves the rest of the computer short (on a Mac the graphics
 * share the same memory), so macOS or Windows starts swapping, which stutters too. In between, more makes no
 * difference: the garbage collector's pauses do not grow with the setting.
 *
 * <p>The game's real need is the heap still in use right after a collection (what survives: the world, models,
 * textures' CPU side), read from the collector's report every 5 s; the advice is twice its peak plus 1 GB, at least 3 GB, rounded up to
 * whole gigabytes. Nothing here changes how the game runs: the launcher fixes the maximum before the game
 * starts, so the check can only say what to set (Prism: Edit instance > Settings > Java > Maximum memory).
 */
public final class MemoryAdvisor {
	public enum Verdict {
		/** Not enough time in a world yet to know the need. */
		MEASURING,
		OK,
		/** The game is close to its maximum after collections: raise the setting. */
		TOO_LOW,
		/** The maximum is more than the computer can spare: lower the setting. */
		TOO_HIGH
	}

	private static final long MB = 1024L * 1024L;
	private static final long GB = 1024L * MB;
	private static final long SAMPLE_NS = 5_000_000_000L;
	/** Samples (5 s each) taken in a world before the need counts as known: 2 minutes. */
	private static final int SAMPLES_TO_KNOW = 24;
	/** Live heap after a collection above this share of the maximum: too low. */
	private static final double TIGHT = 0.80;

	private static final long MAX_HEAP = Runtime.getRuntime().maxMemory();
	private static final long RAM = physicalMemory();
	private static final boolean UNIFIED = isAppleSilicon();
	private static final Set<String> HEAP_POOLS = heapPools();
	private static final List<MemoryPoolMXBean> OLD_POOLS = oldPools();

	private static long lastSample;
	private static int samples;
	/** Samples per window: 5 minutes. */
	private static final int WINDOW = 60;
	/**
	 * The game's need: the highest of the windows' floors. The heap after a young collection still holds old
	 * garbage until the next full or concurrent cleanup, so one reading overstates the need, and more so the
	 * higher the maximum; the lowest reading of a 5-minute window is close to what really lives, and the highest
	 * such floor covers the busiest part of the session.
	 */
	private static long peakLive;
	private static long windowMin = Long.MAX_VALUE;
	private static int windowSamples;
	/** Times the live heap after a collection was above {@link #TIGHT} of the maximum. */
	private static int tightSamples;
	private static long lastGcCount = -1;
	private static long lastGcMs;
	/** Collection pauses in the last 5 s, for F3. */
	private static long gcCountWindow;
	private static long gcMsWindow;
	private static boolean told;

	private MemoryAdvisor() {
	}

	/** Called from MemoryBoost.init: one log line with the facts the advice rests on. */
	static void init() {
		Vesuvio.LOGGER.info("Vesuvio memory check: maximum {} of {} on this computer ({}), garbage collector {}, JVM options: {}",
				size(MAX_HEAP), RAM > 0 ? size(RAM) : "unknown", UNIFIED ? "Apple silicon, memory shared with the graphics"
						: System.getProperty("os.name", "?"),
				collectorNames(), jvmOptions());
	}

	/** End of every client tick (VesuvioUi.afterTick). Samples every 5 s while in a world. */
	public static void tick(Minecraft mc) {
		long now = System.nanoTime();
		if (now - lastSample < SAMPLE_NS) {
			return;
		}
		lastSample = now;
		long count = 0;
		long ms = 0;
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			count += Math.max(0, gc.getCollectionCount());
			ms += Math.max(0, gc.getCollectionTime());
		}
		if (lastGcCount >= 0) {
			gcCountWindow = count - lastGcCount;
			gcMsWindow = ms - lastGcMs;
		}
		lastGcCount = count;
		lastGcMs = ms;
		if (mc.level == null) {
			return;
		}
		long old = oldAfterCleanup();
		long live = liveAfterCollection();
		if (live <= 0) {
			// ZGC's minor cycles report empty pools: fall back to what the old generation kept.
			live = old;
		}
		if (live <= 0) {
			return;
		}
		samples++;
		windowMin = Math.min(windowMin, live);
		if (++windowSamples >= WINDOW) {
			peakLive = Math.max(peakLive, windowMin);
			windowMin = Long.MAX_VALUE;
			windowSamples = 0;
		}
		// Count a tight reading once per new collection, not once per sample of the same one.
		if (live > MAX_HEAP * TIGHT && gcCountWindow > 0) {
			tightSamples++;
		}
		Verdict v = verdict();
		if (!told && (v == Verdict.TOO_LOW || v == Verdict.TOO_HIGH)) {
			told = true;
			Vesuvio.LOGGER.info("Vesuvio memory check: {}", advice().getString());
			mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("vesuvio.memory.chat", advice()));
		}
	}

	public static Verdict verdict() {
		if (tightSamples >= 3) {
			return Verdict.TOO_LOW;
		}
		if (RAM > 0 && MAX_HEAP > spare()) {
			return Verdict.TOO_HIGH;
		}
		return samples < SAMPLES_TO_KNOW ? Verdict.MEASURING : Verdict.OK;
	}

	/** The need as known so far: the highest window floor, or the current window's floor before the first ends. */
	private static long need() {
		return peakLive > 0 ? peakLive : windowMin == Long.MAX_VALUE ? 0 : windowMin;
	}

	/** The maximum to set, in whole gigabytes. */
	public static long recommendedGb() {
		long need = Math.max(3 * GB, need() * 2 + GB);
		long gb = (need + GB - 1) / GB;
		if (RAM > 0) {
			gb = Math.min(gb, Math.max(2, spare() / GB));
		}
		return gb;
	}

	/** One line for the settings screen's button. */
	public static Component status() {
		String use = need() > 0 ? size(need()) : "?";
		return switch (verdict()) {
			case MEASURING -> Component.translatable("vesuvio.memory.status.measuring", size(MAX_HEAP));
			case OK -> Component.translatable("vesuvio.memory.status.ok", use, size(MAX_HEAP));
			case TOO_LOW -> Component.translatable("vesuvio.memory.status.low", use, size(MAX_HEAP));
			case TOO_HIGH -> Component.translatable("vesuvio.memory.status.high", size(MAX_HEAP));
		};
	}

	/** What to do, in a sentence or two. */
	public static Component advice() {
		long rec = recommendedGb();
		String range = rec + "-" + Math.max(rec, Math.max(2, spare() / GB)) + " GB";
		return switch (verdict()) {
			case MEASURING -> Component.translatable("vesuvio.memory.advice.measuring");
			case OK -> Component.translatable("vesuvio.memory.advice.ok", size(MAX_HEAP), range);
			case TOO_LOW -> Component.translatable("vesuvio.memory.advice.low", size(need()), size(MAX_HEAP), rec + " GB");
			case TOO_HIGH -> Component.translatable("vesuvio.memory.advice.high", size(MAX_HEAP),
					RAM > 0 ? size(RAM) : "?", rec + " GB");
		};
	}

	/** The facts behind the advice, for the settings screen's box and F3. */
	public static String facts() {
		return String.format(Locale.ROOT, "in use after the last cleanup %s (need %s), maximum %s, computer %s, cleanups last 5 s: %d (%d ms)",
				size(Math.max(0, liveAfterCollection())), size(need()), size(MAX_HEAP), RAM > 0 ? size(RAM) : "?",
				gcCountWindow, gcMsWindow);
	}

	/** Most memory the game may take before the computer runs short: half on Apple silicon, else all but 4 GB. */
	private static long spare() {
		if (RAM <= 0) {
			return Long.MAX_VALUE;
		}
		long spare = UNIFIED ? RAM / 2 : Math.max(RAM / 2, RAM - 4 * GB);
		// A computer with lots of memory still gains nothing past ~12 GB for one player; this only caps the advice
		// and the warning, never below 2 GB.
		return Math.max(2 * GB, spare);
	}

	/**
	 * Heap in use right after the most recent collection, young or full (every heap pool, from the collector's own
	 * report): what survived, plus old garbage a young collection leaves for later, so it is never below the real
	 * need. -1 when no collection has run yet.
	 */
	private static long liveAfterCollection() {
		long newest = -1;
		long live = -1;
		for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
			if (!(bean instanceof com.sun.management.GarbageCollectorMXBean gc)) {
				continue;
			}
			GcInfo info = gc.getLastGcInfo();
			if (info == null || info.getEndTime() < newest) {
				continue;
			}
			long sum = 0;
			for (Map.Entry<String, MemoryUsage> e : info.getMemoryUsageAfterGc().entrySet()) {
				if (HEAP_POOLS.contains(e.getKey())) {
					sum += e.getValue().getUsed();
				}
			}
			newest = info.getEndTime();
			live = sum;
		}
		return live;
	}

	/** What the long-lived heap pools held after the last collection that cleaned them; 0 when none has run yet. */
	private static long oldAfterCleanup() {
		long sum = 0;
		for (MemoryPoolMXBean pool : OLD_POOLS) {
			MemoryUsage u = pool.getCollectionUsage();
			if (u != null) {
				sum += u.getUsed();
			}
		}
		return sum;
	}

	/** Heap pools for long-lived objects: G1 "Old Gen", ZGC "Old Generation", Parallel/Serial "Old Gen"/"Tenured". */
	private static List<MemoryPoolMXBean> oldPools() {
		List<MemoryPoolMXBean> old = new ArrayList<>();
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			String name = pool.getName().toLowerCase(Locale.ROOT);
			if (pool.getType() == MemoryType.HEAP && (name.contains("old") || name.contains("tenured"))) {
				old.add(pool);
			}
		}
		return old;
	}

	/** Names of the heap's pools (the collectors' reports also list code cache and metaspace). */
	private static Set<String> heapPools() {
		Set<String> names = new HashSet<>();
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans()) {
			if (pool.getType() == MemoryType.HEAP) {
				names.add(pool.getName());
			}
		}
		return names;
	}

	private static long physicalMemory() {
		try {
			if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean os) {
				return os.getTotalMemorySize();
			}
		} catch (RuntimeException | LinkageError e) {
			Vesuvio.LOGGER.debug("Vesuvio: could not read the computer's memory size", e);
		}
		return -1;
	}

	private static boolean isAppleSilicon() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		return os.contains("mac") && (arch.contains("aarch64") || arch.contains("arm"));
	}

	private static String collectorNames() {
		List<String> names = new ArrayList<>();
		for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
			names.add(gc.getName());
		}
		return String.join(", ", names);
	}

	/** Memory and garbage collector options only (-Xmx, -Xms, -XX:...), nothing that could name the user. */
	private static String jvmOptions() {
		List<String> out = new ArrayList<>();
		try {
			for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
				if (arg.startsWith("-Xm") || arg.startsWith("-Xs") || arg.startsWith("-XX:")) {
					out.add(arg);
				}
			}
		} catch (RuntimeException e) {
			return "?";
		}
		return out.isEmpty() ? "none" : String.join(" ", out);
	}

	static String size(long bytes) {
		return bytes >= GB ? String.format(Locale.ROOT, "%.1f GB", bytes / (double) GB) : (bytes / MB) + " MB";
	}

	/** For the settings screen: null when there is nothing to warn about. */
	public static @Nullable Component warning() {
		Verdict v = verdict();
		return v == Verdict.TOO_LOW || v == Verdict.TOO_HIGH ? advice() : null;
	}
}
