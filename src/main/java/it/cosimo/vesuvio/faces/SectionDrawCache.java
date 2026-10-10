package it.cosimo.vesuvio.faces;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.frame.AnimatedTextures;
import it.cosimo.vesuvio.ui.VesuvioStats;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Setting sectionDrawCache: the main view's terrain draw lists are reused while nothing they depend on changed.
 *
 * <p>Every frame, vanilla's LevelRenderer.extractSectionDrawGroups turns the visible sections into draw lists: for
 * each section and layer it looks up the mesh's draw and its place in the chunk buffers (two hash lookups), groups
 * the draws by buffer and makes a record for each (plus face culling's cut, FaceCulling). Its whole result is a
 * function of:
 * <ul>
 * <li>the visible sections, in order, each with its mesh and origin (LevelRenderer.visibleSections);</li>
 * <li>where each mesh sits in the chunk buffers (UberGpuBuffer allocations; counted by {@link #allocationsChanged},
 * called after every change to any of them: upload, free, close);</li>
 * <li>each drawn section's fade-in (always 1 once the fade is over);</li>
 * <li>respectTranslucentOrder, and for face culling its setting, split threshold and the camera's cell
 * (FaceCulling.cameraCell: the camera may move as long as no section's facing groups change).</li>
 * </ul>
 * When all of these match the last build, the last lists are handed back as they were built; otherwise vanilla
 * builds new ones and they are kept. The check runs under the chunk dispatcher's lock, like vanilla's own
 * extraction, so no allocation can change during it. A build is not kept while sections fade in, or while face
 * culling still has sections to regroup. The draw commands and section data are still written every frame by
 * vanilla's code that follows (they go to per-frame GPU memory).
 *
 * <p>Only vanilla's own main-view call is served (the one FaceCulling marks); Etna's shadow pass and any other
 * caller build their lists as usual. A hit still marks each visible mesh's animated textures as seen, as the
 * extraction would have (AnimatedTextures).
 */
public final class SectionDrawCache {
	public static final VesuvioConfig.Toggle CACHE = VesuvioConfig.toggle("rendering", "sectionDrawCache", true, false);

	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();
	private static final long WINDOW_NS = 1_000_000_000L;

	/** Changes to chunk buffer allocations so far (any thread, under the dispatcher's lock or on the render thread). */
	private static final AtomicInteger ALLOCATIONS = new AtomicInteger();

	// Render thread only. The kept build:
	private static boolean valid;
	private static @Nullable SectionRenderDispatcher dispatcher;
	private static boolean respectTranslucentOrder;
	private static int allocations;
	private static long faceKey;
	private static final int[] cell = new int[6];
	private static final int[] cellNow = new int[6];
	private static int count;
	private static Object[] sections = new Object[0];
	private static Object[] meshes = new Object[0];
	private static int[] origins = new int[0];
	/** Per visible section: it has a section info (something drawn), so its fade-in must stay over. */
	private static boolean[] drawn = new boolean[0];
	private static Object[] infos = new Object[0];
	@SuppressWarnings("unchecked")
	private static final List<Object>[] groups = new List[LAYERS.length];
	private static int largestIndexCount;
	private static int quadsSkipped;
	private static int quadsDrawn;
	private static int drawsAdded;

	private static long windowStart;
	private static int windowCalls;
	private static int windowHits;
	/** Share of the main view's prepares in the last second served from the kept lists, in percent. */
	public static int reusedPercent;

	private SectionDrawCache() {
	}

	/** Called from FaceCulling.init: makes the setting (on the Rendering page, after face culling's) and the counter. */
	static void init() {
		VesuvioStats.register("draw lists reused %", () -> reusedPercent);
	}

	/** UberGpuBuffer (UberAllocationsMixin): an allocation was added, freed or dropped. */
	public static void allocationsChanged() {
		ALLOCATIONS.incrementAndGet();
	}

	/**
	 * The wrapped extractSectionDrawGroups call of both prepare methods. Hands back the kept lists, or runs
	 * {@code build} (vanilla's extraction, filling {@code sectionInfos} and {@code drawGroups}) and keeps what it made.
	 */
	@SuppressWarnings({"unchecked", "rawtypes"})
	public static int extract(@Nullable SectionRenderDispatcher sectionDispatcher,
			ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, long fadeDuration, boolean respectOrder,
			List<?> sectionInfos, Map<?, ?> drawGroups, IntSupplier build) {
		if (!FaceCulling.inMainView()) {
			// Another caller (Etna's shadow pass): built as usual, and the kept main-view build stays.
			return build.getAsInt();
		}
		if (!CACHE.on() || sectionDispatcher == null) {
			valid = false;
			return build.getAsInt();
		}
		long now = System.nanoTime();
		if (now - windowStart >= WINDOW_NS) {
			reusedPercent = windowCalls == 0 ? 0 : windowHits * 100 / windowCalls;
			windowStart = now;
			windowCalls = 0;
			windowHits = 0;
		}
		windowCalls++;
		sectionDispatcher.lock();
		try {
			long key = FaceCulling.mainViewKey();
			boolean cellKnown = key == 0L || FaceCulling.cameraCell(cellNow);
			if (key == 0L) {
				Arrays.fill(cellNow, 0);
			}
			if (valid && cellKnown && sectionDispatcher == dispatcher && respectOrder == respectTranslucentOrder
					&& ALLOCATIONS.get() == allocations && key == faceKey && Arrays.equals(cellNow, cell)
					&& sameSections(visible, fadeDuration)) {
				List<Object> infosOut = (List<Object>) sectionInfos;
				for (Object info : infos) {
					infosOut.add(info);
				}
				Map<ChunkSectionLayer, List<Object>> groupsOut = (Map) drawGroups;
				for (ChunkSectionLayer layer : LAYERS) {
					List<Object> kept = groups[layer.ordinal()];
					if (!kept.isEmpty()) {
						groupsOut.get(layer).addAll(kept);
					}
				}
				FaceCulling.restoreMainViewCounters(quadsSkipped, quadsDrawn, drawsAdded);
				windowHits++;
				return largestIndexCount;
			}
			int generation = ALLOCATIONS.get();
			int result = build.getAsInt();
			keep(sectionDispatcher, visible, fadeDuration, respectOrder, cellKnown, generation,
					(List<Object>) sectionInfos, (Map) drawGroups, result);
			return result;
		} finally {
			sectionDispatcher.unlock();
		}
	}

	/**
	 * Whether the visible sections are the kept ones, in the same order, with the same meshes and origins and their
	 * fade-in over. Marks each mesh's animated textures as seen on the way (the extraction does that too).
	 */
	private static boolean sameSections(ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, long fadeDuration) {
		int n = visible.size();
		if (n != count) {
			return false;
		}
		Object[] elements = visible.elements();
		long millis = Util.getMillis();
		for (int i = 0; i < n; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			if (section != sections[i]) {
				return false;
			}
			SectionMesh mesh = section.getSectionMesh();
			if (mesh != meshes[i]) {
				return false;
			}
			BlockPos origin = section.getRenderOrigin();
			if (origin.getX() != origins[3 * i] || origin.getY() != origins[3 * i + 1] || origin.getZ() != origins[3 * i + 2]) {
				return false;
			}
			if (drawn[i] && section.getVisibility(millis, fadeDuration) != 1.0F) {
				return false;
			}
			AnimatedTextures.markMesh(mesh);
		}
		return true;
	}

	/** After a build: keeps it when it can be reused exactly, else forgets the last one. */
	private static void keep(SectionRenderDispatcher sectionDispatcher,
			ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, long fadeDuration, boolean respectOrder,
			boolean cellKnown, int generation, List<Object> sectionInfos, Map<ChunkSectionLayer, List<Object>> drawGroups,
			int result) {
		valid = false;
		if (!cellKnown || FaceCulling.ungroupedSectionsLastFrame > 0) {
			return;
		}
		for (Object info : sectionInfos) {
			if (!(info instanceof DynamicGpuData.ChunkSectionInfo sectionInfo) || sectionInfo.visibility() != 1.0F) {
				// Fading in: the section data changes every frame until it is over.
				return;
			}
		}
		int n = visible.size();
		if (sections.length < n) {
			int size = Math.max(n, sections.length * 3 / 2 + 16);
			sections = new Object[size];
			meshes = new Object[size];
			origins = new int[3 * size];
			drawn = new boolean[size];
		}
		Object[] elements = visible.elements();
		int infoIndex = 0;
		int drawnCount = 0;
		for (int i = 0; i < n; i++) {
			SectionRenderDispatcher.RenderSection section = (SectionRenderDispatcher.RenderSection) elements[i];
			sections[i] = section;
			meshes[i] = section.getSectionMesh();
			BlockPos origin = section.getRenderOrigin();
			origins[3 * i] = origin.getX();
			origins[3 * i + 1] = origin.getY();
			origins[3 * i + 2] = origin.getZ();
			drawn[i] = false;
		}
		// Section infos are made in the order of the visible sections, one for each section with a draw, holding its
		// origin: match them up to know which sections must stay faded in.
		for (int i = 0; i < n && infoIndex < sectionInfos.size(); i++) {
			DynamicGpuData.ChunkSectionInfo info = (DynamicGpuData.ChunkSectionInfo) sectionInfos.get(infoIndex);
			if (info.x() == origins[3 * i] && info.y() == origins[3 * i + 1] && info.z() == origins[3 * i + 2]) {
				drawn[i] = true;
				infoIndex++;
				drawnCount++;
			}
		}
		if (drawnCount != sectionInfos.size()) {
			// Not matched one to one (another mod changed the extraction?): don't reuse anything.
			return;
		}
		for (int i = n; i < count; i++) {
			sections[i] = null;
			meshes[i] = null;
		}
		count = n;
		infos = sectionInfos.toArray();
		for (ChunkSectionLayer layer : LAYERS) {
			List<Object> built = drawGroups.get(layer);
			groups[layer.ordinal()] = built == null ? List.of() : new ArrayList<>(built);
		}
		dispatcher = sectionDispatcher;
		respectTranslucentOrder = respectOrder;
		allocations = generation;
		faceKey = FaceCulling.lastMainViewKey();
		System.arraycopy(cellNow, 0, cell, 0, 6);
		largestIndexCount = result;
		quadsSkipped = FaceCulling.quadsSkippedLastFrame;
		quadsDrawn = FaceCulling.quadsDrawnLastFrame;
		drawsAdded = FaceCulling.drawsAddedLastFrame;
		valid = true;
	}
}
