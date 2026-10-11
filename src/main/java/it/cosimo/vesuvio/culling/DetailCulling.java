package it.cosimo.vesuvio.culling;

import java.util.List;

import org.joml.Matrix4f;
import org.joml.Vector3fc;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.ui.VesuvioStats;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.blockentity.BeaconRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.PlayerRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.level.block.entity.BeaconBeamOwner;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

/**
 * Small culling bundle (ideas from More Culling by FX - PR0CESS, GPL; Vesuvio's own code). Each part skips only
 * work whose result covers no pixel:
 * <ul>
 * <li><b>Sign text.</b> The text of the side facing away from the camera: text pipelines cull back faces, so the GPU
 * would discard every glyph anyway (and the board stands in front of it). And all text once a whole glyph is
 * smaller than one pixel of the actual window at the current field of view (see {@link #subPixel}).</li>
 * <li><b>Item frames.</b> The whole frame, item or map when the camera is behind the wall it hangs on: every line of
 * sight to the frame goes through the wall's opaque full blocks (checked block by block), whose faces are drawn
 * towards the camera. And the framed item or map alone once it is smaller than one pixel.</li>
 * <li><b>Beacon beams.</b> Vanilla draws every beacon's beam every frame, wherever it is (a "globally rendered" block
 * entity, never frustum culled). A beam whose whole cylinder lies outside the view cone, with room for view bobbing
 * and the hurt tilt, is skipped.</li>
 * </ul>
 */
public final class DetailCulling {
	public static final VesuvioConfig.Toggle SIGNS = VesuvioConfig.toggle("entities", "signTextCulling", true, false);
	public static final VesuvioConfig.Toggle ITEM_FRAMES = VesuvioConfig.toggle("entities", "itemFrameCulling", true, false);
	public static final VesuvioConfig.Toggle BEACONS = VesuvioConfig.toggle("entities", "beaconBeamCulling", true, false);

	// ---- Counters (render thread) ----
	/** Sign sides whose text was skipped last frame. */
	public static int signTextsCulledLastFrame;
	/** Item frames skipped whole (behind their wall) or their item/map skipped (sub-pixel) last frame. */
	public static int itemFramesCulledLastFrame;
	/** Beacon beams skipped last frame. */
	public static int beaconBeamsCulledLastFrame;

	/** The camera must be this far behind a text's plane (blocks; glyph effects sit a hair off it). */
	private static final double SIGN_BACK_MARGIN = 0.02;
	/** Glyphs can be wider than the line is high (wide Unicode glyphs, some fonts): this much margin on the size. */
	private static final double GLYPH_SIZE_FACTOR = 1.5;
	/** Item frame: the box its frame, item and map can occupy, from the wall face (generous for oversized items). */
	private static final double FRAME_HALF_WIDTH = 0.75;
	private static final double FRAME_DEPTH = 1.0;
	/** The camera must be at least this far behind the wall face (camera near plane plus margin). */
	private static final double FRAME_CAMERA_BEHIND = 0.25;
	/** Most wall blocks checked for one frame. */
	private static final int FRAME_MAX_CELLS = 25;
	/** Framed item or map: largest size on screen (a map is a block, a 3D item a bit more) and reach from its pose. */
	private static final double FRAME_ITEM_SIZE = 1.5;
	private static final double FRAME_ITEM_REACH = 1.0;
	/** Beam view cone margin: hurt tilt (up to 14 degrees) plus view bobbing (up to 5). */
	private static final double BEAM_MARGIN_DEGREES = 20.0;
	/** The eye moves up to about a block with view bobbing. */
	private static final double BEAM_EYE_REACH = 1.0;

	private static final BlockPos.MutableBlockPos SCRATCH = new BlockPos.MutableBlockPos();

	private static int signCount;
	private static int frameCount;
	private static int beamCount;
	private static int submitFrameCount;

	private DetailCulling() {
	}

	/** Called from CullingBoost.init: makes the settings and registers the counters. */
	public static void init() {
		VesuvioStats.register("sign texts culled", () -> signTextsCulledLastFrame);
		VesuvioStats.register("item frames culled", () -> itemFramesCulledLastFrame);
		VesuvioStats.register("beacon beams culled", () -> beaconBeamsCulledLastFrame);
	}

	/** LevelExtractor.extract, head. */
	public static void beginExtract() {
		frameCount = 0;
		beamCount = 0;
	}

	/** LevelExtractor.extract, return. */
	public static void endExtract() {
		beaconBeamsCulledLastFrame = beamCount;
		itemFramesCulledLastFrame = frameCount + submitFrameCount;
	}

	/** LevelRenderer.submitFeatures, head (block entities and entities are submitted inside it). */
	public static void beginSubmit() {
		signCount = 0;
		submitFrameCount = 0;
	}

	/** LevelRenderer.submitFeatures, return. */
	public static void endSubmit() {
		signTextsCulledLastFrame = signCount;
	}

	// ------------------------------------------------------------------ screen size

	/**
	 * True if something no bigger than {@code size} blocks, whose every point is at least {@code distance} blocks from
	 * the eye, is smaller than one pixel of the window at this frame's field of view. A small object at distance d seen
	 * at an angle covers at most size / d radians in any screen direction (off-axis perspective stretches by 1/cos,
	 * exactly what using the distance instead of the depth accounts for), and a radian is height/2 * m11 pixels.
	 */
	static boolean subPixel(double size, double distance, CameraRenderState camera) {
		if (!(distance > 1.0)) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		int height = mc.gameRenderer.mainRenderTarget().height;
		double m11 = camera.projectionMatrix.m11();
		if (!(height > 0) || !(m11 > 0.0)) {
			return false;
		}
		return 0.5 * height * m11 * size < distance;
	}

	// ------------------------------------------------------------------ signs

	/**
	 * AbstractSignRenderer.submit, for one side's text about to be submitted with {@code poseStack} (camera-relative,
	 * the side's text transformation applied): true to skip it.
	 */
	public static boolean signTextHidden(SignRenderState state, Matrix4f pose, CameraRenderState camera) {
		if (!SIGNS.on()) {
			return false;
		}
		// Text plane: the pose's x and y axes through its origin; the camera is at the origin. The text reads the
		// right way round from the side opposite to (x axis) cross (y axis), whatever the transformation's handedness.
		double px = pose.m30();
		double py = pose.m31();
		double pz = pose.m32();
		double ax = pose.m00();
		double ay = pose.m01();
		double az = pose.m02();
		double bx = pose.m10();
		double by = pose.m11();
		double bz = pose.m12();
		double nx = ay * bz - az * by;
		double ny = az * bx - ax * bz;
		double nz = ax * by - ay * bx;
		double nLength = Math.sqrt(nx * nx + ny * ny + nz * nz);
		if (!(nLength > 0.0)) {
			return false;
		}
		// (camera - origin) . n = -(p . n): positive means the camera is on the back side.
		if (-(px * nx + py * ny + pz * nz) > SIGN_BACK_MARGIN * nLength) {
			signCount++;
			return true;
		}
		double scale = Math.sqrt(bx * bx + by * by + bz * bz);
		double lineHeight = Math.max(state.textLineHeight, 9);
		double halfWidth = Math.max(state.maxTextLineWidth, 90) * 0.5;
		double reach = Math.sqrt(halfWidth * halfWidth + 4.0 * lineHeight * lineHeight) * scale;
		double distance = Math.sqrt(px * px + py * py + pz * pz) - reach;
		if (subPixel(lineHeight * scale * GLYPH_SIZE_FACTOR, distance, camera)) {
			signCount++;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ item frames

	/**
	 * LevelExtractor.extractVisibleEntities, for an item frame vanilla is about to extract: true when the camera is
	 * behind the wall it hangs on and every line of sight to it crosses the wall through opaque full blocks.
	 */
	public static boolean itemFrameHidden(ItemFrame frame, double camX, double camY, double camZ, ClientLevel level) {
		if (!ITEM_FRAMES.on() || level == null) {
			return false;
		}
		Minecraft mc = Minecraft.getInstance();
		if (frame == mc.crosshairPickEntity || mc.shouldEntityAppearGlowing(frame)
				|| frame.shouldShowName() && !frame.isDiscrete()) {
			return false;
		}
		Direction facing = frame.getDirection();
		BlockPos pos = frame.getPos();
		// Axes: n (out of the wall), u and v (along it). Coordinates relative to the centre of the wall face.
		Direction.Axis axis = facing.getAxis();
		int step = facing.getAxisDirection().getStep();
		double faceX = pos.getX() + 0.5 - facing.getStepX() * 0.5;
		double faceY = pos.getY() + 0.5 - facing.getStepY() * 0.5;
		double faceZ = pos.getZ() + 0.5 - facing.getStepZ() * 0.5;
		double rx = camX - faceX;
		double ry = camY - faceY;
		double rz = camZ - faceZ;
		double cn = step * pick(axis, rx, ry, rz);
		if (!(cn < -FRAME_CAMERA_BEHIND)) {
			return false;
		}
		double cu = pickU(axis, rx, ry, rz);
		double cv = pickV(axis, rx, ry, rz);
		// The camera must not be inside an opaque block itself (spectators in walls see through them).
		SCRATCH.set(Mth.floor(camX), Mth.floor(camY), Mth.floor(camZ));
		if (level.getBlockState(SCRATCH).isSolidRender()) {
			return false;
		}
		// Project the frame's box corners onto the wall plane from the camera: the lines of sight cross the plane
		// inside the convex hull of those points, so inside their bounding rectangle.
		double minU = Double.POSITIVE_INFINITY;
		double maxU = Double.NEGATIVE_INFINITY;
		double minV = Double.POSITIVE_INFINITY;
		double maxV = Double.NEGATIVE_INFINITY;
		for (int i = 0; i < 8; i++) {
			double pu = (i & 1) == 0 ? -FRAME_HALF_WIDTH : FRAME_HALF_WIDTH;
			double pv = (i & 2) == 0 ? -FRAME_HALF_WIDTH : FRAME_HALF_WIDTH;
			double pn = (i & 4) == 0 ? 0.0 : FRAME_DEPTH;
			double t = pn / (pn - cn);
			double qu = pu + (cu - pu) * t;
			double qv = pv + (cv - pv) * t;
			minU = Math.min(minU, qu);
			maxU = Math.max(maxU, qu);
			minV = Math.min(minV, qv);
			maxV = Math.max(maxV, qv);
		}
		// Wall cells (the layer behind the face; cell 0 is the frame's support block) covering the rectangle.
		int iMin = Mth.floor(minU + 0.5);
		int iMax = Mth.floor(maxU + 0.5);
		int jMin = Mth.floor(minV + 0.5);
		int jMax = Mth.floor(maxV + 0.5);
		if ((long) (iMax - iMin + 1) * (jMax - jMin + 1) > FRAME_MAX_CELLS) {
			return false;
		}
		BlockPos support = pos.relative(facing.getOpposite());
		for (int i = iMin; i <= iMax; i++) {
			for (int j = jMin; j <= jMax; j++) {
				setCell(support, axis, i, j);
				if (!level.getBlockState(SCRATCH).isSolidRender()) {
					return false;
				}
			}
		}
		frameCount++;
		return true;
	}

	private static double pick(Direction.Axis axis, double x, double y, double z) {
		return axis == Direction.Axis.X ? x : axis == Direction.Axis.Y ? y : z;
	}

	/** First axis along the wall: Z for an X wall, X otherwise. */
	private static double pickU(Direction.Axis axis, double x, double y, double z) {
		return axis == Direction.Axis.X ? z : x;
	}

	/** Second axis along the wall: Z for a floor or ceiling, Y otherwise. */
	private static double pickV(Direction.Axis axis, double x, double y, double z) {
		return axis == Direction.Axis.Y ? z : y;
	}

	private static void setCell(BlockPos support, Direction.Axis axis, int u, int v) {
		switch (axis) {
			case X -> SCRATCH.set(support.getX(), support.getY() + v, support.getZ() + u);
			case Y -> SCRATCH.set(support.getX() + u, support.getY(), support.getZ() + v);
			default -> SCRATCH.set(support.getX() + u, support.getY() + v, support.getZ());
		}
	}

	/**
	 * ItemFrameRenderer.submit, for the framed item or map about to be submitted with {@code pose} (camera-relative):
	 * true to skip it because it is smaller than a pixel.
	 */
	public static boolean framedItemHidden(Matrix4f pose, int outlineColor, CameraRenderState camera) {
		// A glowing frame's outline is drawn a few pixels wide around whatever pixel the item covers.
		if (!ITEM_FRAMES.on() || outlineColor != 0) {
			return false;
		}
		double px = pose.m30();
		double py = pose.m31();
		double pz = pose.m32();
		double distance = Math.sqrt(px * px + py * py + pz * pz) - FRAME_ITEM_REACH;
		if (subPixel(FRAME_ITEM_SIZE, distance, camera)) {
			submitFrameCount++;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------ beacon beams

	/**
	 * LevelExtractor.extractVisibleBlockEntities, globally rendered list: true to skip a vanilla beacon whose beam is
	 * wholly outside the view.
	 */
	public static boolean beaconHidden(BlockEntity blockEntity, BlockEntityRenderDispatcher dispatcher,
			CameraRenderState cameraState, PlayerRenderState player, Vector3fc forward) {
		if (!BEACONS.on() || !(blockEntity instanceof BeaconBlockEntity)
				|| dispatcher.getRenderer(blockEntity) == null
				|| dispatcher.getRenderer(blockEntity).getClass() != BeaconRenderer.class) {
			return false;
		}
		// The nausea and portal effects warp the projection; the panorama camera has its own.
		if (cameraState.isPanoramicMode || player.portalEffectIntensity > 0.0F || player.nauseaEffectIntensity > 0.0F) {
			return false;
		}
		List<BeaconBeamOwner.Section> sections = ((BeaconBeamOwner) blockEntity).getBeamSections();
		BlockPos pos = blockEntity.getBlockPos();
		Vec3 cam = cameraState.pos;
		double dx = pos.getX() + 0.5 - cam.x;
		double dz = pos.getZ() + 0.5 - cam.z;
		double h = Math.sqrt(dx * dx + dz * dz);
		// BeaconRenderer: beam radius 0.25, times distance / 96 when far (larger than with a spyglass).
		double radius = 0.25 * Math.max(1.0, h / 96.0) + BEAM_EYE_REACH;
		if (!(h > radius + 1.0)) {
			return false;
		}
		// Height: every section but the last at its own height, the last to 2048 above its start.
		int top = 0;
		for (int i = 0; i < sections.size() - 1; i++) {
			top += Math.max(0, sections.get(i).getHeight());
		}
		top += 2048;
		double s0 = pos.getY() - cam.y - BEAM_EYE_REACH;
		double s1 = pos.getY() + top - cam.y + BEAM_EYE_REACH;

		// Smallest angle between the view direction and the beam's axis: maximise
		// cos(s) = (k + fy s) / sqrt(h^2 + s^2) over the axis heights s, k = forward . (dx, 0, dz).
		double fx = forward.x();
		double fy = forward.y();
		double fz = forward.z();
		double k = fx * dx + fz * dz;
		double best = Math.max(cosToAxis(k, fy, h, s0), cosToAxis(k, fy, h, s1));
		if (k > 0.0) {
			double critical = fy * h * h / k;
			if (critical > s0 && critical < s1) {
				best = Math.max(best, cosToAxis(k, fy, h, critical));
			}
		}
		// View cone: the corner of the screen (half-diagonal angle), plus the margin, plus the beam's own width.
		double tanY = 1.0 / cameraState.projectionMatrix.m11();
		double tanX = 1.0 / cameraState.projectionMatrix.m00();
		if (!(tanX > 0.0) || !(tanY > 0.0)) {
			return false;
		}
		double halfDiagonal = Math.toDegrees(Math.atan(Math.sqrt(tanX * tanX + tanY * tanY)));
		double width = Math.toDegrees(Math.asin(Math.min(1.0, radius / h)));
		double limit = halfDiagonal + BEAM_MARGIN_DEGREES + width;
		if (limit >= 180.0) {
			return false;
		}
		if (best < Math.cos(Math.toRadians(limit))) {
			beamCount++;
			return true;
		}
		return false;
	}

	private static double cosToAxis(double k, double fy, double h, double s) {
		return (k + fy * s) / Math.sqrt(h * h + s * s);
	}
}
