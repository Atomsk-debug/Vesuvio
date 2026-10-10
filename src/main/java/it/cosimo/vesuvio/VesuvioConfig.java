package it.cosimo.vesuvio;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.fabricmc.loader.api.FabricLoader;

/**
 * config/vesuvio.properties. Each optimization declares its own settings in its own class
 * ({@link #toggle}, {@link #number}); they read their saved value on creation and are listed in the
 * settings screen in the order they were made. Values are plain static reads, so hooks can check them
 * every frame for free.
 */
public final class VesuvioConfig {
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("vesuvio.properties");
	private static final Properties LOADED = new Properties();
	private static final List<Setting> SETTINGS = new ArrayList<>();
	private static boolean loaded;

	/** Master switch: false makes every optimization behave like vanilla (live, no restart). */
	public static final Toggle ENABLED = toggle("general", "enabled", true, false);

	private VesuvioConfig() {
	}

	/** One saved setting: its key in the file, the page it shows on in the settings screen. */
	public abstract static sealed class Setting permits Toggle, Number {
		public final String page;
		public final String key;
		/** Takes effect only after a restart (it decides whether a mixin is applied, or work done at load). */
		public final boolean needsRestart;

		Setting(String page, String key, boolean needsRestart) {
			this.page = page;
			this.key = key;
			this.needsRestart = needsRestart;
		}

		/** Translation keys: vesuvio.option.KEY and vesuvio.option.KEY.tooltip. */
		public String nameKey() {
			return "vesuvio.option." + key;
		}

		public String tooltipKey() {
			return nameKey() + ".tooltip";
		}

		abstract String save();

		abstract void read(String text);

		public abstract void reset();
	}

	/** On or off. Hooks read {@link #on()}, which is also false while the master switch is off. */
	public static final class Toggle extends Setting {
		public final boolean defaultValue;
		private volatile boolean value;

		Toggle(String page, String key, boolean defaultValue, boolean needsRestart) {
			super(page, key, needsRestart);
			this.defaultValue = defaultValue;
			this.value = defaultValue;
		}

		/** The setting as saved, ignoring the master switch. */
		public boolean get() {
			return value;
		}

		/** Whether the optimization should run now: its own switch and the master switch both on. */
		public boolean on() {
			return value && (this == ENABLED || ENABLED.value);
		}

		public void set(boolean v) {
			value = v;
		}

		@Override
		String save() {
			return Boolean.toString(value);
		}

		@Override
		void read(String text) {
			value = Boolean.parseBoolean(text.trim());
		}

		@Override
		public void reset() {
			value = defaultValue;
		}
	}

	/** A whole number in [min, max]. */
	public static final class Number extends Setting {
		public final int min;
		public final int max;
		public final int defaultValue;
		private volatile int value;

		Number(String page, String key, int min, int max, int defaultValue, boolean needsRestart) {
			super(page, key, needsRestart);
			this.min = min;
			this.max = max;
			this.defaultValue = defaultValue;
			this.value = defaultValue;
		}

		public int get() {
			return value;
		}

		public void set(int v) {
			value = Math.max(min, Math.min(max, v));
		}

		@Override
		String save() {
			return Integer.toString(value);
		}

		@Override
		void read(String text) {
			try {
				set(Integer.parseInt(text.trim()));
			} catch (NumberFormatException e) {
				reset();
			}
		}

		@Override
		public void reset() {
			value = defaultValue;
		}
	}

	public static synchronized Toggle toggle(String page, String key, boolean defaultValue, boolean needsRestart) {
		return register(new Toggle(page, key, defaultValue, needsRestart));
	}

	public static synchronized Number number(String page, String key, int min, int max, int defaultValue,
			boolean needsRestart) {
		return register(new Number(page, key, min, max, defaultValue, needsRestart));
	}

	private static <T extends Setting> T register(T setting) {
		load();
		for (Setting s : SETTINGS) {
			if (s.key.equals(setting.key)) {
				throw new IllegalStateException("Vesuvio setting registered twice: " + setting.key);
			}
		}
		String saved = LOADED.getProperty(setting.key);
		if (saved != null) {
			setting.read(saved);
		}
		SETTINGS.add(setting);
		return setting;
	}

	/** Every setting made so far, in the order they were made. */
	public static synchronized List<Setting> all() {
		return Collections.unmodifiableList(new ArrayList<>(SETTINGS));
	}

	/** A raw value from the file, for things read before any setting exists (the mixin plugin). */
	public static synchronized String raw(String key, String fallback) {
		load();
		return LOADED.getProperty(key, fallback);
	}

	private static void load() {
		if (loaded) {
			return;
		}
		loaded = true;
		if (Files.isRegularFile(FILE)) {
			try (Reader r = Files.newBufferedReader(FILE)) {
				LOADED.load(r);
			} catch (IOException | IllegalArgumentException e) {
				Vesuvio.LOGGER.warn("Vesuvio: could not read {}, using defaults", FILE, e);
			}
		}
	}

	public static synchronized void save() {
		Properties p = new Properties();
		// Keep keys we don't know (a newer version's settings, the mixin kill list) as they are.
		p.putAll(LOADED);
		for (Setting s : SETTINGS) {
			p.setProperty(s.key, s.save());
			LOADED.setProperty(s.key, s.save());
		}
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer w = Files.newBufferedWriter(FILE)) {
				p.store(w, "Vesuvio settings. Change them in game: Options > Video Settings > Vesuvio.\n"
						+ "disabledMixins: comma-separated mixin class names to skip at start-up (for troubleshooting).");
			}
		} catch (IOException e) {
			Vesuvio.LOGGER.warn("Vesuvio: could not save {}", FILE, e);
		}
	}
}
