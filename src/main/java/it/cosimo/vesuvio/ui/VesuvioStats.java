package it.cosimo.vesuvio.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntSupplier;

/**
 * Live counters other optimizations can show without touching the UI: the F3 screen ("vesuvio" entry,
 * a few counters per line), the settings screen's description box while no option is under the mouse, latest.log every
 * 5 s with the statsLog setting on, and the end of each benchmark step.
 *
 * <pre>VesuvioStats.register("leaves culled", () -> LeafCulling.culledLastFrame);</pre>
 *
 * The supplier is read on the render thread a few times a second at most (never per frame unless F3 is
 * open), so a plain static int written by the hook is enough; it must not allocate or block.
 */
public final class VesuvioStats {
	/** A counter: its label (short, lower case, English: it goes in the log too) and how to read it. */
	public record Stat(String label, IntSupplier value) {
		/** The value now, or -1 if reading it failed (so a broken counter never breaks F3 or the log). */
		public int read() {
			try {
				return this.value.getAsInt();
			} catch (RuntimeException e) {
				return -1;
			}
		}
	}

	private static final List<Stat> STATS = new ArrayList<>();
	/** Copy read by the render thread; replaced, never changed, so reading needs no lock. */
	private static volatile List<Stat> snapshot = List.of();

	private VesuvioStats() {
	}

	/** Adds a counter. Registering the same label again replaces the old one. Any thread, any time. */
	public static synchronized void register(String label, IntSupplier value) {
		STATS.removeIf(s -> s.label().equals(label));
		STATS.add(new Stat(label, value));
		snapshot = List.copyOf(STATS);
	}

	/** Every counter, in the order they were registered. */
	public static List<Stat> all() {
		return snapshot;
	}

	/** The same as {@link #line()}, cut into lines of at most {@code perLine} counters (F3). Empty when none. */
	public static List<String> lines(int perLine) {
		List<Stat> stats = snapshot;
		List<String> out = new ArrayList<>((stats.size() + perLine - 1) / Math.max(1, perLine));
		StringBuilder b = new StringBuilder();
		int onLine = 0;
		for (Stat s : stats) {
			if (onLine == perLine) {
				out.add(b.toString());
				b.setLength(0);
				onLine = 0;
			}
			if (onLine > 0) {
				b.append(", ");
			}
			b.append(s.label()).append(' ').append(s.read());
			onLine++;
		}
		if (onLine > 0) {
			out.add(b.toString());
		}
		return out;
	}

	/** "label value, label value, ..." on one line; empty when no counter is registered. */
	public static String line() {
		List<Stat> stats = snapshot;
		StringBuilder b = new StringBuilder();
		for (Stat s : stats) {
			if (b.length() > 0) {
				b.append(", ");
			}
			b.append(s.label()).append(' ').append(s.read());
		}
		return b.toString();
	}
}
