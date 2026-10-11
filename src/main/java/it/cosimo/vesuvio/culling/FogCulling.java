package it.cosimo.vesuvio.culling;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.culling.mixin.CullEntityRendererInvoker;
import it.cosimo.vesuvio.culling.mixin.FogRendererAccessor;
import it.cosimo.vesuvio.culling.mixin.ParticleAccessor;
import it.cosimo.vesuvio.ui.VesuvioStats;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.fog.FogData;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.client.renderer.state.level.SkyRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.util.Mth;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.EnderDragonPart;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Guardian;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Fog culling: terrain sections, entities, block entities and particles that the fog already paints over completely
 * are not drawn at all (an idea Sodium made known; Vesuvio's own code). Biggest under water, in lava or powder snow,
 * with Blindness or Darkness, in the Nether and in a boss's world fog; nothing in clear overworld air, where the fog
 * ends past the render distance.
 *
 * <p>Why the picture stays the same. Every world shader mixes its colour towards the fog colour by
 * {@code linear_fog_value(distance, FogEnvironmentalStart, FogEnvironmentalEnd)}, which is exactly 1 from the fog
 * end on (fog.glsl); terrain then writes exactly {@code FogColor}, entities, particles, text and lines write
 * {@code mix(colour, FogColor, 1 * FogColor.a)} with an alpha of 1, glints and lightning fade to nothing. The distance
 * is per vertex, interpolated, and the distance to a box's nearest point is a lower bound for all of them, so a box
 * whose nearest point is past the fog end only ever produces fog-coloured pixels. Taking it away shows what is behind
 * it, which must be fog-coloured too:
 * <ul>
 * <li>anything farther away that the world shaders draw is past the fog end as well (fog colour, blended or not);</li>
 * <li>the background: the main pass clears to the same fog colour. Where vanilla draws a sky (open air, water, the
 * End) only the directions below the sky occluder's fully opaque edge are fog colour (under water: 20 degrees above
 * the horizon and lower), so there a box must lie wholly below it (with a margin for view bobbing); where no sky is
 * drawn (lava, powder snow, Blindness, Darkness, boss fog, the Nether) the whole background is fog colour;</li>
 * <li>clouds farther away are transparent once past their own fog end, so with clouds drawn the cut-off is the larger
 * of the two;</li>
 * <li>things drawn without fog would show through: the world border (no culling while it is drawn), debug overlays
 * (no culling while there are any), glowing outlines and see-through name tags (those entities are never culled, and
 * both are drawn on top anyway).</li>
 * </ul>
 * Off with Improved Transparency (its fog blends into a sky texture, and its order-independent blending is not a plain
 * "fog colour over fog colour"), with the F3 fog toggle off, when the fog colour is not opaque, and while Etna Shaders
 * draws the world (its terrain fog is not vanilla's linear one). Terrain is cut in vanilla's main-view call only:
 * other prepares (a shader mod's shadow pass) see every section. The fog values are this frame's, the ones uploaded
 * to the shaders.
 */
public final class FogCulling {
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("rendering", "fogCulling", true, false);

	// ---- Counters (render thread writes, settings screen / F3 / benchmark read) ----
	/** Terrain sections left out of the main view last frame. */
	public static int sectionsCulledLastFrame;
	/** Entities and block entities not extracted last frame. */
	public static int objectsCulledLastFrame;
	/** Particles not extracted last frame. */
	public static int particlesCulledLastFrame;
	/** Fog end the culling used last frame, in blocks (0 = not culling). */
	public static int distanceLastFrame;

	/** How far a section's mesh can reach outside its 16x16x16 box (model elements reach one block out). */
	private static final double SECTION_REACH = 1.0;
	/** Added to the fog end: float rounding in the shaders, nothing visible. */
	private static final double DISTANCE_MARGIN = 0.5;
	/** View bobbing moves the eye by up to about a block, which tilts lines of sight a few degrees. */
	private static final double ELEVATION_MARGIN_DEGREES = 4.0;
	/** Room around an entity's culling box: held items, capes, flames, a non-see-through name tag. */
	private static final double ENTITY_REACH = 2.0;
	/** Never cull an entity box bigger than this on any axis (huge custom models, broken boxes). */
	private static final double MAX_ENTITY_BOX = 32.0;
	/** Beyond this the fog is not worth testing against (vanilla's clear-air fog end is 1024). */
	private static final double MAX_USEFUL_DISTANCE = 1000.0;

	private static final ClassValue<Boolean> VANILLA_CLASS = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return type.getName().startsWith("net.minecraft.");
		}
	};

	/** One view's culling state: the fog cut-off and which directions have a fog-coloured background. */
	private static final class View {
		boolean active;
		double camX;
		double camY;
		double camZ;
		/** Squared distance from the camera beyond which a box is fully fogged. */
		double cutoffSq;
		/** Highest tangent of elevation whose background is fog colour (+infinity: every direction). */
		double maxTan;
	}

	private static final View EXTRACT = new View();
	private static final View MAIN = new View();
	private static boolean inMainView;
	/** The main view's last frame had standard debug gizmos (lines and boxes drawn without fog). */
	private static boolean gizmosLastFrame;
	private static boolean gizmosThisFrame;

	private static int sectionCount;
	private static int objectCount;
	private static int particleCount;

	private FogCulling() {
	}

	/** Called from CullingBoost.init: makes the setting and registers the counters. */
	public static void init() {
		VesuvioStats.register("fog culled sections", () -> sectionsCulledLastFrame);
		VesuvioStats.register("fog culled entities", () -> objectsCulledLastFrame);
		VesuvioStats.register("fog culled particles", () -> particlesCulledLastFrame);
	}

	// ------------------------------------------------------------------ setup

	/**
	 * Works out the cut-off for one view. {@code skybox}, {@code occluder*} describe the sky vanilla will draw this
	 * frame; {@code cloudsDrawn} whether it draws clouds; {@code borderDrawn} the world border.
	 */
	private static void setup(View view, CameraRenderState camera, boolean improvedTransparency, boolean cloudsDrawn,
			DimensionType.Skybox skybox, boolean hasOccluder, float occluderStart, float occluderEnd,
			boolean borderDrawn, boolean gizmos) {
		view.active = false;
		if (!SETTING.on() || camera == null || !camera.initialized || improvedTransparency || borderDrawn || gizmos
				|| !FogRendererAccessor.vesuvio$fogEnabled() || Etna.active()) {
			return;
		}
		FogData fog = camera.fogData;
		if (fog == null || fog.color == null || fog.color.w != 1.0F) {
			return;
		}
		double cutoff = Math.max(fog.environmentalStart, fog.environmentalEnd);
		if (cloudsDrawn) {
			cutoff = Math.max(cutoff, fog.cloudEnd);
		}
		// NaN fails this too.
		if (!(cutoff < MAX_USEFUL_DISTANCE)) {
			return;
		}
		cutoff = Math.max(0.0, cutoff) + DISTANCE_MARGIN;

		// LevelRenderer.shouldRenderSky, then SkyRenderer.renderSkyOccluder: smoothstep(start, end, elevation) is
		// exactly 1 (fog colour, opaque, drawn over the sun, moon and stars) at or below "end" when start > end.
		FogType fogType = camera.fogType;
		boolean skyDrawn = !fog.shouldCreateBossFog && fogType != FogType.POWDER_SNOW && fogType != FogType.LAVA
				&& !camera.entityRenderState.doesMobEffectBlockSky && skybox != DimensionType.Skybox.NONE;
		double maxTan;
		if (!skyDrawn) {
			maxTan = Double.POSITIVE_INFINITY;
		} else if (skybox == DimensionType.Skybox.OVERWORLD && hasOccluder && occluderStart > occluderEnd) {
			maxTan = Math.tan(Math.toRadians(occluderEnd - ELEVATION_MARGIN_DEGREES));
		} else {
			return;
		}
		Vec3 pos = camera.pos;
		view.camX = pos.x;
		view.camY = pos.y;
		view.camZ = pos.z;
		view.cutoffSq = cutoff * cutoff;
		view.maxTan = maxTan;
		view.active = true;
	}

	/**
	 * LevelExtractor.extract, head: entities, block entities and particles. The camera, its fog and its effects are
	 * already this frame's (GameRenderer.extractCamera runs first); the sky and the border are extracted later in the
	 * same call, so they are worked out here the way SkyRenderer and WorldBorderRenderer will.
	 */
	public static void beginExtract(Minecraft minecraft, ClientLevel level, LevelRenderState state, float partialTicks) {
		objectCount = 0;
		particleCount = 0;
		EXTRACT.active = false;
		if (!SETTING.on() || level == null || state == null) {
			return;
		}
		try {
			CameraRenderState camera = state.cameraRenderState;
			int renderDistance = minecraft.options.getEffectiveRenderDistance() * 16;
			double toBorder = level.getWorldBorder().getDistanceToBorder(camera.pos.x, camera.pos.z);
			boolean borderDrawn = Math.abs(toBorder) < renderDistance + 16;
			DimensionType.Skybox skybox = level.dimensionType().skybox();
			boolean hasOccluder = false;
			float start = 0.0F;
			float end = 0.0F;
			if (skybox == DimensionType.Skybox.OVERWORLD) {
				hasOccluder = minecraft.gameRenderer.mainCamera().attributeProbe()
						.getValue(EnvironmentAttributes.HAS_SKY_OCCLUDER, partialTicks);
				LocalPlayer player = minecraft.player;
				if (player != null && player.getEyePosition(partialTicks).y < level.getMinY()) {
					start = 15.0F;
					end = 10.0F;
				} else if (camera.fogType == FogType.WATER) {
					start = 40.0F;
					end = 20.0F;
				} else {
					start = -1.0F;
					end = -8.0F;
				}
			}
			int cloudColor = ARGB.colorFromVector4f(minecraft.gameRenderer.mainCamera().attributeProbe()
					.getValue(EnvironmentAttributes.CLOUD_COLOR, partialTicks));
			boolean clouds = minecraft.options.getCloudStatus() != CloudStatus.OFF && ARGB.alpha(cloudColor) > 0;
			setup(EXTRACT, camera, minecraft.gameRenderer.useImprovedTransparency() || minecraft.wireframe, clouds,
					skybox, hasOccluder, start, end, borderDrawn, gizmosLastFrame);
		} catch (RuntimeException e) {
			EXTRACT.active = false;
			Vesuvio.LOGGER.debug("Vesuvio: fog culling off this frame", e);
		}
	}

	/** LevelExtractor.extract, return. */
	public static void endExtract() {
		objectsCulledLastFrame = objectCount;
		particlesCulledLastFrame = particleCount;
		EXTRACT.active = false;
	}

	/** LevelRenderer.submitFeatures: whether this frame draws standard (depth-tested, unfogged) debug gizmos. */
	public static void noteGizmos(boolean present) {
		gizmosThisFrame = present;
	}

	/** Around vanilla's main-view prepareChunkRenders(Indirect) call in LevelRenderer.render. */
	public static void beginMainView(LevelRenderState state, boolean improvedTransparency, CloudStatus cloudStatus) {
		sectionCount = 0;
		inMainView = true;
		gizmosLastFrame = gizmosThisFrame;
		try {
			SkyRenderState sky = state.skyRenderState;
			boolean clouds = cloudStatus != CloudStatus.OFF && ARGB.alpha(state.cloudColor) > 0;
			setup(MAIN, state.cameraRenderState, improvedTransparency || state.renderWireframeTerrain, clouds,
					sky.skybox, sky.hasSkyOccluder, sky.occluderStartAngle, sky.occluderEndAngle,
					state.worldBorderRenderState.alpha > 0.0, gizmosThisFrame);
		} catch (RuntimeException e) {
			MAIN.active = false;
		}
		distanceLastFrame = MAIN.active ? (int) Math.sqrt(MAIN.cutoffSq) : 0;
	}

	public static void endMainView() {
		sectionsCulledLastFrame = sectionCount;
		inMainView = false;
		MAIN.active = false;
	}

	// ------------------------------------------------------------------ tests

	/** True if every point of the box (world coordinates) is fully fogged and has a fog-coloured background. */
	private static boolean hidden(View view, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
		double x0 = minX - view.camX;
		double y0 = minY - view.camY;
		double z0 = minZ - view.camZ;
		double x1 = maxX - view.camX;
		double y1 = maxY - view.camY;
		double z1 = maxZ - view.camZ;
		double dx = x0 > 0.0 ? x0 : x1 < 0.0 ? -x1 : 0.0;
		double dy = y0 > 0.0 ? y0 : y1 < 0.0 ? -y1 : 0.0;
		double dz = z0 > 0.0 ? z0 : z1 < 0.0 ? -z1 : 0.0;
		if (!(dx * dx + dy * dy + dz * dz > view.cutoffSq)) {
			return false;
		}
		if (view.maxTan == Double.POSITIVE_INFINITY) {
			return true;
		}
		// Highest elevation seen from the camera: the top face, at its nearest horizontal point when above the eye,
		// at its farthest when below it.
		if (y1 > 0.0) {
			double nearest = Math.sqrt(dx * dx + dz * dz);
			return nearest > 0.0 && y1 <= view.maxTan * nearest;
		}
		double farthest = Math.sqrt(Math.max(x0 * x0, x1 * x1) + Math.max(z0 * z0, z1 * z1));
		return y1 <= view.maxTan * farthest;
	}

	/** LevelRenderer.extractSectionDrawGroups: true to leave this section out of the main view. */
	public static boolean sectionHidden(SectionRenderDispatcher.RenderSection section) {
		if (!inMainView || !MAIN.active) {
			return false;
		}
		BlockPos origin = section.getRenderOrigin();
		double x = origin.getX();
		double y = origin.getY();
		double z = origin.getZ();
		if (hidden(MAIN, x - SECTION_REACH, y - SECTION_REACH, z - SECTION_REACH, x + 16 + SECTION_REACH,
				y + 16 + SECTION_REACH, z + 16 + SECTION_REACH)) {
			sectionCount++;
			return true;
		}
		return false;
	}

	/** LevelExtractor.extractVisibleEntities, for an entity vanilla is about to extract: true to skip it. */
	public static boolean entityHidden(Entity entity, float partialTicks, EntityRenderDispatcher dispatcher) {
		if (!EXTRACT.active || neverCull(entity)) {
			return false;
		}
		CullEntityRendererInvoker renderer = (CullEntityRendererInvoker) dispatcher.getRenderer(entity);
		// Only vanilla renderers: their every layer goes through the fogged entity shaders.
		if (renderer == null || !VANILLA_CLASS.get(renderer.getClass()) || !renderer.vesuvio$affectedByCulling(entity)) {
			return false;
		}
		AABB box = renderer.vesuvio$getBoundingBoxForCulling(entity, partialTicks);
		if (box.hasNaN() || box.getXsize() > MAX_ENTITY_BOX || box.getYsize() > MAX_ENTITY_BOX
				|| box.getZsize() > MAX_ENTITY_BOX) {
			return false;
		}
		// The shadow reaches about the entity's width out and down (radius scales with the entity).
		double reach = ENTITY_REACH + Math.max(box.getXsize(), box.getZsize());
		if (hidden(EXTRACT, box.minX - reach, box.minY - reach, box.minZ - reach, box.maxX + reach,
				box.maxY + ENTITY_REACH, box.maxZ + reach)) {
			objectCount++;
			return true;
		}
		return false;
	}

	/**
	 * Entities drawn without fog somewhere (outline, see-through name), drawn outside their box (leads, beams, the
	 * fishing line, display transforms, the dragon), or with an additive layer (the charged creeper's and the wither's
	 * energy swirl add the fog colour on top of the background instead of replacing it).
	 */
	private static boolean neverCull(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (entity instanceof LocalPlayer || entity == mc.getCameraEntity() || entity == mc.crosshairPickEntity
				|| mc.shouldEntityAppearGlowing(entity) || entity.shouldShowName() && !entity.isDiscrete()) {
			return true;
		}
		LocalPlayer player = mc.player;
		if (player != null && (entity.hasIndirectPassenger(player) || player.hasIndirectPassenger(entity))) {
			return true;
		}
		if (entity instanceof Leashable leashable && leashable.getLeashHolder() != null) {
			return true;
		}
		return entity instanceof Display || entity instanceof FishingHook || entity instanceof LightningBolt
				|| entity instanceof EnderDragon || entity instanceof EnderDragonPart
				|| entity instanceof EndCrystal crystal && crystal.getBeamTarget() != null
				|| entity instanceof Guardian guardian && guardian.hasActiveAttackTarget()
				|| entity instanceof Creeper creeper && creeper.isPowered()
				|| entity instanceof WitherBoss wither && wither.isPowered();
	}

	/** LevelExtractor.extractVisibleBlockEntities, per-section loop: true to skip this block entity. */
	public static boolean blockEntityHidden(BlockEntity blockEntity, BlockEntityRenderDispatcher dispatcher) {
		if (!EXTRACT.active) {
			return false;
		}
		BlockEntityRenderer<BlockEntity, ?> renderer = dispatcher.getRenderer(blockEntity);
		if (renderer == null || !VANILLA_CLASS.get(renderer.getClass()) || renderer.shouldRenderOffScreen()
				|| renderer.getViewDistance() > 64) {
			return false;
		}
		// The block plus everything vanilla renderers draw around it (banners hang a block below and stand two high,
		// moving pistons are a block away, the spawner's mob, the enchanting book).
		BlockPos pos = blockEntity.getBlockPos();
		if (hidden(EXTRACT, pos.getX() - 1.5, pos.getY() - 2.0, pos.getZ() - 1.5, pos.getX() + 2.5, pos.getY() + 3.5,
				pos.getZ() + 2.5)) {
			objectCount++;
			return true;
		}
		return false;
	}

	/** QuadParticleGroup.extractRenderState, for a particle vanilla is about to extract: true to skip it. */
	public static boolean particleHidden(SingleQuadParticle particle, double x, double y, double z, float partialTicks) {
		if (!EXTRACT.active || !VANILLA_CLASS.get(particle.getClass())) {
			return false;
		}
		ParticleAccessor old = (ParticleAccessor) particle;
		// Drawn at the interpolated position, a camera-facing quad reaching quadSize from it (corners: times sqrt 2).
		double cx = Mth.lerp(partialTicks, old.vesuvio$xo(), x);
		double cy = Mth.lerp(partialTicks, old.vesuvio$yo(), y);
		double cz = Mth.lerp(partialTicks, old.vesuvio$zo(), z);
		double r = Math.abs(particle.getQuadSize(partialTicks)) * 1.5 + 0.5;
		if (!(r < 16.0)) {
			return false;
		}
		if (hidden(EXTRACT, cx - r, cy - r, cz - r, cx + r, cy + r, cz + r)) {
			particleCount++;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ Etna

	/** Whether Etna Shaders is installed and drawing (its terrain fog is its own curve, not vanilla's). */
	static final class Etna {
		private static final boolean LOADED = FabricLoader.getInstance().isModLoaded("etna");
		private static final MethodHandle ENABLED = LOADED ? find() : null;

		private Etna() {
		}

		private static MethodHandle find() {
			try {
				Class<?> config = Class.forName("it.cosimo.etna.EtnaConfig", false, FogCulling.class.getClassLoader());
				return MethodHandles.publicLookup().findStaticGetter(config, "enabled", boolean.class);
			} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
				Vesuvio.LOGGER.info("Vesuvio: Etna Shaders found but its on/off switch is not readable; fog culling stays off");
				return null;
			}
		}

		static boolean active() {
			if (!LOADED) {
				return false;
			}
			if (ENABLED == null) {
				return true;
			}
			try {
				return (boolean) ENABLED.invokeExact();
			} catch (Throwable t) {
				return true;
			}
		}
	}
}
