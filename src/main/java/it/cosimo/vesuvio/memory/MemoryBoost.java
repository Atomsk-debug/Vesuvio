package it.cosimo.vesuvio.memory;

import it.cosimo.vesuvio.VesuvioConfig;

/**
 * Memory and load-time savings (page "memory"). Every setting here acts on objects made while the game starts or
 * while chunks load, so each one needs a restart: {@link MemoryMixinPlugin} leaves the matching mixins out
 * entirely when a setting (or the master switch) is off at start-up, so the game runs vanilla code.
 *
 * <p>No setting changes anything that can be seen: they only make equal, immutable objects shared instead of
 * copied, or replace a debug lock with a smaller one that catches the same mistakes.
 */
public final class MemoryBoost {
	// Keys are compile-time constants so MemoryMixinPlugin can read them without loading this class early.
	public static final String KEY_THREAD_CHECKS = "memoryLightThreadChecks";
	public static final String KEY_SHAPES = "memoryShareBlockShapes";
	public static final String KEY_STATE_ARRAYS = "memoryShareStateTables";
	public static final String KEY_IDENTIFIERS = "memoryShareIdNamespaces";
	public static final boolean DEFAULT_THREAD_CHECKS = true;
	public static final boolean DEFAULT_SHAPES = true;
	public static final boolean DEFAULT_STATE_ARRAYS = true;
	public static final boolean DEFAULT_IDENTIFIERS = true;

	/** Chunk data (PalettedContainer): a 4-byte owner field instead of a ~128-byte lock trio per container. */
	public static final VesuvioConfig.Toggle LIGHT_THREAD_CHECKS = VesuvioConfig.toggle("memory", KEY_THREAD_CHECKS,
			DEFAULT_THREAD_CHECKS, true);
	/** Block shapes: one shared object per distinct shape; wall/fence/pane shape tables computed once per kind. */
	public static final VesuvioConfig.Toggle SHARE_BLOCK_SHAPES = VesuvioConfig.toggle("memory", KEY_SHAPES,
			DEFAULT_SHAPES, true);
	/** Block state tables: equal face-support and face-occlusion arrays shared between states. */
	public static final VesuvioConfig.Toggle SHARE_STATE_TABLES = VesuvioConfig.toggle("memory", KEY_STATE_ARRAYS,
			DEFAULT_STATE_ARRAYS, true);
	/** Identifiers: one shared "minecraft" namespace string instead of a copy per parsed id. */
	public static final VesuvioConfig.Toggle SHARE_ID_NAMESPACES = VesuvioConfig.toggle("memory", KEY_IDENTIFIERS,
			DEFAULT_IDENTIFIERS, true);

	// Counters for the settings screen / benchmark (written at start-up only, on the thread that builds blocks).
	/** Block shapes replaced by an equal shape that already existed. */
	public static int shapesShared;
	/** Wall/fence/pane/bars shape tables taken from an earlier block of the same kind instead of computed. */
	public static int shapeTablesReused;
	/** Block state face tables (support flags, occlusion faces) replaced by an equal shared one. */
	public static int stateTablesShared;

	private MemoryBoost() {
	}

	/** Called from Vesuvio.onInitializeClient: makes the settings and drops the start-up lookup tables. */
	public static void init() {
		// F3, the settings screen's box, the stats log and the benchmark's counter table (start-up sharing only).
		it.cosimo.vesuvio.ui.VesuvioStats.register("block shapes shared", () -> shapesShared);
		it.cosimo.vesuvio.ui.VesuvioStats.register("memory saved (KB)", () -> (int) (estimatedBytesSaved() / 1024));
		// Every vanilla block (and the blocks of mods that register in their main entry point) exists by now;
		// the lookup tables would only keep ~100 KB alive. A block made later just starts a new table.
		// (Only touch the helper classes whose mixins were applied, i.e. whose setting was on at start-up.)
		if (SHARE_BLOCK_SHAPES.on()) {
			ShapeDedup.release();
		}
		if (SHARE_STATE_TABLES.on()) {
			StateTableDedup.release();
		}
	}

	/** Rough bytes saved so far by the start-up sharing (for the settings screen), not counting chunk data. */
	public static long estimatedBytesSaved() {
		// ~450 B for a shape with its coordinate lists, ~40 B for a face table.
		return shapesShared * 450L + stateTablesShared * 40L;
	}
}
