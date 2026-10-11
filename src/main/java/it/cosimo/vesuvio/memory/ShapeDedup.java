package it.cosimo.vesuvio.memory;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import it.cosimo.vesuvio.memory.mixin.BitSetDiscreteVoxelShapeAccessor;
import it.cosimo.vesuvio.memory.mixin.VoxelShapeAccessor;
import it.unimi.dsi.fastutil.doubles.DoubleList;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.shapes.ArrayVoxelShape;
import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;
import net.minecraft.world.phys.shapes.CubeVoxelShape;
import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareBlockShapes (idea from FerriteCore's shape deduplication and ModernFix's wall-shape
 * deduplication; own code).
 *
 * <p>Blocks build one VoxelShape per block state when they are made, and many of them are equal: the 26 wall
 * blocks each build 2 x 162 shapes, each with its own coordinate lists and, once the game asks for face shapes,
 * its own face slices (~900 bytes each, ~7.5 MB for walls alone); fences, panes and
 * bars do the same on a smaller scale. {@link #intern} returns one shared shape for all shapes that are equal
 * <em>structurally</em> (same class, same voxel grid bits and bounds, same coordinate list classes and exact
 * values), so every method of the shared shape gives bit-for-bit the results the copy would have given. The only
 * identity checks the game makes on shapes are against Shapes.block() and Shapes.empty(), which are never
 * substituted. Shapes are immutable after they are built; their lazy face cache is filled the same way by any
 * thread, as for the shapes vanilla already shares.
 *
 * <p>{@link #sharedTable} goes further for wall/fence/pane/bars blocks: their shape depends only on the block's
 * class, the numbers passed to makeShapes and the state's property values, so the second block of a kind reuses
 * the first one's shapes instead of computing them again (each wall shape is up to 4 Shapes.or calls with an
 * optimize pass each), which also shortens start-up.
 */
public final class ShapeDedup {
	private static final Direction.Axis[] AXES = Direction.Axis.values();
	private static final Object LOCK = new Object();
	private static Map<Key, VoxelShape> shapes = new HashMap<>();
	private static Map<TableKey, Map<List<Object>, VoxelShape>> tables = new HashMap<>();

	private ShapeDedup() {
	}

	/** Drops the lookup tables (the shared shapes stay with the blocks that use them). */
	public static void release() {
		synchronized (LOCK) {
			shapes = new HashMap<>();
			tables = new HashMap<>();
		}
	}

	/** The shared shape structurally equal to this one (or this one, which becomes the shared one). */
	public static VoxelShape intern(VoxelShape shape) {
		if (shape == null || shape == Shapes.block() || shape == Shapes.empty()) {
			return shape;
		}
		Class<?> type = shape.getClass();
		if (type != ArrayVoxelShape.class && type != CubeVoxelShape.class) {
			return shape;
		}
		if (grid(shape).getClass() != BitSetDiscreteVoxelShape.class) {
			return shape;
		}
		Key key = new Key(shape);
		synchronized (LOCK) {
			VoxelShape known = shapes.putIfAbsent(key, shape);
			if (known != null) {
				MemoryBoost.shapesShared++;
				return known;
			}
		}
		return shape;
	}

	/** Wraps a block's per-state shape calculator so its results are interned. */
	public static Function<BlockState, VoxelShape> interning(Function<BlockState, VoxelShape> calculator) {
		return state -> intern(calculator.apply(state));
	}

	/**
	 * The state-to-shape function for a wall/fence-like block: from an earlier block of the same class, built with
	 * the same numbers and the same properties, when there is one; otherwise computed (vanilla) and remembered.
	 */
	public static Function<BlockState, VoxelShape> sharedTable(Block block, List<Float> numbers,
			Supplier<Function<BlockState, VoxelShape>> compute) {
		Class<?> type = block.getClass();
		if (!type.getName().startsWith("net.minecraft.")) {
			// A mod's subclass could compute shapes from its own fields: leave it alone.
			return compute.get();
		}
		ImmutableList<BlockState> states = block.getStateDefinition().getPossibleStates();
		Property<?>[] properties = block.getStateDefinition().getProperties().toArray(new Property<?>[0]);
		TableKey key = new TableKey(type, numbers, properties);
		Map<List<Object>, VoxelShape> table;
		synchronized (LOCK) {
			table = tables.get(key);
		}
		if (table != null) {
			ImmutableMap.Builder<BlockState, VoxelShape> map = ImmutableMap.builderWithExpectedSize(states.size());
			for (BlockState state : states) {
				VoxelShape shape = table.get(values(state, properties));
				if (shape == null) {
					return compute.get();
				}
				map.put(state, shape);
			}
			MemoryBoost.shapeTablesReused++;
			return map.build()::get;
		}
		Function<BlockState, VoxelShape> computed = compute.get();
		Map<List<Object>, VoxelShape> built = new HashMap<>(states.size() * 2);
		for (BlockState state : states) {
			VoxelShape shape = computed.apply(state);
			if (shape == null) {
				return computed;
			}
			built.put(values(state, properties), shape);
		}
		synchronized (LOCK) {
			tables.putIfAbsent(key, built);
		}
		return computed;
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static List<Object> values(BlockState state, Property<?>[] properties) {
		Object[] values = new Object[properties.length];
		for (int i = 0; i < properties.length; i++) {
			values[i] = state.getValue((Property) properties[i]);
		}
		return Arrays.asList(values);
	}

	private static DiscreteVoxelShape grid(VoxelShape shape) {
		return ((VoxelShapeAccessor) shape).vesuvio$getShape();
	}

	private static int hash(VoxelShape shape) {
		DiscreteVoxelShape grid = grid(shape);
		int h = shape.getClass().getName().hashCode();
		for (Direction.Axis axis : AXES) {
			h = 31 * h + grid.getSize(axis);
			h = 31 * h + grid.firstFull(axis);
			h = 31 * h + grid.lastFull(axis);
			DoubleList coords = shape.getCoords(axis);
			h = 31 * h + coords.getClass().getName().hashCode();
			for (int i = 0, n = coords.size(); i < n; i++) {
				h = 31 * h + Double.hashCode(coords.getDouble(i));
			}
		}
		return 31 * h + ((BitSetDiscreteVoxelShapeAccessor) grid).vesuvio$getStorage().hashCode();
	}

	/** Same class, same grid (size, bounds, bits), same coordinate list classes and exact coordinates. */
	private static boolean same(VoxelShape a, VoxelShape b) {
		if (a == b) {
			return true;
		}
		if (a.getClass() != b.getClass()) {
			return false;
		}
		DiscreteVoxelShape ga = grid(a);
		DiscreteVoxelShape gb = grid(b);
		if (ga.getClass() != gb.getClass()) {
			return false;
		}
		for (Direction.Axis axis : AXES) {
			if (ga.getSize(axis) != gb.getSize(axis) || ga.firstFull(axis) != gb.firstFull(axis)
					|| ga.lastFull(axis) != gb.lastFull(axis)) {
				return false;
			}
			DoubleList ca = a.getCoords(axis);
			DoubleList cb = b.getCoords(axis);
			int n = ca.size();
			if (ca.getClass() != cb.getClass() || n != cb.size()) {
				return false;
			}
			for (int i = 0; i < n; i++) {
				if (Double.doubleToRawLongBits(ca.getDouble(i)) != Double.doubleToRawLongBits(cb.getDouble(i))) {
					return false;
				}
			}
		}
		return ((BitSetDiscreteVoxelShapeAccessor) ga).vesuvio$getStorage()
				.equals(((BitSetDiscreteVoxelShapeAccessor) gb).vesuvio$getStorage());
	}

	private static final class Key {
		private final VoxelShape shape;
		private final int hash;

		Key(VoxelShape shape) {
			this.shape = shape;
			this.hash = ShapeDedup.hash(shape);
		}

		@Override
		public int hashCode() {
			return hash;
		}

		@Override
		public boolean equals(Object o) {
			return o instanceof Key k && k.hash == hash && same(shape, k.shape);
		}
	}

	/** Block class + makeShapes numbers + the exact Property objects (compared by identity), in order. */
	private record TableKey(Class<?> type, List<Float> numbers, Property<?>[] properties) {
		@Override
		public boolean equals(Object o) {
			if (!(o instanceof TableKey k) || k.type != type || !k.numbers.equals(numbers)
					|| k.properties.length != properties.length) {
				return false;
			}
			for (int i = 0; i < properties.length; i++) {
				if (k.properties[i] != properties[i]) {
					return false;
				}
			}
			return true;
		}

		@Override
		public int hashCode() {
			int h = type.hashCode() * 31 + numbers.hashCode();
			for (Property<?> p : properties) {
				h = h * 31 + System.identityHashCode(p);
			}
			return h;
		}
	}
}
