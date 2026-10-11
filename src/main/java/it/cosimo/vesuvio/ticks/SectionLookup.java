package it.cosimo.vesuvio.ticks;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.core.SectionPos;
import net.minecraft.util.AbortableIterationConsumer;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntitySection;
import net.minecraft.world.phys.AABB;

/**
 * EntitySectionStorage.forEachAccessibleNonEmptySection without the sorted-set walk.
 *
 * <p>Vanilla, for each section column x in range, takes {@code sectionIds.subSet(asLong(x,0,0), asLong(x,-1,-1)+1)}
 * (a LongAVLTreeSet view plus an iterator) and walks every existing section of that column in the whole world,
 * keeping the ones whose y and z are in range. We look the in-range keys up in the {@code sections} map directly,
 * in exactly the order the sorted walk would meet them, so the consumer sees the same sections in the same order.
 *
 * <p>Order of the sorted walk: SectionPos.asLong packs x in the top 22 bits, z in the next 22, y in the low 20
 * (two's complement, masked). Within one column the top bits are equal, so the signed long order is the unsigned
 * order of {@code (z & 0x3FFFFF) << 20 | (y & 0xFFFFF)}: z first, then y, and in each the non-negative values
 * ascending come before the negative values ascending (e.g. z = 0, 1, 2, -3, -2, -1). {@code sections} and
 * {@code sectionIds} always hold the same keys (EntitySectionStorage.createSection / remove change both).
 */
public final class SectionLookup {
	/** Above this many (x, y, z) keys the sorted walk is cheaper (huge boxes: beacons, raids, commands). */
	private static final long MAX_KEYS = 64;
	private static final int XZ_LIMIT = 1 << 21;
	private static final int Y_LIMIT = 1 << 19;

	private SectionLookup() {
	}

	/**
	 * Visits the accessible non-empty sections the vanilla method would visit, in its order.
	 *
	 * @return false when the box is too large or too far out for the direct lookup; the caller then runs vanilla
	 */
	public static <T extends EntityAccess> boolean forEach(Long2ObjectMap<EntitySection<T>> sections, AABB bb,
			AbortableIterationConsumer<EntitySection<T>> output) {
		// Same bounds as vanilla, same functions.
		int xMin = SectionPos.posToSectionCoord(bb.minX - 2.0);
		int yMin = SectionPos.posToSectionCoord(bb.minY - 4.0);
		int zMin = SectionPos.posToSectionCoord(bb.minZ - 2.0);
		int xMax = SectionPos.posToSectionCoord(bb.maxX + 2.0);
		int yMax = SectionPos.posToSectionCoord(bb.maxY + 0.0);
		int zMax = SectionPos.posToSectionCoord(bb.maxZ + 2.0);
		if (xMin > xMax || yMin > yMax || zMin > zMax) {
			// Vanilla visits nothing either (the x loop is empty, or the y/z filter rejects every key).
			return true;
		}
		// Packing must be one-to-one and order-preserving for the order argument above. (At x = 2^21 - 1 vanilla's
		// subSet bound overflows and throws; such boxes, 33 million blocks out, are left to vanilla.)
		if (xMin < -XZ_LIMIT || xMax >= XZ_LIMIT - 1 || zMin < -XZ_LIMIT || zMax >= XZ_LIMIT || yMin < -Y_LIMIT
				|| yMax >= Y_LIMIT) {
			return false;
		}
		long keys = (long) (xMax - xMin + 1) * (long) (yMax - yMin + 1) * (long) (zMax - zMin + 1);
		if (keys > MAX_KEYS) {
			return false;
		}
		TickStats.sectionLookups++;
		for (int x = xMin; x <= xMax; x++) {
			// z: non-negative values first, then negative ones (unsigned order of the masked value).
			for (int zPass = 0; zPass < 2; zPass++) {
				int zFrom = zPass == 0 ? Math.max(zMin, 0) : zMin;
				int zTo = zPass == 0 ? zMax : Math.min(zMax, -1);
				for (int z = zFrom; z <= zTo; z++) {
					for (int yPass = 0; yPass < 2; yPass++) {
						int yFrom = yPass == 0 ? Math.max(yMin, 0) : yMin;
						int yTo = yPass == 0 ? yMax : Math.min(yMax, -1);
						for (int y = yFrom; y <= yTo; y++) {
							EntitySection<T> section = sections.get(SectionPos.asLong(x, y, z));
							if (section != null && !section.isEmpty() && section.getStatus().isAccessible()
									&& output.accept(section).shouldAbort()) {
								return true;
							}
						}
					}
				}
			}
		}
		return true;
	}
}
