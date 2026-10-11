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
		it.cosimo.vesuvio.leaves.LeafCulling.init();
		it.cosimo.vesuvio.ui.VesuvioUi.init();
		it.cosimo.vesuvio.memory.MemoryBoost.init();
		it.cosimo.vesuvio.faces.FaceCulling.init();
		it.cosimo.vesuvio.draws.Draws.init();
		it.cosimo.vesuvio.entities.Culling.init();
		it.cosimo.vesuvio.culling.CullingBoost.init();
		it.cosimo.vesuvio.frame.FrameBoost.init();
		WorldTicks.init();
		it.cosimo.vesuvio.chunks.ChunkUpdates.init();
		it.cosimo.vesuvio.vertex.CompactVertices.init();
		it.cosimo.vesuvio.clears.ClearFusion.init();
		VesuvioConfig.save();
		LOGGER.info("Vesuvio {} ready, {} settings, optimizations {}",
				FabricLoader.getInstance().getModContainer("vesuvio").map(c -> c.getMetadata().getVersion()
						.getFriendlyString()).orElse("?"),
				VesuvioConfig.all().size(), VesuvioConfig.ENABLED.on() ? "on" : "off (master switch)");
	}

	/**
	 * The client is changing worlds or leaving one (LevelChangeMixin). Each part drops what it still holds of the old
	 * world; all of it is rebuilt from the new world as needed.
	 */
	public static void levelChanged() {
		it.cosimo.vesuvio.faces.SectionDrawCache.release();
		it.cosimo.vesuvio.clears.ClearFusion.release();
	}
}
