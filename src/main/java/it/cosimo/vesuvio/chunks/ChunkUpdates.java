package it.cosimo.vesuvio.chunks;

import it.cosimo.vesuvio.VesuvioConfig;

/**
 * Smoother, faster chunk updates on the client. Five independent parts, each with its own setting:
 * <ul>
 * <li>{@link ChunkScheduler} ({@link #SCHEDULER}): hands the compile workers only as many new sections per frame as
 * they can take, nearest first, instead of copying every dirty section in view in one frame.</li>
 * <li>{@link ChunkScheduler} ({@link #SKIP_EMPTY}): sections that hold only air get their (empty) result at once,
 * without a world copy or a compile task.</li>
 * <li>{@link SectionCopyTrim} ({@link #TRIM_COPIES}): the world copy of a section keeps only its own block
 * entities, not the whole chunk's.</li>
 * <li>{@link ChunkUploads} ({@link #UPLOAD_BUDGET}): the finished meshes are sent to the GPU within a time budget per
 * frame; the rest waits for the next frame.</li>
 * <li>{@link TranslucentSort} ({@link #SORT_THROTTLE}): far see-through sections are re-sorted when the camera moved
 * enough for their order to change, not on every block step.</li>
 * </ul>
 */
public final class ChunkUpdates {
	/** Bounded, nearest-first scheduling of section rebuilds. Live. */
	public static final VesuvioConfig.Toggle SCHEDULER = VesuvioConfig.toggle("world", "chunkScheduler", true, false);
	/** All-air sections become empty at once (no copy, no compile task). Live. */
	public static final VesuvioConfig.Toggle SKIP_EMPTY = VesuvioConfig.toggle("world", "skipEmptySections", true, false);
	/** Section copies keep only their own block entities. Live. */
	public static final VesuvioConfig.Toggle TRIM_COPIES = VesuvioConfig.toggle("world", "trimSectionCopies", true, false);
	/** Milliseconds per frame for sending finished chunk meshes to the GPU; 0 = no limit (vanilla). Live. */
	public static final VesuvioConfig.Number UPLOAD_BUDGET = VesuvioConfig.number("world", "chunkUploadBudget", 0, 16, 2, false);
	/** Re-sort far translucent sections only after the camera moved enough. Live. */
	public static final VesuvioConfig.Toggle SORT_THROTTLE = VesuvioConfig.toggle("rendering", "translucentSortThrottle", true, false);

	private ChunkUpdates() {
	}

	/** Creates the settings (called from Vesuvio.onInitializeClient). */
	public static void init() {
	}
}
