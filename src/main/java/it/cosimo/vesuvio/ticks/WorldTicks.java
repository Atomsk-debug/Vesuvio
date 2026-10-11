package it.cosimo.vesuvio.ticks;

import it.cosimo.vesuvio.VesuvioConfig;

/**
 * Game-logic speedups ("world" page): the integrated server of a single-player world and the client tick run
 * the same vanilla code with less work. Every technique gives exactly the vanilla result (same entities in the
 * same order, same path types, same POI records, same random numbers); see report-ticks.md for the proofs.
 * In multiplayer the server-side ones simply never run (the server is someone else's); the client-side users of
 * the same code (entity lookups, the local player's collisions) still get faster.
 *
 * <p>Counters for the settings screen / benchmark are in {@link TickStats}.
 */
public final class WorldTicks {
	/** Entity lookups visit only the entity sections inside the search box (vanilla walks a whole sorted column). */
	public static final VesuvioConfig.Toggle SECTION_LOOKUP = VesuvioConfig.toggle("world", "worldEntitySectionLookup", true,
			false);
	/**
	 * Each entity section keeps a short list of its "rare" entities (boats, minecarts, shulkers, happy ghasts:
	 * the only ones anything can collide with; container entities: what hoppers look for), so entity-vs-entity
	 * collision checks and hopper container checks skip the cows, items and zombies. Restart: the lists are
	 * filled as entities enter sections.
	 */
	public static final VesuvioConfig.Toggle RARE_ENTITY_INDEX = VesuvioConfig.toggle("world", "worldRareEntityIndex", true,
			true);
	/** Remembers each block state's pathfinding type (it only depends on the state and the tags). */
	public static final VesuvioConfig.Toggle PATH_TYPE_CACHE = VesuvioConfig.toggle("world", "worldPathTypeCache", true,
			false);
	/** Spherical POI searches (villagers, bees, golems) skip the sub-chunks above and below the sphere. */
	public static final VesuvioConfig.Toggle POI_RANGE = VesuvioConfig.toggle("world", "worldPoiVerticalRange", true, false);
	/** The per-tick "inside a wall?" check of every mob uses plain loops instead of a stream. */
	public static final VesuvioConfig.Toggle SUFFOCATION = VesuvioConfig.toggle("world", "worldFastSuffocationCheck", true,
			false);

	private WorldTicks() {
	}

	/** Called from the client entry point so the settings exist (and the restart-only index decides) early. */
	public static void init() {
		RareEntities.init();
		// F3, the settings screen's box, the stats log and the benchmark's counter table.
		it.cosimo.vesuvio.ui.VesuvioStats.register("path type memo hits %", () -> {
			long hits = TickStats.pathTypeHits;
			long all = hits + TickStats.pathTypeMisses;
			return all == 0 ? 0 : (int) (hits * 100 / all);
		});
		it.cosimo.vesuvio.ui.VesuvioStats.register("entity lookups (k)", () -> (int) (TickStats.sectionLookups / 1000));
	}
}
