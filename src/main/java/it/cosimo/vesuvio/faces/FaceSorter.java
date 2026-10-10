package it.cosimo.vesuvio.faces;

import java.nio.ByteBuffer;
import java.util.Map;

import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import net.minecraft.client.renderer.chunk.ChunkSectionLayer;

/**
 * Build side of face culling, on the section compile threads: sorts the quads of each opaque layer of a
 * freshly built section by the way they face, so that each facing is one contiguous run of the vertex
 * buffer, and records where each run starts.
 *
 * <p>A quad goes in a direction group when its four corners lie exactly in one plane across an axis
 * (x, y or z the same float in all four), that plane is on the block grid (a multiple of 1/64 block,
 * within a block of the section) and both of its triangles (0 1 2 and 2 3 0, the shared quad index buffer)
 * turn the same way. Which way they turn (the right-hand normal of the corners in buffer order, the side
 * the GPU sees as the front) gives + or -. So the group is exactly the side from which the GPU's back-face
 * culling would keep the quad: a +X quad at x = c can only be drawn by a camera with x greater than c.
 * Everything else (crossed plants, rotated or slanted faces, fluid tops, faces just off the grid like
 * Etna's waving leaves, which move in the vertex shader) goes in the "unassigned" group, always drawn.
 *
 * <p>The sort is stable, so quads keep their order inside a group: two quads in exactly the same place
 * facing the same way (a grass block's side and its overlay) are in the same group and still draw in the
 * same order. Quads in different groups can never both be front-facing in the same spot, so the depth
 * test sees the same thing as before: the picture is unchanged. Only the order of whole quads changes;
 * no vertex is altered, added or removed.
 */
public final class FaceSorter {
	/** Group order in the vertex buffer. See FaceCulling for why this order. */
	public static final int PX = 0;
	public static final int PZ = 1;
	public static final int PY = 2;
	public static final int UNASSIGNED = 3;
	public static final int NZ = 4;
	public static final int NX = 5;
	public static final int NY = 6;
	public static final int GROUPS = 7;

	/** Planes further than this outside the section (in blocks) are left unassigned: FaceCulling's bounds rest on it. */
	static final float PLANE_MARGIN = 1.0F;
	private static final float GRID = 64.0F;
	private static final float GRID_TOLERANCE = 0.01F;

	private static final ThreadLocal<Scratch> SCRATCH = ThreadLocal.withInitial(Scratch::new);

	private FaceSorter() {
	}

	/** Per-thread working memory: one int per quad, and room to hold two quads while they are swapped. */
	private static final class Scratch {
		int[] dest = new int[4096];
		ByteBuffer swap = ByteBuffer.allocateDirect(1024);

		int[] dest(int quads) {
			if (dest.length < quads) {
				dest = new int[Math.max(quads, dest.length * 2)];
			}
			return dest;
		}

		long swap(int quadBytes) {
			if (swap.capacity() < 2 * quadBytes) {
				swap = ByteBuffer.allocateDirect(2 * quadBytes);
			}
			return MemoryUtil.memAddress(swap);
		}
	}

	/** Groups every opaque layer of a compile's results. Never null: a non-null array marks a section built with face culling on. */
	public static int[][] groupAll(Map<ChunkSectionLayer, MeshData> layers) {
		int[][] groups = new int[ChunkSectionLayer.values().length][];
		for (Map.Entry<ChunkSectionLayer, MeshData> entry : layers.entrySet()) {
			ChunkSectionLayer layer = entry.getKey();
			if (!layer.translucent()) {
				groups[layer.ordinal()] = sort(entry.getValue(), layer.vertexFormat());
			}
		}
		return groups;
	}

	/**
	 * Sorts one layer's quads in place by facing group. Returns the 8 group start offsets (in quads; the
	 * last is the quad count), or null when the mesh is not something this understands (then nothing is
	 * touched and the layer is drawn whole).
	 */
	static int @Nullable [] sort(MeshData mesh, @Nullable VertexFormat format) {
		MeshData.DrawState state = mesh.drawState();
		if (format == null || state.primitiveTopology() != PrimitiveTopology.QUADS || mesh.indexBuffer() != null) {
			return null;
		}
		VertexFormatElement position = format.getElement("Position");
		if (position == null || position.format() != GpuFormat.RGB32_FLOAT) {
			return null;
		}
		int vertices = state.vertexCount();
		int stride = format.getVertexSize();
		ByteBuffer buffer = mesh.vertexBuffer();
		if (vertices <= 0 || vertices % 4 != 0 || !buffer.isDirect() || (long) vertices * stride > buffer.remaining()) {
			return null;
		}
		long base = MemoryUtil.memAddress(buffer) + position.offset();
		int quads = vertices / 4;
		int quadBytes = 4 * stride;
		Scratch scratch = SCRATCH.get();
		int[] dest = scratch.dest(quads);

		// Pass 1: the group of each quad, and how many quads each group has.
		int[] starts = new int[GROUPS + 1];
		boolean inOrder = true;
		int previous = 0;
		for (int q = 0; q < quads; q++) {
			int group = classify(base + (long) q * quadBytes, stride);
			dest[q] = group;
			starts[group + 1]++;
			inOrder &= group >= previous;
			previous = group;
		}
		for (int g = 0; g < GROUPS; g++) {
			starts[g + 1] += starts[g];
		}
		if (inOrder) {
			return starts;
		}

		// Pass 2: where each quad goes (a stable counting sort).
		int[] next = new int[GROUPS];
		System.arraycopy(starts, 0, next, 0, GROUPS);
		for (int q = 0; q < quads; q++) {
			dest[q] = next[dest[q]]++;
		}

		// Pass 3: move the quads, following each cycle of the permutation, so only two quads of extra
		// memory are needed. Each quad is copied out and in once.
		long data = MemoryUtil.memAddress(buffer);
		long carry = scratch.swap(quadBytes);
		long spare = carry + quadBytes;
		for (int start = 0; start < quads; start++) {
			if (dest[start] == start) {
				continue;
			}
			MemoryUtil.memCopy(data + (long) start * quadBytes, carry, quadBytes);
			int at = start;
			while (true) {
				int to = dest[at];
				dest[at] = at;
				if (to == start) {
					MemoryUtil.memCopy(carry, data + (long) start * quadBytes, quadBytes);
					break;
				}
				MemoryUtil.memCopy(data + (long) to * quadBytes, spare, quadBytes);
				MemoryUtil.memCopy(carry, data + (long) to * quadBytes, quadBytes);
				long t = carry;
				carry = spare;
				spare = t;
				at = to;
			}
		}
		return starts;
	}

	/** The group of the quad whose first vertex position is at {@code v0}. */
	private static int classify(long v0, int stride) {
		long v1 = v0 + stride;
		long v2 = v1 + stride;
		long v3 = v2 + stride;
		float x0 = MemoryUtil.memGetFloat(v0), y0 = MemoryUtil.memGetFloat(v0 + 4), z0 = MemoryUtil.memGetFloat(v0 + 8);
		float x1 = MemoryUtil.memGetFloat(v1), y1 = MemoryUtil.memGetFloat(v1 + 4), z1 = MemoryUtil.memGetFloat(v1 + 8);
		float x2 = MemoryUtil.memGetFloat(v2), y2 = MemoryUtil.memGetFloat(v2 + 4), z2 = MemoryUtil.memGetFloat(v2 + 8);
		float x3 = MemoryUtil.memGetFloat(v3), y3 = MemoryUtil.memGetFloat(v3 + 4), z3 = MemoryUtil.memGetFloat(v3 + 8);
		if (x0 == x1 && x0 == x2 && x0 == x3) {
			// Normal's x of triangle 0 1 2 and of triangle 2 3 0.
			float a = (y1 - y0) * (z2 - z0) - (z1 - z0) * (y2 - y0);
			float b = (y3 - y2) * (z0 - z2) - (z3 - z2) * (y0 - y2);
			return axis(x0, a, b, PX, NX);
		}
		if (y0 == y1 && y0 == y2 && y0 == y3) {
			float a = (z1 - z0) * (x2 - x0) - (x1 - x0) * (z2 - z0);
			float b = (z3 - z2) * (x0 - x2) - (x3 - x2) * (z0 - z2);
			return axis(y0, a, b, PY, NY);
		}
		if (z0 == z1 && z0 == z2 && z0 == z3) {
			float a = (x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0);
			float b = (x3 - x2) * (y0 - y2) - (y3 - y2) * (x0 - x2);
			return axis(z0, a, b, PZ, NZ);
		}
		return UNASSIGNED;
	}

	/**
	 * A quad in the plane at {@code plane} across an axis, its two triangles' normal components along that
	 * axis {@code a} and {@code b}. A triangle with no area draws nothing, so only the other one counts;
	 * triangles turned opposite ways (a folded quad) or a quad with no area at all stay unassigned.
	 */
	private static int axis(float plane, float a, float b, int positive, int negative) {
		if (!(plane >= -PLANE_MARGIN && plane <= 16.0F + PLANE_MARGIN)) {
			return UNASSIGNED;
		}
		float grid = plane * GRID;
		if (Math.abs(grid - Math.round(grid)) > GRID_TOLERANCE) {
			return UNASSIGNED;
		}
		if (a >= 0.0F && b >= 0.0F && (a > 0.0F || b > 0.0F)) {
			return positive;
		}
		if (a <= 0.0F && b <= 0.0F && (a < 0.0F || b < 0.0F)) {
			return negative;
		}
		return UNASSIGNED;
	}
}
