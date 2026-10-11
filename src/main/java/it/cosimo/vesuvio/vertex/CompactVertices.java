package it.cosimo.vesuvio.vertex;

import java.nio.ByteBuffer;

import org.lwjgl.system.MemoryUtil;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.renderpearl.api.GpuFormat;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;

/**
 * Setting compactTerrainVertices (World page, off, restart): chunk meshes in a 20-byte vertex instead of vanilla's
 * 28-byte BLOCK vertex, so terrain takes about 29% less graphics memory, upload bandwidth and vertex fetch (which
 * matters most with Etna, whose shadow pass reads every caster vertex once more).
 *
 * <p>Vanilla's BLOCK vertex: Position RGB32_FLOAT (12), Color RGBA8_UNORM (4), UV0 RG32_FLOAT (8), UV2 RG16_SINT (4).
 * The compact one: Position RGBA16_FLOAT (8, the fourth half unused), Color and UV2 unchanged, UV0 RG16_UNORM (4).
 * The GPU's vertex fetch turns half floats and 16-bit unorms into the same {@code vec3 Position} and
 * {@code vec2 UV0} the shaders read, so no shader changes: vanilla's terrain shader and Etna's both work as they are.
 * Both formats are in Vulkan's list of formats every device must support for vertex buffers (no 3-component 16-bit
 * format, which AMD lacks); on Metal they are Half4 and UShort2Normalized.
 *
 * <p>Only the chunk pipelines change: the format is swapped in vanilla's terrain snippet while RenderPipelines is
 * being set up (RenderPipelinesCompactMixin), so every terrain pipeline (solid, cutout, translucent, wireframe,
 * improved fog, multidraw, improved transparency) and every copy another mod makes of one (Etna's shadow and
 * shadowed terrain pipelines copy {@code getVertexFormatBindings()}) uses it from the start, and the section
 * builders, the GPU buffers and the draws all take their stride from {@code ChunkSectionLayer.vertexFormat()}.
 * Block-shaped things drawn outside chunks (moving pistons, falling blocks, the breaking overlay, beacon beams) keep
 * the BLOCK format. Because the pipelines are fixed when the game starts, the setting needs a restart; with it off
 * at start-up none of this is in the game (VertexMixinPlugin) and chunks are built exactly as vanilla builds them.
 *
 * <p>Precision, positions. A chunk vertex position is relative to its section, normally 0 to 16 (a model may stick
 * out up to one block more). A half float holds every multiple of 1/16 exactly up to 32, so blocks on the
 * sixteenth-of-a-block grid (all full blocks, slabs, stairs, most vanilla models) land on exactly the same float as
 * in vanilla and draw the same pixels. Other values (water heights, plants moved off the grid by their random
 * offset, rotated model elements) are rounded to the half grid: 1/1024 of a block near 0, 1/128 between 8 and 16,
 * 1/64 between 16 and 32. They are rounded towards the centre of the 1/16 cell they are in, never onto its edge, so
 * a face kept a hair off a block face (water's sides and top sit 0.001 inside the block next to glass or under a
 * block) stays off it and does not start to flicker against it. The rounding depends only on the value, so a
 * corner shared by two faces gets the same half in both and no cracks open.
 *
 * <p>Precision, texture coordinates. RG16_UNORM steps are 1/65535 of the atlas: under 1/64 texel for an atlas up to
 * 1024 wide, 1/16 texel at 4096, 1/8 at 8192. Quads written through the fast path are rounded towards the middle of
 * their own texture rectangle, so their edges never reach the next sprite in the atlas (no bleeding lines). When the
 * block atlas is wider or taller than 8192 the next start uses float texture coordinates instead (24 bytes, -14%),
 * remembered in vesuvio.properties ({@link TerrainFormat#KEY_FLOAT_UV}); positions stay compact.
 *
 * <p>Default off: positions off the 1/16 grid and every texture coordinate move by a small fraction of a texel or
 * pixel, so the picture is not bit-for-bit vanilla's, though nobody should see the difference.
 */
public final class CompactVertices {
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("world", TerrainFormat.KEY, false, true);

	/** Byte offsets inside the compact vertex. */
	public static final int POSITION = 0;
	public static final int COLOR = 8;
	public static final int UV0 = 12;

	/** The fourth position half, unused by the shaders: 1.0. */
	private static final short HALF_ONE = 0x3C00;

	private CompactVertices() {
	}

	/** Creates the setting and the counters (called from Vesuvio.onInitializeClient). */
	public static void init() {
		it.cosimo.vesuvio.ui.VesuvioStats.register("terrain vertex bytes", () -> TerrainFormat.format().getVertexSize());
		if (TerrainFormat.active()) {
			Vesuvio.LOGGER.info("Vesuvio: compact terrain vertices, {} bytes ({} texture coordinates)",
					TerrainFormat.format().getVertexSize(), TerrainFormat.floatUv() ? "float" : "16-bit");
		}
	}

	/**
	 * Called with the size of every new block atlas. A side over {@link TerrainFormat#MAX_UNORM_ATLAS} with 16-bit
	 * texture coordinates (or the other way round) is noted for the next start; this run keeps its format, since
	 * the pipelines and every chunk already use it.
	 */
	public static void noteBlockAtlas(int width, int height) {
		boolean wantFloat = Math.max(width, height) > TerrainFormat.MAX_UNORM_ATLAS;
		if (wantFloat != Boolean.parseBoolean(VesuvioConfig.raw(TerrainFormat.KEY_FLOAT_UV, "false").trim())) {
			VesuvioConfig.setRaw(TerrainFormat.KEY_FLOAT_UV, Boolean.toString(wantFloat));
		}
		if (wantFloat != TerrainFormat.floatUv()) {
			Vesuvio.LOGGER.warn("Vesuvio: the block atlas is {}x{}; compact terrain vertices switch to {} texture "
					+ "coordinates at the next start", width, height, wantFloat ? "float" : "16-bit");
		}
	}

	// ---- Encoding ----------------------------------------------------------------------------------------------

	/** Writes a position as four halves (x, y, z, 1). */
	public static void putPosition(long pointer, float x, float y, float z) {
		MemoryUtil.memPutShort(pointer, half(x));
		MemoryUtil.memPutShort(pointer + 2L, half(y));
		MemoryUtil.memPutShort(pointer + 4L, half(z));
		MemoryUtil.memPutShort(pointer + 6L, HALF_ONE);
	}

	/**
	 * A coordinate as a half float: exact when it can be (every multiple of 1/16 below 32), otherwise one of the two
	 * halves around it, the one towards the centre of the 1/16 cell it lies in. The centre and the cell's edges are
	 * themselves halves (below 32), so the result stays strictly inside the cell: a value off the 1/16 grid never
	 * lands on it. It depends on the value alone and never reorders two values. Outside +-32 (no vanilla chunk
	 * vertex gets there) it is plain round-to-nearest.
	 */
	static short half(float v) {
		short h = Float.floatToFloat16(v);
		float back = Float.float16ToFloat(h);
		if (back == v || !(Math.abs(v) < 32.0F)) {
			return h;
		}
		float center = (float) Math.floor(v * 16.0F) * 0.0625F + 0.03125F;
		if (v < center) {
			// Round up, towards the centre.
			return back < v ? stepUp(h) : h;
		}
		// v > center (it is not the centre, which is exact): round down.
		return back > v ? stepDown(h) : h;
	}

	/** The next half towards +infinity from a finite half. */
	private static short stepUp(short h) {
		int bits = h & 0xFFFF;
		if (bits == 0x8000) {
			return 0x0001;
		}
		return (short) ((bits & 0x8000) == 0 ? bits + 1 : bits - 1);
	}

	/** The next half towards -infinity from a finite half. */
	private static short stepDown(short h) {
		int bits = h & 0xFFFF;
		if (bits == 0x0000) {
			return (short) 0x8001;
		}
		return (short) ((bits & 0x8000) == 0 ? bits - 1 : bits + 1);
	}

	/** Writes texture coordinates as 16-bit unorms, each rounded to the nearest step. */
	public static void putUv(long pointer, float u, float v) {
		MemoryUtil.memPutShort(pointer, unorm(u, 0));
		MemoryUtil.memPutShort(pointer + 2L, unorm(v, 0));
	}

	/**
	 * Rewrites the texture coordinates of the quad whose first vertex is at {@code first}, each rounded towards the
	 * middle of the quad's own texture rectangle, so that no edge reaches outside it. {@code uv} holds u0 v0 .. u3 v3.
	 */
	public static void putQuadUvs(long first, int stride, float[] uv) {
		float uMid = 0.5F * (Math.min(Math.min(uv[0], uv[2]), Math.min(uv[4], uv[6]))
				+ Math.max(Math.max(uv[0], uv[2]), Math.max(uv[4], uv[6])));
		float vMid = 0.5F * (Math.min(Math.min(uv[1], uv[3]), Math.min(uv[5], uv[7]))
				+ Math.max(Math.max(uv[1], uv[3]), Math.max(uv[5], uv[7])));
		for (int i = 0; i < 4; i++) {
			long pointer = first + (long) i * stride + UV0;
			float u = uv[2 * i];
			float v = uv[2 * i + 1];
			MemoryUtil.memPutShort(pointer, unorm(u, Float.compare(uMid, u)));
			MemoryUtil.memPutShort(pointer + 2L, unorm(v, Float.compare(vMid, v)));
		}
	}

	/** A coordinate in 0..1 as a 16-bit unorm: rounded up for {@code direction} > 0, down for < 0, else nearest. */
	private static short unorm(float c, int direction) {
		double scaled = Math.clamp(c, 0.0F, 1.0F) * 65535.0;
		double r = direction > 0 ? Math.ceil(scaled) : direction < 0 ? Math.floor(scaled) : Math.rint(scaled);
		return (short) (int) r;
	}

	// ---- Reading back ------------------------------------------------------------------------------------------

	/** Whether {@code format} keeps its positions as half floats (the compact formats). */
	public static boolean halfPositions(VertexFormat format) {
		VertexFormatElement position = format.getElement("Position");
		return position != null && position.format() == GpuFormat.RGBA16_FLOAT;
	}

	/** The coordinate stored as a half at {@code pointer}, as the GPU reads it. */
	public static float getHalf(long pointer) {
		return Float.float16ToFloat(MemoryUtil.memGetShort(pointer));
	}

	/**
	 * MeshData.decodeQuadCentroids for half positions (the translucent sort): the same arithmetic as vanilla on
	 * the coordinates the GPU sees. Returns false when this format is not one it handles (vanilla runs then).
	 */
	public static boolean decodeQuadCentroids(ByteBuffer vertexBuffer, int vertexCount, VertexFormat format,
			CompactVectorArray output, int outputIndex) {
		if (!halfPositions(format) || !vertexBuffer.isDirect()) {
			return false;
		}
		int stride = format.getVertexSize();
		long base = MemoryUtil.memAddress(vertexBuffer) + format.getElement("Position").offset();
		int quadStride = stride * 4;
		int quadCount = vertexCount / 4;
		for (int i = 0; i < quadCount; i++) {
			long first = base + (long) i * quadStride;
			long third = first + 2L * stride;
			float xMid = (getHalf(first) + getHalf(third)) / 2.0F;
			float yMid = (getHalf(first + 2L) + getHalf(third + 2L)) / 2.0F;
			float zMid = (getHalf(first + 4L) + getHalf(third + 4L)) / 2.0F;
			output.set(outputIndex + i, xMid, yMid, zMid);
		}
		return true;
	}
}
