package it.cosimo.vesuvio.ticks;

/**
 * Counters of the "world" optimizations, for the settings screen / benchmark. Plain statics, only ever
 * incremented (from the server thread and the client thread, so a few increments can be lost: they are statistics,
 * not state). Read them, take differences over time.
 */
public final class TickStats {
	/** Entity lookups answered by visiting the sections directly (instead of walking the sorted column). */
	public static long sectionLookups;
	/** Entity-collision and hopper-container lookups answered from the rare-entity lists. */
	public static long rareLookups;
	/** Path type requests answered from the per-state memo / computed and stored. */
	public static long pathTypeHits;
	public static long pathTypeMisses;
	/** POI searches narrowed to the sub-chunks the sphere touches. */
	public static long poiSearches;
	/** Suffocation checks done with the loop version. */
	public static long suffocationChecks;

	private TickStats() {
	}
}
