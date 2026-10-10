package it.cosimo.vesuvio.leaves;

import java.util.concurrent.atomic.LongAdder;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.ui.VesuvioStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Leaf face culling. With "cutout leaves" on (fancy leaves), vanilla draws every face of a leaf block that touches
 * another leaf block, so a canopy is built of stacked see-through planes all the way through. Most of the inner ones
 * are never seen: to reach them a ray has to pass several leaf textures first. Here a leaf face that touches another
 * leaf is dropped when the leaf is buried in that direction, i.e. the next blocks further along are leaves too (or
 * full opaque blocks); the outer layers, which are what you actually see, stay. Only the faces the model marks with a
 * cullface are affected (all of a vanilla leaf block's faces), and only during section compilation. The hook sits on
 * {@code Block.shouldRenderFace}, which both vanilla's block renderer and Fabric API's Indigo renderer call, so it
 * works with or without Fabric API.
 *
 * <p>Modes ({@link #MODE}): 0 off (vanilla), 1 smart (default: the neighbour and the two blocks after it must be
 * leaves/opaque, keeps three planes from every surface), 2 aggressive (neighbour and one more, like OptiFine's Smart
 * Leaves / CullLeaves, keeps two planes), 3 all (any leaf neighbour, like fast graphics but keeping the cutout look;
 * trees turn into hollow shells).
 *
 * <p>Threads: {@link #cull} runs on the section compile workers and only reads the {@code RenderSectionRegion} it is
 * given (it reaches at most 3 blocks from the block being compiled, well inside the region's one-section margin);
 * {@link #poll} and {@link #blockChanged} run on the render thread.
 */
public final class LeafCulling {
	public static final int OFF = 0;
	public static final int SMART = 1;
	public static final int AGGRESSIVE = 2;
	public static final int ALL = 3;

	/** 0 off, 1 smart (default), 2 aggressive, 3 all. Live: changing it rebuilds the loaded chunk meshes. */
	public static final VesuvioConfig.Number MODE = VesuvioConfig.number("rendering", "leafCulling", OFF, ALL, SMART, false);

	/** The mode the meshes are being built with; changes only in {@link #poll}, together with a full rebuild. */
	private static volatile int active = OFF;

	private static final LongAdder CHECKED = new LongAdder();
	private static final LongAdder CULLED = new LongAdder();
	private static final BlockPos.MutableBlockPos MAIN_PROBE = new BlockPos.MutableBlockPos();

	private LeafCulling() {
	}

	/** Creates the setting and its counter (called from Vesuvio.onInitializeClient). */
	public static void init() {
		active = wanted();
		// F3, the settings screen's box, the stats log and the benchmark's counter table.
		VesuvioStats.register("leaf faces culled %", LeafCulling::culledPercent);
	}

	private static int wanted() {
		return VesuvioConfig.ENABLED.on() ? MODE.get() : OFF;
	}

	/** The mode the current meshes use (0 when the master switch is off). */
	public static int activeMode() {
		return active;
	}

	/**
	 * Counter: leaf faces facing another leaf that vanilla would have drawn, in the sections compiled since the
	 * mode last changed (sections compiled more than once count again).
	 */
	public static long leafFacesChecked() {
		return CHECKED.sum();
	}

	/** Counter: how many of {@link #leafFacesChecked()} were culled. */
	public static long leafFacesCulled() {
		return CULLED.sum();
	}

	/** Counter: share of leaf-against-leaf faces culled, 0..100 (0 before any leaf was compiled). */
	public static int culledPercent() {
		long checked = CHECKED.sum();
		return checked == 0 ? 0 : (int) (CULLED.sum() * 100 / checked);
	}

	/**
	 * Render thread, once a frame: when the setting or the master switch changed, switch mode and rebuild every
	 * loaded section, as vanilla does when a graphics option changes.
	 */
	public static void poll() {
		int mode = wanted();
		if (mode != active) {
			active = mode;
			CHECKED.reset();
			CULLED.reset();
			Minecraft.getInstance().levelExtractor.allChanged();
		}
	}

	/**
	 * Section compile thread. Called only where vanilla decided to draw the face of {@code state} turned towards
	 * {@code direction} (so the neighbour neither hides it nor makes vanilla skip it). True drops the face.
	 *
	 * @param neighborPos the neighbour's position (the renderer's scratch position: read only)
	 * @param probe       a position this call may overwrite, owned by the calling renderer
	 */
	public static boolean cull(BlockGetter level, BlockState state, BlockState neighbor, BlockPos neighborPos,
			Direction direction, BlockPos.MutableBlockPos probe) {
		int mode = active;
		if (mode == OFF || !(state.getBlock() instanceof LeavesBlock) || !(neighbor.getBlock() instanceof LeavesBlock)) {
			return false;
		}
		CHECKED.increment();
		if (mode != ALL) {
			int depth = mode == SMART ? 3 : 2;
			probe.set(neighborPos);
			for (int k = 2; k <= depth; k++) {
				probe.move(direction);
				if (!buries(level.getBlockState(probe))) {
					return false;
				}
			}
		}
		CULLED.increment();
		return true;
	}

	/** Whether a block hides what is behind it as well as a leaf does, for the "buried" test. */
	private static boolean buries(BlockState state) {
		return state.getBlock() instanceof LeavesBlock || state.isSolidRender();
	}

	/**
	 * Render thread, after vanilla marked the sections within one block of {@code pos} for rebuilding. A leaf face's
	 * culling also depends on blocks 2 and 3 steps away, so when a block starts or stops burying, the sections of
	 * the leaves 2..3 blocks away along each axis are rebuilt too (only those vanilla's one-block margin misses).
	 */
	public static void blockChanged(ClientLevel level, BlockPos pos, BlockState oldState, BlockState newState) {
		int mode = active;
		if (mode == OFF || mode == ALL || buries(oldState) == buries(newState)) {
			return;
		}
		int depth = mode == SMART ? 3 : 2;
		BlockPos.MutableBlockPos probe = MAIN_PROBE;
		for (Direction d : DIRECTIONS) {
			for (int k = 2; k <= depth; k++) {
				probe.setWithOffset(pos, d.getStepX() * k, d.getStepY() * k, d.getStepZ() * k);
				int sx = SectionPos.blockToSectionCoord(probe.getX());
				int sy = SectionPos.blockToSectionCoord(probe.getY());
				int sz = SectionPos.blockToSectionCoord(probe.getZ());
				// pos + d lies between pos and the probe: if they share a section, vanilla's 3x3x3 already marked it.
				if (sx == SectionPos.blockToSectionCoord(pos.getX() + d.getStepX())
						&& sy == SectionPos.blockToSectionCoord(pos.getY() + d.getStepY())
						&& sz == SectionPos.blockToSectionCoord(pos.getZ() + d.getStepZ())) {
					continue; // vanilla already marked this section
				}
				if (level.getBlockState(probe).getBlock() instanceof LeavesBlock) {
					Minecraft.getInstance().levelExtractor.setSectionDirty(sx, sy, sz);
				}
			}
		}
	}

	/**
	 * Section compile thread: the leaf block about to be tesselated. Whichever block renderer meshes it (vanilla's,
	 * or Fabric API's Indigo, which replaces it in SectionCompiler but still asks {@code Block.shouldRenderFace}),
	 * the face test then knows where the leaf is. Only leaf blocks are recorded.
	 */
	public static void beginBlock(BlockGetter region, BlockPos pos, BlockState state) {
		if (active == OFF || !(state.getBlock() instanceof LeavesBlock)) {
			return;
		}
		CompileContext ctx = CONTEXT.get();
		ctx.level = region;
		ctx.state = state;
		ctx.pos.set(pos);
	}

	/** Section compile thread, end of a compile: forget the region. */
	public static void endCompile() {
		if (active == OFF) {
			return;
		}
		CompileContext ctx = CONTEXT.get();
		ctx.level = null;
		ctx.state = null;
	}

	/**
	 * {@code Block.shouldRenderFace} said "draw": true drops the face. Acts only inside a section compile, for the
	 * leaf block recorded by {@link #beginBlock} (the only blocks that pass {@code state} here are the one being
	 * tesselated, so the identity check ties the call to its position).
	 */
	public static boolean cullAtCompile(BlockState state, BlockState neighbor, Direction direction) {
		if (active == OFF || !(state.getBlock() instanceof LeavesBlock) || !(neighbor.getBlock() instanceof LeavesBlock)) {
			return false;
		}
		CompileContext ctx = CONTEXT.get();
		if (ctx.state != state || ctx.level == null) {
			return false;
		}
		ctx.neighbor.setWithOffset(ctx.pos, direction);
		return cull(ctx.level, state, neighbor, ctx.neighbor, direction, ctx.probe);
	}

	private static final class CompileContext {
		BlockGetter level;
		BlockState state;
		final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
		final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
	}

	private static final ThreadLocal<CompileContext> CONTEXT = ThreadLocal.withInitial(CompileContext::new);

	private static final Direction[] DIRECTIONS = Direction.values();
}
