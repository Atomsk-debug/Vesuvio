package it.cosimo.vesuvio.chunks;

import java.util.Arrays;

import it.cosimo.vesuvio.chunks.mixin.RenderSectionInvoker;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SectionUpdateRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.Util;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

/**
 * Replaces the "sectionUpdates" loop of {@code LevelExtractor.extract} (render thread, once a frame).
 *
 * <p>Vanilla walks every section in view and, for each dirty one that may be built, copies its 3x3x3 neighbourhood
 * of block data on the render thread and queues a compile task, all in the same frame. After joining a world, after
 * a teleport, or when flying into new terrain that is hundreds or thousands of sections in one frame (a long frame),
 * and the task queue grows to thousands of entries that every worker scans linearly, under one lock, for each task
 * it takes. Here the same sections are found with the same rules, but:
 * <ul>
 * <li>sections the player changed and sections within vanilla's "nearby" distance (about 27 blocks) are queued at
 * once, as in vanilla;</li>
 * <li>the others are queued only up to what keeps the workers busy (the queue is topped up to
 * {@link #TARGET_QUEUE} tasks, at least {@link #MIN_PER_FRAME} a frame), nearest first; the rest simply stay dirty
 * and are looked at again next frame, with the camera's new view, so turning or flying never leaves the workers
 * building sections that went out of view while newer ones in front wait;</li>
 * <li>with {@link ChunkUpdates#SKIP_EMPTY}, a section that holds only air gets the empty mesh straight away (what its
 * compile would produce: no blocks, no block entities), with no copy and no task.</li>
 * </ul>
 * Nothing is dropped: a deferred section keeps its dirty flag, exactly like a dirty section out of view in vanilla,
 * and is built as soon as it is among the nearest pending ones.
 */
public final class ChunkScheduler {
	private static final ObjectArrayList<SectionRenderDispatcher.RenderSection> NOTHING = new ObjectArrayList<>(0);
	/** Squared block distance below which vanilla calls a section "nearby" (LevelRenderer.compileSections). */
	private static final long NEARBY_DIST_SQR = 768L;
	private static final int THREADS = Util.maxAllowedExecutorThreads();
	/** Tasks the compile queue is topped up to each frame: enough for every worker for a frame or two. */
	static final int TARGET_QUEUE = Math.max(48, THREADS * 24);
	/** New far tasks allowed every frame even with a full queue, so the nearest pending ones always get in. */
	static final int MIN_PER_FRAME = Math.max(4, THREADS);

	// Counters (render thread writes, read anywhere).
	private static volatile int scheduledLastFrame;
	private static volatile int deferredLastFrame;
	private static volatile int emptyLastFrame;
	private static volatile long emptyTotal;

	// Scratch, render thread only.
	private static SectionRenderDispatcher.RenderSection[] candSection = new SectionRenderDispatcher.RenderSection[256];
	private static SectionUpdateTracker.SectionDirtyState[] candState = new SectionUpdateTracker.SectionDirtyState[256];
	private static int[] candBucket = new int[256];
	private static final int[] HISTOGRAM = new int[256];

	private ChunkScheduler() {
	}

	/** Counter: section rebuilds queued in the last frame (incl. nearby / player-changed ones). */
	public static int scheduledLastFrame() {
		return scheduledLastFrame;
	}

	/** Counter: dirty sections in view left for a later frame in the last frame (0 = nothing waiting). */
	public static int deferredLastFrame() {
		return deferredLastFrame;
	}

	/** Counter: all-air sections given their empty mesh directly in the last frame. */
	public static int emptyLastFrame() {
		return emptyLastFrame;
	}

	/** Counter: all-air sections given their empty mesh directly since start-up (copies + tasks saved). */
	public static long emptyTotal() {
		return emptyTotal;
	}

	/**
	 * Render thread, in place of vanilla's loop. Returns the list vanilla's loop should walk: {@code visible} when
	 * both settings are off (vanilla does everything), otherwise an empty list (the work was done here).
	 */
	public static ObjectArrayList<SectionRenderDispatcher.RenderSection> schedule(
			ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, LevelRenderer renderer, ClientLevel level,
			SectionUpdateTracker tracker, LevelRenderState state, Vec3 cameraPos) {
		boolean limit = ChunkUpdates.SCHEDULER.on();
		boolean skipEmpty = ChunkUpdates.SKIP_EMPTY.on() && !level.isDebug();
		SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
		if ((!limit && !skipEmpty) || dispatcher == null) {
			scheduledLastFrame = 0;
			deferredLastFrame = 0;
			emptyLastFrame = 0;
			return visible;
		}
		int camX = Mth.floor(cameraPos.x);
		int camY = Mth.floor(cameraPos.y);
		int camZ = Mth.floor(cameraPos.z);
		RenderRegionCache cache = new RenderRegionCache();
		int scheduled = 0;
		int empty = 0;
		int candidates = 0;
		Object[] elements = visible.elements();
		for (int i = 0, n = visible.size(); i < n; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			long node = section.getSectionNode();
			SectionUpdateTracker.SectionDirtyState dirty = tracker.getDirtyState(node);
			// Vanilla's condition, unchanged: dirty, and either already built once or all 8 neighbour columns lit.
			if (dirty == null || !dirty.isDirty()
					|| (section.sectionMesh.get() == CompiledSectionMesh.UNCOMPILED && !tracker.hasAllNeighbors(level, node))) {
				continue;
			}
			if (skipEmpty && isOnlyAir(level, node)) {
				setEmpty(section, dispatcher);
				dirty.setNotDirty();
				empty++;
				continue;
			}
			if (!limit || dirty.isDirtyFromPlayer()) {
				submit(section, dirty, level, cache, state);
				scheduled++;
				continue;
			}
			long dx = SectionPos.sectionToBlockCoord(SectionPos.x(node)) + 8 - camX;
			long dy = SectionPos.sectionToBlockCoord(SectionPos.y(node)) + 8 - camY;
			long dz = SectionPos.sectionToBlockCoord(SectionPos.z(node)) + 8 - camZ;
			long distSqr = dx * dx + dy * dy + dz * dz;
			if (distSqr < NEARBY_DIST_SQR) {
				submit(section, dirty, level, cache, state);
				scheduled++;
				continue;
			}
			if (candidates == candSection.length) {
				candSection = Arrays.copyOf(candSection, candidates * 2);
				candState = Arrays.copyOf(candState, candidates * 2);
				candBucket = Arrays.copyOf(candBucket, candidates * 2);
			}
			candSection[candidates] = section;
			candState[candidates] = dirty;
			candBucket[candidates] = Math.min(255, (int) Math.sqrt((double) distSqr) >> 4);
			candidates++;
		}

		int deferred = 0;
		if (candidates > 0) {
			int allowed = Math.max(MIN_PER_FRAME, TARGET_QUEUE - dispatcher.getCompileQueueSize());
			if (candidates <= allowed) {
				for (int i = 0; i < candidates; i++) {
					submit(candSection[i], candState[i], level, cache, state);
				}
				scheduled += candidates;
			} else {
				// Nearest first: whole distance rings (one section wide) while they fit, then part of the next.
				int[] hist = HISTOGRAM;
				Arrays.fill(hist, 0);
				for (int i = 0; i < candidates; i++) {
					hist[candBucket[i]]++;
				}
				int cut = 0;
				int taken = 0;
				while (cut < hist.length && taken + hist[cut] <= allowed) {
					taken += hist[cut];
					cut++;
				}
				int fromCut = allowed - taken;
				for (int i = 0; i < candidates; i++) {
					int bucket = candBucket[i];
					if (bucket < cut || (bucket == cut && fromCut-- > 0)) {
						submit(candSection[i], candState[i], level, cache, state);
						scheduled++;
					} else {
						deferred++;
					}
				}
			}
			Arrays.fill(candSection, 0, candidates, null);
			Arrays.fill(candState, 0, candidates, null);
		}
		scheduledLastFrame = scheduled;
		deferredLastFrame = deferred;
		emptyLastFrame = empty;
		emptyTotal += empty;
		return NOTHING;
	}

	/** Exactly what vanilla's loop does for a section it rebuilds. */
	private static void submit(SectionRenderDispatcher.RenderSection section, SectionUpdateTracker.SectionDirtyState dirty,
			ClientLevel level, RenderRegionCache cache, LevelRenderState state) {
		long node = section.getSectionNode();
		state.sectionUpdateRenderStates.add(
				new SectionUpdateRenderState(node, dirty.isDirtyFromPlayer(), cache.createRegion(level, node)));
		dirty.setNotDirty();
	}

	/**
	 * Whether the section holds no block at all, read from the same chunk data vanilla's section copy would read
	 * this frame. Missing chunks and out-of-range sections take vanilla's path.
	 */
	private static boolean isOnlyAir(ClientLevel level, long node) {
		LevelChunk chunk = level.getChunkSource().getChunk(SectionPos.x(node), SectionPos.z(node), false);
		if (chunk == null) {
			return false;
		}
		int index = chunk.getSectionIndexFromSectionY(SectionPos.y(node));
		LevelChunkSection[] sections = chunk.getSections();
		return index >= 0 && index < sections.length && sections[index].hasOnlyAir();
	}

	/**
	 * What a compile of an all-air section ends in (SectionRenderDispatcher.RenderSection.CompileTask.doTask with no
	 * rendered layer and no block entity), done here on the render thread instead of a worker: cancel the section's
	 * pending tasks (as createCompileTask does, so an older build can't land later), show the EMPTY mesh, free the
	 * old one's GPU memory under the dispatcher's lock.
	 */
	private static void setEmpty(SectionRenderDispatcher.RenderSection section, SectionRenderDispatcher dispatcher) {
		RenderSectionInvoker invoker = (RenderSectionInvoker) (Object) section;
		invoker.vesuvio$cancelTasks();
		// Counts as a build started now: an older build of this section still on its way can't replace it later.
		MeshOrder.taskStarted(Util.getNanos());
		SectionMesh old = invoker.vesuvio$setSectionMesh(CompiledSectionMesh.EMPTY);
		dispatcher.lock();
		try {
			invoker.vesuvio$releaseSectionMesh(old);
		} finally {
			dispatcher.unlock();
		}
	}

	/** For LevelRenderer.hasRenderedAllSections: false while dirty sections in view still wait for their turn. */
	public static boolean nothingDeferred() {
		return deferredLastFrame == 0;
	}
}
