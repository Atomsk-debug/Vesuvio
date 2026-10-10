package it.cosimo.vesuvio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import it.cosimo.vesuvio.ticks.WorldTicks;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;

/** Vesuvio: the optimization mod. Each optimization lives in its own package and makes its own settings. */
public final class Vesuvio implements ClientModInitializer {
	public static final Logger LOGGER = LoggerFactory.getLogger("Vesuvio");

	@Override
	public void onInitializeClient() {
		// Touch each optimization so its settings exist before the settings screen or the first frame.
		// (one line per optimization package)
		WorldTicks.init();
		VesuvioConfig.save();
		LOGGER.info("Vesuvio {} ready, {} settings, optimizations {}",
				FabricLoader.getInstance().getModContainer("vesuvio").map(c -> c.getMetadata().getVersion()
						.getFriendlyString()).orElse("?"),
				VesuvioConfig.all().size(), VesuvioConfig.ENABLED.on() ? "on" : "off (master switch)");
	}
}
