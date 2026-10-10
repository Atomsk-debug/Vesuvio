package it.cosimo.vesuvio.ui;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.Vesuvio;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * The parts of Vesuvio the player touches: the master key, the stats log, and the settings the
 * settings screen and benchmark use. The screen itself is {@link VesuvioSettingsScreen}.
 */
public final class VesuvioUi {
	/** Writes the live counters ({@link VesuvioStats}) to latest.log every 5 s. */
	public static final VesuvioConfig.Toggle STATS_LOG = VesuvioConfig.toggle("general", "statsLog", false, false);
	/** How long each benchmark step measures, after 3 s to settle. */
	public static final VesuvioConfig.Number BENCHMARK_SECONDS = VesuvioConfig.number("general", "benchmarkSeconds", 5, 60, 10,
			false);

	private static final long STATS_LOG_NS = 5_000_000_000L;
	private static long lastStatsLog;

	private VesuvioUi() {
	}

	/** Called from Vesuvio.onInitializeClient: makes this package's settings exist early. */
	public static void init() {
		// The static settings above are made by loading this class.
	}

	/** Client tick, no menu open (Minecraft.handleKeybinds): the master key. */
	public static void handleKeys(Minecraft mc) {
		while (VesuvioKey.TOGGLE.consumeClick()) {
			toggleMaster(mc);
		}
	}

	/** Flips the master switch, saves it and says so above the hotbar. */
	public static void toggleMaster(Minecraft mc) {
		// A key press has stopped a running benchmark already (KeyboardHandlerMixin); this is for any other way in.
		VesuvioBenchmark.stop(VesuvioBenchmark.Stop.OTHER);
		boolean on = !VesuvioConfig.ENABLED.get();
		VesuvioConfig.ENABLED.set(on);
		VesuvioConfig.save();
		VesuvioChanges.changed(VesuvioConfig.ENABLED);
		mc.gui.hud.setOverlayMessage(Component.translatable(on ? "vesuvio.toggle.on" : "vesuvio.toggle.off"), false);
		Vesuvio.LOGGER.info("Vesuvio switched {} with the key", on ? "on" : "off");
	}

	/** End of every client tick: the stats log. */
	public static void afterTick(Minecraft mc) {
		if (!STATS_LOG.on()) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastStatsLog < STATS_LOG_NS) {
			return;
		}
		lastStatsLog = now;
		String stats = VesuvioStats.line();
		Vesuvio.LOGGER.info("Vesuvio stats: {} fps, frame {} ms (CPU){}", mc.getFps(),
				String.format(java.util.Locale.ROOT, "%.2f", mc.getFrameTimeNs() / 1.0e6),
				stats.isEmpty() ? "" : ", " + stats);
	}
}
