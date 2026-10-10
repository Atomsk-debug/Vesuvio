package it.cosimo.vesuvio.ui;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.Vesuvio;

/**
 * Tells optimizations that a setting was changed live: by the settings screen, the master key or the
 * benchmark (which changes them and puts them back). The setting passed is the one that changed;
 * {@link VesuvioConfig#ENABLED} means every optimization's on() may have changed. Listeners run on the
 * render thread. For work that has to be redone when a switch flips, e.g. chunk meshes built with a
 * culling rule: {@code VesuvioChanges.listen(s -> { if (s == ENABLED || s == MY_TOGGLE) rebuild(); });}
 */
public final class VesuvioChanges {
	private static final List<Consumer<VesuvioConfig.Setting>> LISTENERS = new CopyOnWriteArrayList<>();

	private VesuvioChanges() {
	}

	public static void listen(Consumer<VesuvioConfig.Setting> listener) {
		LISTENERS.add(listener);
	}

	static void changed(VesuvioConfig.Setting setting) {
		for (Consumer<VesuvioConfig.Setting> listener : LISTENERS) {
			try {
				listener.accept(setting);
			} catch (RuntimeException e) {
				Vesuvio.LOGGER.error("Vesuvio: a listener failed after {} changed", setting.key, e);
			}
		}
	}
}
