package it.cosimo.vesuvio.ui;

import java.util.ArrayList;
import java.util.List;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.client.gui.components.debug.DebugGroup;
import net.minecraft.client.gui.components.debug.DebugScreenDisplayer;
import net.minecraft.client.gui.components.debug.DebugScreenEntry;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import org.jspecify.annotations.Nullable;

/**
 * Vesuvio's lines on the F3 screen: whether it is on, and the live counters ({@link VesuvioStats}).
 * Registered as the debug entry "vesuvio:vesuvio" (DebugScreenEntriesMixin), shown with F3 by
 * default (DebugScreenEntryListMixin) and listed in vanilla's debug options screen, where it can be set to
 * always / with F3 / never like any other entry.
 */
public final class VesuvioDebugEntry implements DebugScreenEntry {
	public static final Identifier ID = Identifier.fromNamespaceAndPath("vesuvio", "vesuvio");
	private static final DebugGroup GROUP = DebugGroup.Builder.titleless().build();

	@Override
	public void display(DebugScreenDisplayer displayer, @Nullable Level serverOrClientLevel, @Nullable LevelChunk clientChunk,
			@Nullable LevelChunk serverChunk) {
		List<String> lines = new ArrayList<>(2);
		String state = VesuvioConfig.ENABLED.get() ? "on" : "off";
		if (VesuvioBenchmark.running()) {
			state += " (benchmark: " + VesuvioBenchmark.stepName() + ")";
		}
		lines.add("Vesuvio " + state);
		if (VesuvioConfig.ENABLED.get()) {
			String stats = VesuvioStats.line();
			if (!stats.isEmpty()) {
				lines.add(stats);
			}
		}
		displayer.addToGroup(GROUP, lines);
	}

	@Override
	public boolean isAllowed(boolean reducedDebugInfo) {
		return true;
	}
}
