package it.cosimo.vesuvio.faces;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.renderpearl.util.PlatformUtil;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.draws.SubmitTimers;
import it.cosimo.vesuvio.ui.VesuvioStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.DynamicGpuData;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Per-direction face culling of terrain sections (the idea Sodium made known; this is Vesuvio's own code).
 *
 * <p>FaceSorter sorts each opaque section mesh's quads into seven groups when the section is built: the
 * six directions a quad on the block grid can face, and everything else. When vanilla's main view
 * prepares its terrain draws (LevelRenderer.extractSectionDrawGroups), each opaque section draw is cut to
 * the groups that can face the camera: quads facing +X are front faces only when the camera is east of
 * their plane, so a section wholly west of the camera has none to draw. The GPU would throw those faces
 * away anyway (back-face culling), but only after shading their vertices: skipping them first saves about
 * half the vertex work of the terrain pass, and nothing in the picture changes.
 *
 * <p>Group order in the buffer: +X +Z +Y unassigned -Z -X -Y. Of the eight ways a section can lie from the
 * camera (one side of each axis), this order (one of the best of all 5040) needs 1 draw for 3 of them, 2
 * for 2 and 3 for 3: 2 on average, 1.5 when the camera is above the section, and a single draw for any
 * sun or moon position in the shadow pass (light from above, both Z sides). Hidden groups at either end of
 * a draw cost nothing to leave out; a hidden run between two visible ones costs a second draw (on the Mac,
 * one more Metal draw per section), so it is left out only when it has at least faceCullingMinGap quads,
 * otherwise drawn with the rest.
 *
 * <p>faceCullingMinGap is how many hidden quads are worth one more draw when the GPU's time is what counts. With
 * faceCullingAutoSplit on (default), on macOS that holds only while the GPU is the limit: MoltenVK turns every
 * draw into a Metal draw on the CPU (about 1 us each), so while the CPU is the limit a split costs frame time and
 * the vertex work it saves on the idle GPU buys nothing; there the effective value is the highest one (no draw is
 * ever split, the hidden ends are still left out). Which side is the limit comes from the submit timers
 * (SubmitTimers.gpuBound: at least 10% of the frame spent waiting for the GPU, decided once a second). On
 * Windows and Linux the drivers draw an indirect batch as one command and the value applies as set. Any split
 * choice draws the same picture.
 *
 * <p>Only vanilla's own main-view call is cut (LevelRendererFacesMixin wraps the two prepare calls in
 * LevelRenderer.render). Any other caller, Etna's shadow pass included, gets whole meshes unless it asks
 * for culling along a direction with {@link #cullNextPrepareTowards}.
 */
public final class FaceCulling {
	public static final VesuvioConfig.Toggle FACE_CULLING = VesuvioConfig.toggle("rendering", "faceCulling", true, false);
	/** A hidden run of quads between two drawn ones is skipped (at the price of one more draw) only from this many quads. */
	public static final VesuvioConfig.Number MIN_GAP = VesuvioConfig.number("rendering", "faceCullingMinGap", 0, 65536, 64, false);
	/** On macOS, splits only while the GPU is the limit (see the class comment); faceCullingMinGap as set everywhere else. */
	public static final VesuvioConfig.Toggle AUTO_SPLIT = VesuvioConfig.toggle("rendering", "faceCullingAutoSplit", true, false);

	// Counters for the settings screen and benchmark (render thread). Main view, last frame:
	/** Opaque terrain quads left out of the main view's draws. */
	public static int quadsSkippedLastFrame;
	/** Opaque terrain quads drawn in the main view (skipped / (skipped + drawn) is the share saved). */
	public static int quadsDrawnLastFrame;
	/** Draws added by splitting section draws in two or three (each one indirect command / Metal draw more). */
	public static int drawsAddedLastFrame;
	/** Opaque layers of visible sections drawn whole because they were built while face culling was off (being rebuilt). */
	public static int ungroupedSectionsLastFrame;
	/** The same for the last prepare that used {@link #cullNextPrepareTowards} (Etna's shadow pass). */
	public static int directionalQuadsSkippedLastPass;
	public static int directionalDrawsAddedLastPass;

	/** How far outside a section its grouped planes can be (FaceSorter), plus room for view bobbing. */
	private static final double MARGIN = FaceSorter.PLANE_MARGIN + 0.5;
	/** For directional culling: a light this close to edge-on to a facing keeps both sides of it. */
	private static final float EDGE_ON = 1.0E-3F;
	private static final int ALL = (1 << FaceSorter.GROUPS) - 1;
	private static final int REGROUP_PER_FRAME = 24;

	private static final int NONE = 0;
	private static final int MAIN = 1;
	private static final int DIRECTIONAL = 2;

	private static final ChunkSectionLayer[] LAYERS = ChunkSectionLayer.values();

	// Render thread only.
	private static boolean mainViewCall;
	private static double camX;
	private static double camY;
	private static double camZ;
	private static boolean armed;
	private static int armedMask;
	private static final boolean[] armedLayers = new boolean[LAYERS.length];

	private static int mode = NONE;
	/** {@link #mainViewKey} as the last main-view prepare used it. */
	private static long lastMainViewKey;
	private static int directionalMask;
	private static final boolean[] layerOn = new boolean[LAYERS.length];
	private static int minGap;
	private static int skipped;
	private static int drawn;
	private static int added;
	private static int ungrouped;
	private static final int[] regroup = new int[REGROUP_PER_FRAME * 3];
	private static int regroupCount;
	private static boolean regroupFailed;
	/** Per layer: 0 not checked yet, 1 its pipelines cull back faces, 2 they don't (never cut). */
	private static final byte[] layerCulls = new byte[LAYERS.length];

	private FaceCulling() {
	}

	/** Called from Vesuvio.onInitializeClient: makes the settings and registers the counters. */
	public static void init() {
		// F3, the settings screen's box, the stats log and the benchmark's counter table.
		VesuvioStats.register("terrain quads skipped %", FaceCulling::skippedPercent);
		VesuvioStats.register("face draws added", () -> drawsAddedLastFrame);
		SectionDrawCache.init();
	}

	/**
	 * The split threshold for this frame: faceCullingMinGap, or on macOS with faceCullingAutoSplit while the CPU is
	 * the limit, the highest value (no splits). Render thread.
	 */
	static int effectiveMinGap() {
		if (PlatformUtil.IS_MACOS && AUTO_SPLIT.on() && !SubmitTimers.gpuBound()) {
			return MIN_GAP.max;
		}
		return MIN_GAP.get();
	}

	/** Inside vanilla's own main-view prepare call (SectionDrawCache only serves that one). */
	static boolean inMainView() {
		return mainViewCall;
	}

	/**
	 * Everything besides the camera that decides how a main-view prepare cuts its draws: 0 with face culling off,
	 * else on plus the split threshold. Render thread, inside the main-view call.
	 */
	static long mainViewKey() {
		return FACE_CULLING.on() ? (1L << 32) | effectiveMinGap() : 0L;
	}

	/** {@link #mainViewKey} as the last main-view prepare computed it. */
	static long lastMainViewKey() {
		return lastMainViewKey;
	}

	/**
	 * The main view's camera, reduced to what decides the facing groups shown ({@link #mainMask}): per axis, the
	 * first section index whose positive-facing group is hidden and the first whose negative-facing group is shown.
	 * Two cameras with the same six numbers give every section the same mask. False when the camera is not a usable
	 * number. Render thread, inside the main-view call.
	 */
	static boolean cameraCell(int[] out) {
		out[0] = firstHiddenPositive(camX);
		out[1] = firstShownNegative(camX);
		out[2] = firstHiddenPositive(camY);
		out[3] = firstShownNegative(camY);
		out[4] = firstHiddenPositive(camZ);
		out[5] = firstShownNegative(camZ);
		for (int i = 0; i < 6; i++) {
			if (out[i] == Integer.MIN_VALUE) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The smallest k for which a section with origin 16k along this axis gets no positive-facing group from
	 * {@link #mainMask} (the test there, {@code cam - origin > -MARGIN}, is true for small k and false from here on).
	 */
	private static int firstHiddenPositive(double cam) {
		if (!(Math.abs(cam) < 1.0E8)) {
			return Integer.MIN_VALUE;
		}
		int k = (int) Math.ceil((cam + MARGIN) / 16.0);
		while (!(cam - (double) (16 * (k - 1)) > -MARGIN)) {
			k--;
		}
		while (cam - (double) (16 * k) > -MARGIN) {
			k++;
		}
		return k;
	}

	/**
	 * The smallest k for which a section with origin 16k gets its negative-facing group ({@code cam - origin <
	 * 16 + MARGIN} in {@link #mainMask}: false for small k, true from here on).
	 */
	private static int firstShownNegative(double cam) {
		if (!(Math.abs(cam) < 1.0E8)) {
			return Integer.MIN_VALUE;
		}
		int k = (int) Math.floor((cam - 16.0 - MARGIN) / 16.0) + 1;
		while (cam - (double) (16 * (k - 1)) < 16.0 + MARGIN) {
			k--;
		}
		while (!(cam - (double) (16 * k) < 16.0 + MARGIN)) {
			k++;
		}
		return k;
	}

	/** A main-view prepare served from SectionDrawCache: puts back the counters its cached build published. */
	static void restoreMainViewCounters(int skippedQuads, int drawnQuads, int addedDraws) {
		quadsSkippedLastFrame = skippedQuads;
		quadsDrawnLastFrame = drawnQuads;
		drawsAddedLastFrame = addedDraws;
		ungroupedSectionsLastFrame = 0;
	}

	/** Share of the main view's opaque terrain quads left out last frame, in percent. */
	private static int skippedPercent() {
		long skippedQuads = quadsSkippedLastFrame;
		long all = skippedQuads + quadsDrawnLastFrame;
		return all == 0 ? 0 : (int) (skippedQuads * 100 / all);
	}

	/**
	 * For a later step of Etna's shadow pass: cull the faces turned away from a directional light in the
	 * next call of LevelRenderer.prepareChunkRenders / prepareChunkRendersIndirect that is not vanilla's main
	 * view (call it right before that prepare; it is used once, and forgotten at the end of the frame's
	 * main-view prepare if no prepare took it). {@code towardsLight} points from the terrain to the light
	 * (the sun or moon), in world axes; the view must be orthographic along it with back faces culled, so a
	 * face is drawn only when its normal has a positive part along it. Name only the layers whose pipeline
	 * culls back faces in that pass (Etna: SOLID with shadowCulling on; not CUTOUT, drawn double-sided). Does
	 * nothing while faceCulling (or the master switch) is off. Render thread only.
	 */
	public static void cullNextPrepareTowards(float towardsLightX, float towardsLightY, float towardsLightZ,
			ChunkSectionLayer... layers) {
		float length = (float) Math.sqrt(towardsLightX * towardsLightX + towardsLightY * towardsLightY
				+ towardsLightZ * towardsLightZ);
		if (!(length > 0.0F)) {
			armed = false;
			return;
		}
		float x = towardsLightX / length;
		float y = towardsLightY / length;
		float z = towardsLightZ / length;
		int mask = 1 << FaceSorter.UNASSIGNED;
		mask |= x > -EDGE_ON ? 1 << FaceSorter.PX : 0;
		mask |= x < EDGE_ON ? 1 << FaceSorter.NX : 0;
		mask |= y > -EDGE_ON ? 1 << FaceSorter.PY : 0;
		mask |= y < EDGE_ON ? 1 << FaceSorter.NY : 0;
		mask |= z > -EDGE_ON ? 1 << FaceSorter.PZ : 0;
		mask |= z < EDGE_ON ? 1 << FaceSorter.NZ : 0;
		armedMask = mask;
		java.util.Arrays.fill(armedLayers, false);
		for (ChunkSectionLayer layer : layers) {
			armedLayers[layer.ordinal()] = !layer.translucent();
		}
		armed = true;
	}

	/** Forgets a {@link #cullNextPrepareTowards} no prepare has taken yet. */
	public static void clearNextPrepare() {
		armed = false;
	}

	// Called by LevelRendererFacesMixin around vanilla's own prepare call in LevelRenderer.render.

	public static void beginMainView(Vec3 camera) {
		mainViewCall = true;
		camX = camera.x;
		camY = camera.y;
		camZ = camera.z;
	}

	public static void endMainView() {
		mainViewCall = false;
		armed = false;
	}

	/** Head of extractSectionDrawGroups: decides how this prepare cuts its draws. */
	public static void beginPrepare() {
		skipped = 0;
		drawn = 0;
		added = 0;
		ungrouped = 0;
		regroupCount = 0;
		minGap = effectiveMinGap();
		boolean on = FACE_CULLING.on();
		if (mainViewCall) {
			mode = on ? MAIN : NONE;
			lastMainViewKey = on ? (1L << 32) | minGap : 0L;
			for (ChunkSectionLayer layer : LAYERS) {
				layerOn[layer.ordinal()] = on && !layer.translucent() && culls(layer);
			}
		} else if (armed) {
			armed = false;
			mode = on ? DIRECTIONAL : NONE;
			directionalMask = armedMask;
			System.arraycopy(armedLayers, 0, layerOn, 0, layerOn.length);
		} else {
			mode = NONE;
		}
	}

	/** Return of extractSectionDrawGroups: publishes the counters, asks for rebuilds of ungrouped sections. */
	public static void endPrepare() {
		if (mode == MAIN) {
			quadsSkippedLastFrame = skipped;
			quadsDrawnLastFrame = drawn;
			drawsAddedLastFrame = added;
			ungroupedSectionsLastFrame = ungrouped;
			requestRegroups();
		} else if (mainViewCall) {
			// Main view with face culling off.
			quadsSkippedLastFrame = 0;
			quadsDrawnLastFrame = 0;
			drawsAddedLastFrame = 0;
			ungroupedSectionsLastFrame = 0;
		} else if (mode == DIRECTIONAL) {
			directionalQuadsSkippedLastPass = skipped;
			directionalDrawsAddedLastPass = added;
		}
		mode = NONE;
	}

	/**
	 * The wrapped {@code drawGroup.draws.add(new IndexedDraw(...))} of extractSectionDrawGroups, for one
	 * layer of one section. Adds the draw as vanilla does, or the one to three draws that cover the groups
	 * that can face the camera.
	 */
	public static boolean add(List<?> draws, Object element, Operation<Boolean> original, Object sectionMesh,
			ChunkSectionLayer layer, BlockPos origin) {
		if (mode == NONE || !layerOn[layer.ordinal()] || !(element instanceof DynamicGpuData.IndexedDraw draw)
				|| !(sectionMesh instanceof FaceGroups mesh)) {
			return original.call(draws, element);
		}
		int[][] groups = mesh.vesuvio$faceGroups();
		if (groups == null) {
			// Built while face culling was off: drawn whole; have it rebuilt (grouped) in the background.
			ungrouped++;
			if (mode == MAIN && !mesh.vesuvio$regroupAsked() && regroupCount < REGROUP_PER_FRAME) {
				mesh.vesuvio$setRegroupAsked();
				int i = 3 * regroupCount++;
				regroup[i] = origin.getX() >> 4;
				regroup[i + 1] = origin.getY() >> 4;
				regroup[i + 2] = origin.getZ() >> 4;
			}
			return original.call(draws, element);
		}
		int[] starts = groups[layer.ordinal()];
		if (starts == null) {
			return original.call(draws, element);
		}
		int total = starts[FaceSorter.GROUPS];
		if (draw.firstIndex() != 0 || draw.indexCount() != total * 6) {
			return original.call(draws, element);
		}
		int visible = mode == MAIN ? mainMask(origin) : directionalMask;
		if (visible == ALL) {
			drawn += total;
			return original.call(draws, element);
		}

		boolean result = true;
		int runs = 0;
		int quads = 0;
		int runStart = -1;
		int runEnd = 0;
		for (int g = 0; g < FaceSorter.GROUPS; g++) {
			int from = starts[g];
			int to = starts[g + 1];
			if (from == to || (visible & (1 << g)) == 0) {
				continue;
			}
			if (runStart < 0) {
				runStart = from;
			} else if (from > runEnd && from - runEnd >= minGap) {
				result &= original.call(draws, part(draw, runStart, runEnd));
				runs++;
				quads += runEnd - runStart;
				runStart = from;
			}
			runEnd = to;
		}
		if (runStart >= 0) {
			result &= original.call(draws, part(draw, runStart, runEnd));
			runs++;
			quads += runEnd - runStart;
		} else {
			// Every quad faces away. Keep one draw of the first quad (which the GPU culls, as it would have)
			// rather than none: vanilla's draw lists must not end up empty.
			result &= original.call(draws, part(draw, 0, 1));
			runs = 1;
			quads = 1;
		}
		drawn += quads;
		skipped += total - quads;
		added += runs - 1;
		return result;
	}

	private static DynamicGpuData.IndexedDraw part(DynamicGpuData.IndexedDraw draw, int fromQuad, int toQuad) {
		// The shared sequential quad index buffer: quad q is indices 6q .. 6q+5, naming vertices 4q .. 4q+3.
		return new DynamicGpuData.IndexedDraw((toQuad - fromQuad) * 6, draw.instanceCount(), fromQuad * 6,
				draw.baseVertex(), draw.baseInstance());
	}

	/** The groups that can face the camera, for a section whose corner is at {@code origin}. */
	private static int mainMask(BlockPos origin) {
		double x = camX - origin.getX();
		double y = camY - origin.getY();
		double z = camZ - origin.getZ();
		int mask = 1 << FaceSorter.UNASSIGNED;
		mask |= x > -MARGIN ? 1 << FaceSorter.PX : 0;
		mask |= x < 16.0 + MARGIN ? 1 << FaceSorter.NX : 0;
		mask |= y > -MARGIN ? 1 << FaceSorter.PY : 0;
		mask |= y < 16.0 + MARGIN ? 1 << FaceSorter.NY : 0;
		mask |= z > -MARGIN ? 1 << FaceSorter.PZ : 0;
		mask |= z < 16.0 + MARGIN ? 1 << FaceSorter.NZ : 0;
		return mask;
	}

	/**
	 * Whether every pipeline vanilla can draw this layer with drops back faces (it does in vanilla; checked
	 * once, in case another mod changes that). Cutting a layer drawn double-sided would lose faces.
	 */
	private static boolean culls(ChunkSectionLayer layer) {
		int i = layer.ordinal();
		if (layerCulls[i] == 0) {
			boolean cull = layer.pipeline(false, false).isCull() && layer.pipeline(true, false).isCull()
					&& layer.pipeline(false, true).isCull() && layer.pipeline(true, true).isCull();
			layerCulls[i] = (byte) (cull ? 1 : 2);
			if (!cull) {
				Vesuvio.LOGGER.info("Vesuvio: {} terrain is drawn double-sided, face culling leaves it whole", layer.label());
			}
		}
		return layerCulls[i] == 1;
	}

	/**
	 * Sections built while face culling was off are drawn whole. Once it is on, the visible ones are marked
	 * for a rebuild, a few each frame: the old mesh stays on screen until the new one is uploaded, and
	 * the fade-in does not run again, so nothing flickers.
	 */
	private static void requestRegroups() {
		if (regroupCount == 0 || regroupFailed) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null) {
			return;
		}
		try {
			for (int i = 0; i < regroupCount; i++) {
				minecraft.levelExtractor.setSectionDirty(regroup[3 * i], regroup[3 * i + 1], regroup[3 * i + 2]);
			}
		} catch (RuntimeException e) {
			regroupFailed = true;
			Vesuvio.LOGGER.warn("Vesuvio: could not ask for section rebuilds; sections built while face culling "
					+ "was off stay drawn whole until they are rebuilt", e);
		}
	}
}
