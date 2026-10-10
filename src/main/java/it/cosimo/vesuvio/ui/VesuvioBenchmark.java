package it.cosimo.vesuvio.ui;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.Vesuvio;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

/**
 * The "Run benchmark" button on the General page. With the player standing still it measures, for
 * {@link VesuvioUi#BENCHMARK_SECONDS} each after 3 s to settle: Vesuvio off, on, then each live
 * optimization setting changed on its own (every setting off the General page that needs no restart: a
 * toggle is flipped, a number goes to its lowest value, e.g. "leafCulling 0", or to its default if it is
 * at its lowest already), on again and off again. "Off" and "on" run at both ends, so how far the two
 * land apart is the noise.
 *
 * Frame time is the real interval between the starts of two frames on the render thread
 * (Minecraft.runTick, see MinecraftUiMixin): it includes waiting for the GPU and for the swapchain, which
 * the game's own frame timer (getFrameTimeNs, the "cpu" column) leaves out.
 *
 * The settings are only changed in memory and never saved during a run; every switch is put back when it
 * ends, and when it is stopped: any key or mouse button pressed, the player moving or turning, a menu or
 * loading screen opening, the window minimized, the world left, or an error.
 */
public final class VesuvioBenchmark {
	/** Why a run ended. */
	public enum Stop {
		DONE, INPUT, MOVED, MENU, MINIMIZED, LEFT_WORLD, ERROR, OTHER;

		String key() {
			return "vesuvio.benchmark.stop." + name().toLowerCase(Locale.ROOT);
		}
	}

	private static final long SETTLE_NS = 3_000_000_000L;
	/**
	 * Settling goes on past {@link #SETTLE_NS} while chunk sections are still queued for compiling (a step that
	 * changes how meshes are built, e.g. the master switch with leaf culling, rebuilds every loaded section), up
	 * to this long.
	 */
	private static final long MAX_SETTLE_NS = 30_000_000_000L;
	/** Camera travel (blocks) or turn (degrees) since the start above which the run stops. */
	private static final double MOVE_LIMIT = 0.3;
	private static final float TURN_LIMIT = 1.0F;
	/** Frame intervals kept per step for the 95th percentile; more frames than this are only averaged. */
	private static final int MAX_FRAMES = 1 << 18;

	/**
	 * One step: its name in the table, and what it sets on top of the player's settings: the toggle it
	 * flips, or the number setting it sets to {@code numberValue}.
	 */
	private record Step(String name, Kind kind, VesuvioConfig.@Nullable Toggle flipped,
			VesuvioConfig.@Nullable Number number, int numberValue) {
		Step(String name, Kind kind, VesuvioConfig.@Nullable Toggle flipped) {
			this(name, kind, flipped, null, 0);
		}
	}

	private enum Kind {
		OFF, ON, FLIP
	}

	/** One measured step. */
	record Result(String name, int frames, double meanMs, double fps, double p95Ms, double cpuMs, int[] counters) {
	}

	private static @Nullable List<Step> steps;
	private static List<VesuvioConfig.Toggle> toggles = List.of();
	/** The player's own values of {@link #toggles}, put back at the end. */
	private static boolean[] saved = new boolean[0];
	private static List<VesuvioConfig.Number> numbers = List.of();
	/** The player's own values of {@link #numbers}, put back at the end. */
	private static int[] savedNumbers = new int[0];
	private static Result[] results = new Result[0];
	private static List<VesuvioStats.Stat> stats = List.of();
	private static @Nullable ClientLevel level;
	private static double startX;
	private static double startY;
	private static double startZ;
	private static float startYRot;
	private static float startXRot;
	private static long measureNs;
	private static int step = -1;
	private static boolean settling;
	private static long phaseStart;
	private static long lastFrame;
	private static float @Nullable [] frames;
	private static int frameCount;
	private static double frameSumNs;
	private static double cpuSumNs;

	private VesuvioBenchmark() {
	}

	public static boolean running() {
		return steps != null;
	}

	/** The step under way, for the F3 line; "" when no run is going. */
	public static String stepName() {
		List<Step> s = steps;
		return s == null || step < 0 || step >= s.size() ? "" : (step + 1) + "/" + s.size() + " " + s.get(step).name;
	}

	/** Called by the settings screen's button after it closed the menu. */
	public static void start() {
		Minecraft mc = Minecraft.getInstance();
		if (running() || mc.level == null || mc.player == null) {
			return;
		}
		List<VesuvioConfig.Toggle> all = new ArrayList<>();
		List<VesuvioConfig.Number> allNumbers = new ArrayList<>();
		for (VesuvioConfig.Setting s : VesuvioConfig.all()) {
			if (s instanceof VesuvioConfig.Toggle t) {
				all.add(t);
			} else if (s instanceof VesuvioConfig.Number n && !n.needsRestart && !"general".equals(n.page)) {
				allNumbers.add(n);
			}
		}
		toggles = List.copyOf(all);
		saved = new boolean[toggles.size()];
		for (int i = 0; i < toggles.size(); i++) {
			saved[i] = toggles.get(i).get();
		}
		numbers = List.copyOf(allNumbers);
		savedNumbers = new int[numbers.size()];
		for (int i = 0; i < numbers.size(); i++) {
			savedNumbers[i] = numbers.get(i).get();
		}
		List<Step> list = new ArrayList<>();
		list.add(new Step("Vesuvio off", Kind.OFF, null));
		list.add(new Step("Vesuvio on", Kind.ON, null));
		for (VesuvioConfig.Toggle t : toggles) {
			if (t != VesuvioConfig.ENABLED && !t.needsRestart && !"general".equals(t.page)) {
				list.add(new Step(t.key + (t.get() ? " off" : " on"), Kind.FLIP, t));
			}
		}
		for (VesuvioConfig.Number n : numbers) {
			// Lowest value (the optimization's "off" or mildest, e.g. leafCulling 0), or the default if already there.
			int value = n.get() != n.min ? n.min : n.defaultValue;
			if (value != n.get()) {
				list.add(new Step(n.key + " " + value, Kind.FLIP, null, n, value));
			}
		}
		list.add(new Step("Vesuvio on, again", Kind.ON, null));
		list.add(new Step("Vesuvio off, again", Kind.OFF, null));
		stats = VesuvioStats.all();
		results = new Result[list.size()];
		frames = new float[MAX_FRAMES];
		measureNs = VesuvioUi.BENCHMARK_SECONDS.get() * 1_000_000_000L;
		level = mc.level;
		LocalPlayer p = mc.player;
		startX = p.getX();
		startY = p.getY();
		startZ = p.getZ();
		startYRot = p.getYRot();
		startXRot = p.getXRot();
		step = -1;
		lastFrame = 0L;
		steps = list;
		long seconds = list.size() * (SETTLE_NS / 1_000_000_000L + measureNs / 1_000_000_000L);
		Vesuvio.LOGGER.info("Vesuvio benchmark started: {} steps, {} s each after {} s to settle, about {} s. {}",
				list.size(), measureNs / 1_000_000_000L, SETTLE_NS / 1_000_000_000L, seconds, setup(mc));
		mc.showDebugChat(Component.translatable("vesuvio.benchmark.started", list.size(), seconds));
	}

	/** Start of every frame (Minecraft.runTick), render thread. Cheap when no run is going. */
	public static void frame() {
		if (steps == null) {
			return;
		}
		try {
			Minecraft mc = Minecraft.getInstance();
			long now = System.nanoTime();
			long interval = lastFrame == 0L ? 0L : now - lastFrame;
			lastFrame = now;
			Stop reason = interruption(mc);
			if (reason != null) {
				stop(reason);
				return;
			}
			// The player is asked not to touch anything: keep the game's AFK frame limit out of the run.
			mc.getFramerateLimitTracker().onInputReceived();
			if (step < 0) {
				begin(0, now);
				return;
			}
			if (settling) {
				long settled = now - phaseStart;
				if (settled >= SETTLE_NS && (settled >= MAX_SETTLE_NS || compileQueue(mc) == 0)) {
					settling = false;
					phaseStart = now;
					frameCount = 0;
					frameSumNs = 0.0;
					cpuSumNs = 0.0;
				}
				return;
			}
			if (interval > 0L) {
				if (frameCount < MAX_FRAMES) {
					frames[frameCount] = (float) (interval / 1.0e6);
				}
				frameCount++;
				frameSumNs += interval;
				// The game's own timer for the frame that just ended: from its start to the blit, no waiting.
				cpuSumNs += mc.getFrameTimeNs();
			}
			if (now - phaseStart >= measureNs) {
				results[step] = result(steps.get(step).name);
				if (step + 1 < steps.size()) {
					begin(step + 1, now);
				} else {
					finish(mc);
				}
			}
		} catch (RuntimeException e) {
			Vesuvio.LOGGER.error("Vesuvio: the benchmark failed", e);
			stop(Stop.ERROR);
		}
	}

	/** Any key or mouse button pressed (InputUiMixin, MouseUiMixin). */
	public static void input() {
		if (steps != null) {
			stop(Stop.INPUT);
		}
	}

	/** Stops a run, if one is going, and puts the player's settings back. */
	public static void stop(Stop reason) {
		if (steps == null) {
			return;
		}
		String at = stepName();
		restore();
		steps = null;
		frames = null;
		level = null;
		if (reason == Stop.DONE) {
			return;
		}
		Vesuvio.LOGGER.info("Vesuvio benchmark stopped at step {} ({}); settings put back", at,
				reason.name().toLowerCase(Locale.ROOT));
		Minecraft mc = Minecraft.getInstance();
		Component message = Component.translatable("vesuvio.benchmark.stopped", Component.translatable(reason.key()));
		mc.gui.hud.setOverlayMessage(message, false);
		mc.showDebugChat(message);
	}

	/** Sections waiting to be compiled (0 when unknown). */
	private static int compileQueue(Minecraft mc) {
		SectionRenderDispatcher dispatcher = mc.levelRenderer.sectionRenderDispatcher();
		return dispatcher == null ? 0 : dispatcher.getCompileQueueSize();
	}

	private static @Nullable Stop interruption(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (mc.level == null || mc.level != level || p == null) {
			return Stop.LEFT_WORLD;
		}
		if (mc.gui.screen() != null || mc.gui.overlay() != null) {
			return Stop.MENU;
		}
		if (mc.getWindow().isIconified()) {
			return Stop.MINIMIZED;
		}
		double dx = p.getX() - startX;
		double dy = p.getY() - startY;
		double dz = p.getZ() - startZ;
		if (dx * dx + dy * dy + dz * dz > MOVE_LIMIT * MOVE_LIMIT
				|| Math.abs(p.getYRot() - startYRot) > TURN_LIMIT || Math.abs(p.getXRot() - startXRot) > TURN_LIMIT) {
			return Stop.MOVED;
		}
		return null;
	}

	/** Puts the player's settings back, then applies step i's switches on top of them. */
	private static void begin(int i, long now) {
		Step s = steps.get(i);
		boolean[] target = saved.clone();
		for (int t = 0; t < toggles.size(); t++) {
			VesuvioConfig.Toggle toggle = toggles.get(t);
			if (toggle == VesuvioConfig.ENABLED) {
				target[t] = s.kind != Kind.OFF;
			} else if (toggle == s.flipped) {
				target[t] = !saved[t];
			}
		}
		int[] numberTarget = savedNumbers.clone();
		for (int n = 0; n < numbers.size(); n++) {
			if (numbers.get(n) == s.number) {
				numberTarget[n] = s.numberValue;
			}
		}
		apply(target, numberTarget);
		step = i;
		settling = true;
		phaseStart = now;
	}

	private static void restore() {
		apply(saved, savedNumbers);
	}

	/** Sets every toggle and number to its target, telling listeners about each one that changed. Never saves. */
	private static void apply(boolean[] target, int[] numberTarget) {
		for (int t = 0; t < toggles.size(); t++) {
			VesuvioConfig.Toggle toggle = toggles.get(t);
			if (toggle.get() != target[t]) {
				toggle.set(target[t]);
				VesuvioChanges.changed(toggle);
			}
		}
		for (int n = 0; n < numbers.size(); n++) {
			VesuvioConfig.Number number = numbers.get(n);
			if (number.get() != numberTarget[n]) {
				number.set(numberTarget[n]);
				VesuvioChanges.changed(number);
			}
		}
	}

	private static Result result(String name) {
		int n = frameCount;
		double mean = n == 0 ? 0.0 : frameSumNs / n / 1.0e6;
		double cpu = n == 0 ? 0.0 : cpuSumNs / n / 1.0e6;
		int kept = Math.min(n, MAX_FRAMES);
		double p95 = 0.0;
		if (kept > 0) {
			float[] sorted = Arrays.copyOf(frames, kept);
			Arrays.sort(sorted);
			p95 = sorted[Math.min(kept - 1, (int) Math.ceil(kept * 0.95) - 1)];
		}
		int[] counters = new int[stats.size()];
		for (int i = 0; i < counters.length; i++) {
			counters[i] = stats.get(i).read();
		}
		return new Result(name, n, mean, mean > 0.0 ? 1000.0 / mean : 0.0, p95, cpu, counters);
	}

	private static void finish(Minecraft mc) {
		Result[] done = results.clone();
		List<Step> s = steps;
		String setupText = setup(mc);
		stop(Stop.DONE);
		double onMs = mean(done, s, Kind.ON);
		double offMs = mean(done, s, Kind.OFF);
		StringBuilder table = new StringBuilder();
		table.append(String.format(Locale.ROOT, "%-3s%-28s%5s %6s %6s %7s | %6s%n", "#", "step", "fps", "frame", "p95", "vs on",
				"cpu"));
		for (int i = 0; i < done.length; i++) {
			Result r = done[i];
			String vs = s.get(i).kind == Kind.ON ? "ref" : String.format(Locale.ROOT, "%+.2f", r.meanMs - onMs);
			table.append(String.format(Locale.ROOT, "%-3d%-28s%5d %6.2f %6.2f %7s | %6.2f%s%n", i, clip(r.name, 27),
					Math.round(r.fps), r.meanMs, r.p95Ms, vs, r.cpuMs, r.frames < 30 ? "  (few frames)" : ""));
		}
		if (!stats.isEmpty()) {
			table.append(String.format(Locale.ROOT, "%nCounters at the end of each step:%n%-3s%-28s", "#", "step"));
			for (VesuvioStats.Stat st : stats) {
				table.append(String.format(Locale.ROOT, " %12s", clip(st.label(), 12)));
			}
			table.append(System.lineSeparator());
			for (int i = 0; i < done.length; i++) {
				table.append(String.format(Locale.ROOT, "%-3d%-28s", i, clip(done[i].name, 27)));
				for (int c : done[i].counters) {
					table.append(String.format(Locale.ROOT, " %12d", c));
				}
				table.append(System.lineSeparator());
			}
		}
		double offFps = offMs > 0 ? 1000.0 / offMs : 0.0;
		double onFps = onMs > 0 ? 1000.0 / onMs : 0.0;
		Vesuvio.LOGGER.info("Vesuvio benchmark done: off {} fps ({} ms), on {} fps ({} ms). {}{}{}",
				Math.round(offFps), fmt(offMs), Math.round(onFps), fmt(onMs), setupText, System.lineSeparator(), table);
		mc.showDebugChat(Component.translatable("vesuvio.benchmark.done", Math.round(offFps), Math.round(onFps),
				String.format(Locale.ROOT, "%+.0f", offFps > 0 ? (onFps / offFps - 1.0) * 100.0 : 0.0)));
		List<VesuvioBenchmarkScreen.Row> rows = new ArrayList<>();
		for (int i = 0; i < done.length; i++) {
			Result r = done[i];
			boolean ref = s.get(i).kind == Kind.ON;
			rows.add(new VesuvioBenchmarkScreen.Row(r.name, Math.round(r.fps), r.meanMs, r.p95Ms, ref ? Double.NaN : r.meanMs - onMs));
		}
		mc.gui.setScreen(new VesuvioBenchmarkScreen(rows, onFps, offFps));
	}

	/** Mean frame time of the steps of a kind (the two "on" or the two "off" steps). */
	private static double mean(Result[] done, List<Step> s, Kind kind) {
		double sum = 0.0;
		int n = 0;
		for (int i = 0; i < done.length; i++) {
			if (s.get(i).kind == kind && done[i] != null && done[i].frames > 0) {
				sum += done[i].meanMs;
				n++;
			}
		}
		return n == 0 ? 0.0 : sum / n;
	}

	/** What the numbers depend on besides Vesuvio, for the log. */
	private static String setup(Minecraft mc) {
		var o = mc.options;
		return String.format(Locale.ROOT,
				"Window %dx%d, render distance %d, fps limit %s, vsync %s, Etna Shaders %s, %d live settings.",
				mc.getWindow().getWidth(), mc.getWindow().getHeight(), o.renderDistance().get(),
				o.framerateLimit().get() >= 260 ? "none" : o.framerateLimit().get().toString(),
				o.enableVsync().get() ? "on" : "off",
				FabricLoader.getInstance().isModLoaded("etna") ? "installed" : "not installed",
				steps == null ? 0 : steps.size() - 4);
	}

	private static String fmt(double ms) {
		return String.format(Locale.ROOT, "%.2f", ms);
	}

	private static String clip(String s, int n) {
		return s.length() <= n ? s : s.substring(0, n - 1) + "~";
	}
}
