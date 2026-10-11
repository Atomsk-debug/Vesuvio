package it.cosimo.vesuvio.models;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Entity model fast path (idea from Sodium's entity renderer; Vesuvio's own code). Vanilla's
 * {@code ModelPart.Cube.compile} transforms every vertex of every face with the pose matrix: 24 transforms for a
 * box, although a box has only 8 corners, each shared by 3 faces. Here each distinct corner is transformed once and
 * the 24 vertices are written from those.
 *
 * <p>The bytes written are the same as vanilla's, bit for bit:
 * <ul>
 * <li>Each corner goes through the same {@code Matrix4f.transformPosition(x, y, z, dest)} call vanilla makes for each
 * vertex, with the same matrix and the same inputs ({@code Vertex.worldX()} etc.). Java float arithmetic is exact
 * and deterministic (strict IEEE since Java 17), so a corner transformed once gives the very floats vanilla gets each
 * time it transforms that corner again. Corners are told apart by the bits of their inputs, never by "close
 * enough".</li>
 * <li>Normals are transformed exactly as vanilla does, once per face, with {@code PoseStack.Pose.transformNormal}
 * (normal matrix, normalised only when the pose is not trusted; mirrored and negative-scale parts are handled by the
 * pose itself, as in vanilla: compile flips nothing).</li>
 * <li>The vertices go out in vanilla's order through the same 11-argument {@code VertexConsumer.addVertex}, so
 * BufferBuilder keeps its own fast path and every wrapping consumer (decals, outlines, glint) sees the same calls.</li>
 * </ul>
 * The corner layout of a cube is worked out once and kept on the cube; before each use it is checked against the
 * cube's current faces and vertices (by identity), so a mod that swaps them gets a fresh layout, never a stale one.
 */
public final class ModelFastPath {
	public static final VesuvioConfig.Toggle FAST_MODELS = VesuvioConfig.toggle("entities", "fastEntityModels", true, false);

	/** Cubes written by the fast path since the last frame ended, and last frame (counter). */
	static int cubes;
	public static int cubesLastFrame;

	/**
	 * Corner positions transformed this call (3 floats each). Models are drawn on the render thread, which uses
	 * {@link #renderScratch} without a thread-local lookup per cube; any other thread gets its own array.
	 */
	private static final ThreadLocal<float[]> SCRATCH = ThreadLocal.withInitial(() -> new float[24]);
	private static float[] renderScratch = new float[24];

	private ModelFastPath() {
	}

	/** End of a frame (command encoder submit): publishes the counter. */
	public static void endFrame() {
		cubesLastFrame = cubes;
		cubes = 0;
	}

	/**
	 * How one cube's vertices map onto its distinct corners. Immutable (final fields: safe to publish through a plain
	 * field). Built from the cube's faces as they are; {@link #matches} tells whether they still are.
	 */
	public static final class Layout {
		/** The faces as they were (the cube's array elements, in order). */
		final ModelPart.Polygon[] polygons;
		/** Every vertex of every face, in vanilla's emission order. */
		final ModelPart.Vertex[] vertices;
		/** Index into {@link #vertices} where each face's vertices end. */
		final int[] faceEnds;
		/** Distinct corners: worldX, worldY, worldZ for each. */
		final float[] corners;
		/** For each vertex, its corner times 3 (offset into {@link #corners} and the transformed scratch). */
		final int[] cornerOffset;
		/** For each vertex, its u and v (copied: Vertex is an immutable record, and {@link #matches} checks identity). */
		final float[] uvs;
		/** False if no two vertices share a corner (nothing to save): vanilla's own code runs. */
		final boolean worthIt;

		private Layout(ModelPart.Polygon[] polygons, ModelPart.Vertex[] vertices, int[] faceEnds, float[] corners,
				int[] cornerOffset, float[] uvs, boolean worthIt) {
			this.polygons = polygons;
			this.vertices = vertices;
			this.faceEnds = faceEnds;
			this.corners = corners;
			this.cornerOffset = cornerOffset;
			this.uvs = uvs;
			this.worthIt = worthIt;
		}

		/** The layout of these faces as they are now. */
		public static Layout of(ModelPart.Polygon[] faces) {
			ModelPart.Polygon[] polygons = faces.clone();
			int total = 0;
			for (ModelPart.Polygon polygon : polygons) {
				total += polygon.vertices().length;
			}
			ModelPart.Vertex[] vertices = new ModelPart.Vertex[total];
			int[] faceEnds = new int[polygons.length];
			int[] cornerOffset = new int[total];
			float[] corners = new float[total * 3];
			float[] uvs = new float[total * 2];
			int cornerCount = 0;
			int k = 0;
			for (int p = 0; p < polygons.length; p++) {
				for (ModelPart.Vertex vertex : polygons[p].vertices()) {
					float x = vertex.worldX();
					float y = vertex.worldY();
					float z = vertex.worldZ();
					int found = -1;
					for (int c = 0; c < cornerCount; c++) {
						if (Float.floatToRawIntBits(corners[c * 3]) == Float.floatToRawIntBits(x)
								&& Float.floatToRawIntBits(corners[c * 3 + 1]) == Float.floatToRawIntBits(y)
								&& Float.floatToRawIntBits(corners[c * 3 + 2]) == Float.floatToRawIntBits(z)) {
							found = c;
							break;
						}
					}
					if (found < 0) {
						found = cornerCount++;
						corners[found * 3] = x;
						corners[found * 3 + 1] = y;
						corners[found * 3 + 2] = z;
					}
					vertices[k] = vertex;
					cornerOffset[k] = found * 3;
					uvs[k * 2] = vertex.u();
					uvs[k * 2 + 1] = vertex.v();
					k++;
				}
				faceEnds[p] = k;
			}
			float[] trimmed = new float[cornerCount * 3];
			System.arraycopy(corners, 0, trimmed, 0, trimmed.length);
			return new Layout(polygons, vertices, faceEnds, trimmed, cornerOffset, uvs, cornerCount < total);
		}

		/** True if the cube's faces and their vertices are still exactly the ones this layout was made from. */
		public boolean matches(ModelPart.Polygon[] faces) {
			ModelPart.Polygon[] polygons = this.polygons;
			if (faces.length != polygons.length) {
				return false;
			}
			ModelPart.Vertex[] vertices = this.vertices;
			int k = 0;
			for (int p = 0; p < polygons.length; p++) {
				ModelPart.Polygon polygon = faces[p];
				if (polygon != polygons[p]) {
					return false;
				}
				// Same polygon (a record) means the same vertex array; its elements could still have been replaced.
				ModelPart.Vertex[] faceVertices = polygon.vertices();
				if (k + faceVertices.length != this.faceEnds[p]) {
					return false;
				}
				for (ModelPart.Vertex vertex : faceVertices) {
					if (vertex != vertices[k++]) {
						return false;
					}
				}
			}
			return true;
		}
	}

	/**
	 * Writes the cube the way {@code ModelPart.Cube.compile(pose, builder, lightCoords, overlayCoords, color)} does,
	 * from {@code layout} (which must {@link Layout#matches match} the cube). False, with nothing written, if the
	 * layout has nothing to save: the caller then runs vanilla's code. {@code scratchVector} is vanilla's own scratch
	 * vector of that call (its contents do not matter: every use overwrites it).
	 */
	public static boolean compile(Layout layout, PoseStack.Pose pose, VertexConsumer builder, int lightCoords,
			int overlayCoords, int color, Vector3f scratchVector) {
		if (!layout.worthIt) {
			return false;
		}
		Matrix4f matrix = pose.pose();
		float[] corners = layout.corners;
		boolean renderThread = RenderSystem.isOnRenderThread();
		float[] transformed = renderThread ? renderScratch : SCRATCH.get();
		if (transformed.length < corners.length) {
			transformed = new float[corners.length];
			if (renderThread) {
				renderScratch = transformed;
			} else {
				SCRATCH.set(transformed);
			}
		}
		for (int c = 0; c < corners.length; c += 3) {
			Vector3f pos = matrix.transformPosition(corners[c], corners[c + 1], corners[c + 2], scratchVector);
			transformed[c] = pos.x();
			transformed[c + 1] = pos.y();
			transformed[c + 2] = pos.z();
		}
		ModelPart.Polygon[] polygons = layout.polygons;
		int[] faceEnds = layout.faceEnds;
		float[] uvs = layout.uvs;
		int[] cornerOffset = layout.cornerOffset;
		int k = 0;
		for (int p = 0; p < polygons.length; p++) {
			Vector3f normal = pose.transformNormal(polygons[p].normal(), scratchVector);
			float nx = normal.x();
			float ny = normal.y();
			float nz = normal.z();
			for (int end = faceEnds[p]; k < end; k++) {
				int c = cornerOffset[k];
				builder.addVertex(transformed[c], transformed[c + 1], transformed[c + 2], color, uvs[k * 2], uvs[k * 2 + 1],
						overlayCoords, lightCoords, nx, ny, nz);
			}
		}
		cubes++;
		return true;
	}
}
