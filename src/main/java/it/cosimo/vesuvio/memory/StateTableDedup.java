package it.cosimo.vesuvio.memory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareStateTables (idea from FerriteCore's block state cache deduplication; own code). Every block
 * state keeps, from start-up, an 18-entry "is this face sturdy" table and, for partly occluding blocks (stairs,
 * slabs, walls, fences...), a 6-entry table of its face occlusion shapes. Both are written once in the
 * constructor/initCache and only read afterwards; there are only a few dozen distinct support tables among ~30,000
 * states, so states with equal tables share one array.
 */
public final class StateTableDedup {
	private static final Object LOCK = new Object();
	private static Map<Long, boolean[]> support = new HashMap<>();
	private static Map<List<VoxelShape>, VoxelShape[]> faces = new HashMap<>();

	private StateTableDedup() {
	}

	public static void release() {
		synchronized (LOCK) {
			support = new HashMap<>();
			faces = new HashMap<>();
		}
	}

	/** The shared array with the same flags (the array is never written after the state's cache is built). */
	public static boolean[] support(boolean[] flags) {
		if (flags == null || flags.length > 56) {
			return flags;
		}
		long key = (long) flags.length << 56;
		for (int i = 0; i < flags.length; i++) {
			if (flags[i]) {
				key |= 1L << i;
			}
		}
		synchronized (LOCK) {
			boolean[] known = support.putIfAbsent(key, flags);
			if (known != null) {
				MemoryBoost.stateTablesShared++;
				return known;
			}
		}
		return flags;
	}

	/** The shared array holding the same six face shapes (same objects, in the same order). */
	public static VoxelShape[] faces(VoxelShape[] shapes) {
		if (shapes == null) {
			return null;
		}
		for (VoxelShape s : shapes) {
			if (s == null) {
				return shapes;
			}
		}
		// VoxelShape doesn't override equals/hashCode, so this list compares the shapes by identity.
		List<VoxelShape> key = List.of(shapes);
		synchronized (LOCK) {
			VoxelShape[] known = faces.putIfAbsent(key, shapes);
			if (known != null && known != shapes) {
				MemoryBoost.stateTablesShared++;
				return known;
			}
		}
		return shapes;
	}
}
