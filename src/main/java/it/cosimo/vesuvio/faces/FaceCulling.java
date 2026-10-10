package it.cosimo.vesuvio.faces;

import java.util.List;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.Vesuvio;
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
 * <p>Only vanilla's own main-view call is cut (LevelRendererFacesMixin wraps the two prepare calls in
 * LevelRenderer.render). Any other caller, Etna's shadow pass included, gets whole meshes unless it asks
 * for culling along a direction with {@link #cullNextPrepareTowards}.
 */
public final class FaceCulling {
	public static final VesuvioConfig.Toggle FACE_CULLING = VesuvioConfig.toggle("rendering", "faceCulling", true, false);
	/** A hidden run of quads between two drawn ones is skipped (at the price of one more draw) only from this many quads. */
	public static final VesuvioConfig.Number MIN_GAP = VesuvioConfig.number("rendering", "faceCullingMinGap", 0, 65536, 64, false);

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
		minGap = MIN_GAP.get();
		boolean on = FACE_CULLING.on();
		if (mainViewCall) {
			mode = on ? MAIN : NONE;
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
