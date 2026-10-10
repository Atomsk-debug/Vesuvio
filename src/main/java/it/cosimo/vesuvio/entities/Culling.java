package it.cosimo.vesuvio.entities;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.entities.mixin.EntityRendererInvoker;
import it.cosimo.vesuvio.entities.mixin.FrustumInvoker;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SectionOcclusionGraph;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.monster.Shulker;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.piston.PistonMovingBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * Skips drawing entities, block entities and particles the camera cannot see. Render-thread side: called from the
 * mixins in {@code LevelExtractor} (entity / block entity extraction) and {@code QuadParticleGroup} (particle
 * extraction); the ray casts run on {@link CullWorker}.
 *
 * <ul>
 * <li>Entities and block entities that pass vanilla's own checks are queued for an occlusion test each frame; one
 * whose latest fresh result says "hidden" is not extracted (no render state, no submit, no draw). Unknown, stale
 * or failed results mean visible, so the worst case is a hidden object drawn anyway, or one that comes into view
 * a frame or two late.</li>
 * <li>Block entities also get a per-object frustum test (vanilla only tests their 16x16x16 section).</li>
 * <li>Particles in a section that vanilla's own section occlusion graph cannot reach (the same test that hides
 * cave terrain) are not extracted.</li>
 * </ul>
 */
public final class Culling {
	public static final VesuvioConfig.Toggle ENTITIES = VesuvioConfig.toggle("entities", "entityCulling", true, false);
	public static final VesuvioConfig.Toggle BLOCK_ENTITIES = VesuvioConfig.toggle("entities", "blockEntityCulling", true, false);
	public static final VesuvioConfig.Toggle PARTICLES = VesuvioConfig.toggle("entities", "particleCulling", true, false);

	// ---- Counters (render thread writes, settings screen / benchmark reads) ----
	/** Entities that passed vanilla's checks but were hidden by occlusion culling, last frame. */
	public static int entitiesCulledLastFrame;
	/** Entities sent to the occlusion test last frame (vanilla-visible candidates). */
	public static int entitiesTestedLastFrame;
	/** Block entities skipped last frame (occlusion + per-object frustum). */
	public static int blockEntitiesCulledLastFrame;
	/** Of those, skipped by the per-object frustum test alone. */
	public static int blockEntitiesFrustumCulledLastFrame;
	/** Block entities sent to the occlusion test last frame. */
	public static int blockEntitiesTestedLastFrame;
	/** Particles inside the view frustum but in an unreachable section, skipped last frame. */
	public static int particlesCulledLastFrame;
	/** Occlusion tests (one per box) done by the worker per second, and ray casts per second. */
	public static int testsPerSecond;
	public static int raysPerSecond;
	/** Duration of the worker's last pass, microseconds. */
	public static int lastPassMicros;

	/** A result is used only if its pass is this recent and was made from a camera this close to the current one. */
	private static final long MAX_RESULT_AGE_NANOS = 150_000_000L;
	private static final double MAX_RESULT_CAMERA_MOVE = 1.0;
	/** Never cull an entity box bigger than this on any axis (huge custom models, broken boxes). */
	private static final double MAX_ENTITY_BOX = 32.0;

	/** Half-size of the box around the camera that must be free of opaque blocks (> the 0.05 near plane). */
	private static final double NEAR = 0.15;
	private static final BlockPos.MutableBlockPos SCRATCH_POS = new BlockPos.MutableBlockPos();

	private static boolean entitiesOn;
	private static boolean blockEntityFrustumOn;
	private static boolean blockEntityOcclusionOn;
	private static boolean particlesOn;
	private static long minValidPass = Long.MAX_VALUE;
	private static CullWorker.Batch batch;
	private static Frustum frustum;
	private static Minecraft minecraft;
	private static Entity cameraEntity;
	private static double camX;
	private static double camY;
	private static double camZ;
	private static double camSpeedMargin;
	private static double lastCamX = Double.NaN;
	private static double lastCamY;
	private static double lastCamZ;

	private static int entityCulled;
	private static int entityTested;
	private static int blockEntityCulled;
	private static int blockEntityFrustumCulled;
	private static int blockEntityTested;
	private static int particleCulled;

	private static ViewArea viewArea;
	private static SectionOcclusionGraph graph;
	private static long lastParticleSection = Long.MIN_VALUE;
	private static boolean lastParticleSectionHidden;

	private static long statsStart;
	private static long statsTests;
	private static long statsRays;

	private Culling() {
	}

	/** Called from Vesuvio.onInitializeClient so the settings exist early. */
	public static void init() {
	}

	// ------------------------------------------------------------------ frame

	/** LevelExtractor.extract, HEAD (render thread). */
	public static void beginFrame(Minecraft mc, Camera camera, ClientLevel level, LevelRenderer levelRenderer) {
		if (batch != null) {
			batch.clear();
		}
		entityCulled = 0;
		entityTested = 0;
		blockEntityCulled = 0;
		blockEntityFrustumCulled = 0;
		blockEntityTested = 0;
		particleCulled = 0;
		lastParticleSection = Long.MIN_VALUE;

		boolean anyOn = ENTITIES.on() || BLOCK_ENTITIES.on() || PARTICLES.on();
		if (!anyOn || level == null || camera == null || !camera.isInitialized()) {
			entitiesOn = blockEntityFrustumOn = blockEntityOcclusionOn = particlesOn = false;
			return;
		}
		minecraft = mc;
		cameraEntity = camera.entity();
		frustum = camera.getCullFrustum();
		camX = camera.position().x;
		camY = camera.position().y;
		camZ = camera.position().z;
		double moved = Double.isNaN(lastCamX) ? 0.0
				: Math.sqrt((camX - lastCamX) * (camX - lastCamX) + (camY - lastCamY) * (camY - lastCamY)
						+ (camZ - lastCamZ) * (camZ - lastCamZ));
		lastCamX = camX;
		lastCamY = camY;
		lastCamZ = camZ;
		// Results lag a frame or two: grow the tested boxes by about two frames of camera movement.
		camSpeedMargin = Math.min(1.0, moved * 2.0);

		// Same conditions under which vanilla stops trusting walls: the F3 culling toggle off, a frozen debug
		// frustum, or the camera inside an opaque block (spectator no-clip). Also off while the camera is so close
		// to an opaque block that the near plane may cut into it and show what is behind (spectators gliding
		// through walls).
		boolean occlusionAllowed = mc.smartCull && camera.getCapturedFrustum() == null
				&& !cameraNearOpaque(level);

		entitiesOn = ENTITIES.on() && occlusionAllowed;
		blockEntityFrustumOn = BLOCK_ENTITIES.on() && frustum != null;
		blockEntityOcclusionOn = BLOCK_ENTITIES.on() && occlusionAllowed;
		particlesOn = PARTICLES.on() && occlusionAllowed && initParticleGraph(levelRenderer, camera.blockPosition());

		if (entitiesOn || blockEntityOcclusionOn) {
			CullWorker.ensureStarted();
			minValidPass = computeMinValidPass();
			if (batch == null) {
				batch = CullWorker.acquire();
			}
			batch.level = level;
			batch.camX = camX;
			batch.camY = camY;
			batch.camZ = camZ;
			batch.createdNanos = System.nanoTime();
		}
	}

	private static boolean cameraNearOpaque(ClientLevel level) {
		for (int i = 0; i < 8; i++) {
			SCRATCH_POS.set(Mth.floor(camX + ((i & 1) == 0 ? -NEAR : NEAR)), Mth.floor(camY + ((i & 2) == 0 ? -NEAR : NEAR)),
					Mth.floor(camZ + ((i & 4) == 0 ? -NEAR : NEAR)));
			if (level.getBlockState(SCRATCH_POS).isSolidRender()) {
				return true;
			}
		}
		return false;
	}

	/** LevelExtractor.extract, RETURN (render thread). */
	public static void endFrame() {
		entitiesCulledLastFrame = entityCulled;
		entitiesTestedLastFrame = entityTested;
		blockEntitiesCulledLastFrame = blockEntityCulled;
		blockEntitiesFrustumCulledLastFrame = blockEntityFrustumCulled;
		blockEntitiesTestedLastFrame = blockEntityTested;
		particlesCulledLastFrame = particleCulled;
		if (batch != null && batch.level != null) {
			if (batch.count > 0) {
				CullWorker.publish(batch);
				batch = null;
			} else {
				batch.clear();
			}
		}
		minecraft = null;
		cameraEntity = null;
		frustum = null;
		viewArea = null;
		graph = null;
		long now = System.nanoTime();
		if (now - statsStart >= 1_000_000_000L) {
			long tests = CullWorker.totalTests;
			long rays = CullWorker.totalRays;
			double seconds = (now - statsStart) / 1.0E9;
			if (statsStart != 0) {
				testsPerSecond = (int) ((tests - statsTests) / seconds);
				raysPerSecond = (int) ((rays - statsRays) / seconds);
			}
			statsStart = now;
			statsTests = tests;
			statsRays = rays;
			lastPassMicros = CullWorker.lastPassMicros;
		}
	}

	/** Oldest pass whose results are still trusted: recent, and made from (almost) the current camera position. */
	private static long computeMinValidPass() {
		long latest = CullWorker.latestPass;
		long now = System.nanoTime();
		long min = Long.MAX_VALUE;
		// Look back at most half the ring, so the slot the worker may be writing right now is never read.
		for (long p = latest; p > 0 && p > latest - CullWorker.RING / 2; p--) {
			int slot = (int) (p & (CullWorker.RING - 1));
			double dx = CullWorker.RING_CAM[slot * 3] - camX;
			double dy = CullWorker.RING_CAM[slot * 3 + 1] - camY;
			double dz = CullWorker.RING_CAM[slot * 3 + 2] - camZ;
			if (now - CullWorker.RING_TIME[slot] > MAX_RESULT_AGE_NANOS
					|| dx * dx + dy * dy + dz * dz > MAX_RESULT_CAMERA_MOVE * MAX_RESULT_CAMERA_MOVE) {
				break;
			}
			min = p;
		}
		return min;
	}

	private static boolean hiddenByLastResult(CullTarget target) {
		long stamp = target.vesuvio$cullStamp();
		return (stamp & 1L) != 0 && (stamp >>> 1) >= minValidPass;
	}

	// ------------------------------------------------------------------ entities

	/**
	 * For an entity vanilla is about to extract (it passed frustum and distance checks): queue its occlusion test and
	 * say whether the last fresh result hides it.
	 */
	public static boolean entityHidden(Entity entity, float partialTicks, EntityRenderDispatcher dispatcher) {
		if (!entitiesOn || batch == null || neverCull(entity)) {
			return false;
		}
		EntityRendererInvoker renderer = (EntityRendererInvoker) dispatcher.getRenderer(entity);
		if (renderer == null || !renderer.vesuvio$affectedByCulling(entity)) {
			return false;
		}
		AABB box = renderer.vesuvio$getBoundingBoxForCulling(entity, partialTicks);
		if (box.hasNaN() || box.getSize() == 0.0) {
			return false;
		}
		if (box.getXsize() > MAX_ENTITY_BOX || box.getYsize() > MAX_ENTITY_BOX || box.getZsize() > MAX_ENTITY_BOX) {
			return false;
		}
		// Vanilla's frustum margin (0.5), plus how far the entity and the camera move in about two frames.
		double motion = Math.abs(entity.getX() - entity.xOld) + Math.abs(entity.getY() - entity.yOld)
				+ Math.abs(entity.getZ() - entity.zOld);
		double m = 0.5 + Math.min(2.0, motion * 2.0) + camSpeedMargin;
		// A name tag drawn above the box (only when not see-through; see-through ones are never culled).
		double top = entity.shouldShowName() || entity.hasCustomName() ? 0.75 : 0.0;
		batch.add((CullTarget) entity, box.minX - m, box.minY - m, box.minZ - m, box.maxX + m, box.maxY + m + top,
				box.maxZ + m);
		entityTested++;
		if (hiddenByLastResult((CullTarget) entity)) {
			entityCulled++;
			return true;
		}
		return false;
	}

	/** Entities whose look reaches past their box or through walls, or that the player is attached to. */
	private static boolean neverCull(Entity entity) {
		Minecraft mc = minecraft;
		if (entity == cameraEntity || entity instanceof LocalPlayer || entity == mc.crosshairPickEntity) {
			return true;
		}
		// Glowing outline (and spectator player outlines) are drawn through walls.
		if (mc.shouldEntityAppearGlowing(entity)) {
			return true;
		}
		// Name tags are drawn see-through unless the entity is sneaking (players always show a name).
		if (entity.shouldShowName() && !entity.isDiscrete()) {
			return true;
		}
		LocalPlayer player = mc.player;
		if (player != null && (entity.hasIndirectPassenger(player) || player.hasIndirectPassenger(entity))) {
			return true;
		}
		// The lead is drawn from the leashed entity to its holder, maybe far outside the box.
		if (entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
			return true;
		}
		// Beams drawn from the entity to a target outside its box; shulker peek animation.
		if (entity instanceof EndCrystal crystal && crystal.getBeamTarget() != null) {
			return true;
		}
		if (entity instanceof Guardian guardian && guardian.hasActiveAttackTarget()) {
			return true;
		}
		if (entity instanceof Shulker) {
			return true;
		}
		// Text displays can be marked see-through.
		if (entity instanceof Display.TextDisplay text) {
			Display.TextDisplay.TextRenderState state = text.textRenderState();
			if (state == null || (state.flags() & Display.TextDisplay.FLAG_SEE_THROUGH) != 0) {
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------ block entities

	/**
	 * For a block entity in a visible section that vanilla is about to extract: true to skip it (outside the frustum,
	 * or hidden by the last fresh occlusion result).
	 */
	public static boolean blockEntityHidden(BlockEntity blockEntity, BlockEntityRenderDispatcher dispatcher) {
		if (!blockEntityFrustumOn) {
			return false;
		}
		BlockEntityRenderer<BlockEntity, ?> renderer = dispatcher.getRenderer(blockEntity);
		if (renderer == null) {
			return false;
		}
		// Beacon beams, end gateways, structure blocks: drawn far outside their block. (Those that say so are drawn
		// from the "globally rendered" list, which is never culled; a long view distance is the other sign.)
		if (renderer.shouldRenderOffScreen() || renderer.getViewDistance() > 64) {
			return false;
		}
		BlockPos pos = blockEntity.getBlockPos();
		double x = pos.getX();
		double y = pos.getY();
		double z = pos.getZ();
		// Vanilla's own distance check comes after us; don't spend tests on what it will drop anyway.
		double vd = renderer.getViewDistance();
		double cx = x + 0.5 - camX;
		double cy = y + 0.5 - camY;
		double cz = z + 0.5 - camZ;
		if (cx * cx + cy * cy + cz * cz >= vd * vd) {
			return false;
		}
		// Box: the block plus room for what vanilla renderers draw outside it (shulker lids, enchanting book,
		// skulls, decorated pot wobble...). Banners hang a block below / stand two blocks high; moving piston
		// blocks are up to a block away. Modded renderers get a wider margin.
		double m = 0.5;
		double down = 0.0;
		double up = 0.0;
		if (blockEntity instanceof BannerBlockEntity) {
			down = 1.0;
			up = 1.5;
		} else if (blockEntity instanceof PistonMovingBlockEntity) {
			m = 1.5;
		} else if (!renderer.getClass().getName().startsWith("net.minecraft.")) {
			m = 1.5;
		}
		double minX = x - m;
		double minY = y - m - down;
		double minZ = z - m;
		double maxX = x + 1 + m;
		double maxY = y + 1 + m + up;
		double maxZ = z + 1 + m;
		int intersection = ((FrustumInvoker) frustum).vesuvio$cubeInFrustum(minX, minY, minZ, maxX, maxY, maxZ);
		if (intersection != -2 && intersection != -1) {
			blockEntityCulled++;
			blockEntityFrustumCulled++;
			return true;
		}
		if (!blockEntityOcclusionOn || batch == null) {
			return false;
		}
		double c = camSpeedMargin;
		batch.add((CullTarget) blockEntity, minX - c, minY - c, minZ - c, maxX + c, maxY + c, maxZ + c);
		blockEntityTested++;
		if (hiddenByLastResult((CullTarget) blockEntity)) {
			blockEntityCulled++;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ particles

	private static boolean initParticleGraph(LevelRenderer levelRenderer, BlockPos cameraPos) {
		ViewArea area = levelRenderer.viewArea();
		SectionOcclusionGraph g = levelRenderer.sectionOcclusionGraph();
		if (area == null || g == null) {
			return false;
		}
		try {
			// Make sure the graph exists (it is built asynchronously after a level loads).
			SectionRenderDispatcher.RenderSection cameraSection = area.getRenderSectionAt(cameraPos);
			if (cameraSection == null || g.getNode(cameraSection) == null) {
				return false;
			}
		} catch (RuntimeException e) {
			return false;
		}
		viewArea = area;
		graph = g;
		return true;
	}

	/**
	 * For a particle inside the view frustum: true if its section is one vanilla's section occlusion graph cannot
	 * reach from the camera (so its terrain is not drawn either).
	 */
	public static boolean particleHidden(double x, double y, double z) {
		if (!particlesOn) {
			return false;
		}
		int sx = SectionPos.blockToSectionCoord(Mth.floor(x));
		int sy = SectionPos.blockToSectionCoord(Mth.floor(y));
		int sz = SectionPos.blockToSectionCoord(Mth.floor(z));
		long key = SectionPos.asLong(sx, sy, sz);
		boolean hidden;
		if (key == lastParticleSection) {
			hidden = lastParticleSectionHidden;
		} else {
			SectionRenderDispatcher.RenderSection section = viewArea.getSections().getValue(sx, sy, sz);
			// Outside the loaded area / build height: keep vanilla's behaviour.
			hidden = section != null && graph.getNode(section) == null;
			lastParticleSection = key;
			lastParticleSectionHidden = hidden;
		}
		if (hidden) {
			particleCulled++;
		}
		return hidden;
	}
}
