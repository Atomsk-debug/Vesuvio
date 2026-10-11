package it.cosimo.vesuvio.vertex.mixin;

import org.lwjgl.system.MemoryUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;

import it.cosimo.vesuvio.vertex.CompactVertices;
import it.cosimo.vesuvio.vertex.TerrainFormat;

/**
 * Setting compactTerrainVertices (only in the game when it was on at start-up): BufferBuilder writes the compact
 * chunk format. Vanilla's BufferBuilder writes positions and texture coordinates as floats whatever the format's
 * elements say, so a builder made for a compact format (only the section compiler makes those, through
 * ChunkSectionLayer.vertexFormat()) needs its own writes:
 * <ul>
 * <li>the 11-argument addVertex, vanilla's fast path, used for every block quad (ModelBlockRenderer's
 * putBlockBakedQuad, the fluid renderer, Fabric API's Indigo renderer through QuadView.buffer);</li>
 * <li>the element-by-element path: addVertex(x, y, z) and setUv. Colour and light (setColor, setUv2, setLight)
 * already write at the element's offset in the format they always had, and the overlay and normal are not in
 * the format, so vanilla drops them as it does for BLOCK.</li>
 * </ul>
 * Builders of every other format (mode 0) run vanilla's code untouched.
 *
 * <p>Cost: every hook here is a {@code @WrapWithCondition}, which calls its handler and nothing else (no
 * CallbackInfo, no argument array), and each sits on a call only the slow paths make: vanilla's BLOCK and ENTITY
 * fast paths of the 11-argument addVertex (all block-model and entity vertices drawn outside chunks) never reach
 * one, and the element-by-element path pays one field compare per position and per texture coordinate written.
 */
@Mixin(BufferBuilder.class)
public abstract class BufferBuilderCompactMixin {
	@Shadow
	private int vertices;
	@Shadow
	@Final
	private int vertexSize;
	@Shadow
	@Final
	private PrimitiveTopology primitiveTopology;

	/** 0: not a compact format (vanilla code); 1: 20 bytes, unorm16 texture coordinates; 2: 24 bytes, float ones. */
	@Unique
	private int vesuvio$mode;
	/** Texture coordinates of the quad being written (u0 v0 .. u3 v3), mode 1 only. */
	@Unique
	private float[] vesuvio$quadUv;
	/** Which corners of that quad came through the fast path (bit per corner). */
	@Unique
	private int vesuvio$quadCorners;

	@Shadow
	private long beginVertex() {
		throw new AssertionError();
	}

	@Shadow
	private static void putRgba(long pointer, int argb) {
		throw new AssertionError();
	}

	@Shadow
	private static void putPackedUv(long pointer, int packedUv) {
		throw new AssertionError();
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void vesuvio$compactMode(ByteBufferBuilder buffer, PrimitiveTopology primitiveTopology, VertexFormat format,
			CallbackInfo ci) {
		this.vesuvio$mode = TerrainFormat.mode(format);
		if (this.vesuvio$mode == 1) {
			this.vesuvio$quadUv = new float[8];
		}
	}

	/**
	 * The 11-argument addVertex. A compact format is neither BLOCK nor ENTITY, so vanilla's method reaches its last
	 * branch, {@code VertexConsumer.super.addVertex(...)} (the element-by-element default): a compact builder writes
	 * the whole vertex here instead and skips that call; any other builder makes it as in vanilla.
	 */
	@WrapWithCondition(method = "addVertex(FFFIFFIIFFF)V", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;addVertex(FFFIFFIIFFF)V"))
	private boolean vesuvio$compactVertex(VertexConsumer self, float x, float y, float z, int color, float u, float v,
			int overlayCoords, int lightCoords, float nx, float ny, float nz) {
		int mode = this.vesuvio$mode;
		if (mode == 0) {
			return true;
		}
		long pointer = this.beginVertex();
		CompactVertices.putPosition(pointer + CompactVertices.POSITION, x, y, z);
		putRgba(pointer + CompactVertices.COLOR, color);
		if (mode == 1) {
			CompactVertices.putUv(pointer + CompactVertices.UV0, u, v);
			putPackedUv(pointer + CompactVertices.UV0 + 4L, lightCoords);
			this.vesuvio$quadCorner(pointer, u, v);
		} else {
			MemoryUtil.memPutFloat(pointer + CompactVertices.UV0, u);
			MemoryUtil.memPutFloat(pointer + CompactVertices.UV0 + 4L, v);
			putPackedUv(pointer + CompactVertices.UV0 + 8L, lightCoords);
		}
		return false;
	}

	/**
	 * Keeps the quad's texture coordinates; once its fourth corner is in and all four came through here, rewrites
	 * them rounded towards the middle of the quad's texture rectangle (CompactVertices.putQuadUvs).
	 */
	@Unique
	private void vesuvio$quadCorner(long pointer, float u, float v) {
		if (this.primitiveTopology != PrimitiveTopology.QUADS) {
			return;
		}
		int corner = (this.vertices - 1) & 3;
		float[] uv = this.vesuvio$quadUv;
		uv[2 * corner] = u;
		uv[2 * corner + 1] = v;
		int corners = corner == 0 ? 1 : this.vesuvio$quadCorners | 1 << corner;
		this.vesuvio$quadCorners = corners;
		if (corners == 0xF) {
			CompactVertices.putQuadUvs(pointer - 3L * this.vertexSize, this.vertexSize, uv);
		}
	}

	/**
	 * addVertex(x, y, z): vanilla has already begun the vertex at the Position element (offset 0 in both compact
	 * formats) and reset the elements to fill; a compact builder writes the position as halves instead of floats.
	 */
	@WrapWithCondition(method = "addVertex(FFF)Lcom/mojang/blaze3d/vertex/VertexConsumer;", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/BufferBuilder;putVec3f(JFFF)V"))
	private boolean vesuvio$compactPosition(long pointer, float x, float y, float z) {
		if (this.vesuvio$mode == 0) {
			return true;
		}
		CompactVertices.putPosition(pointer, x, y, z);
		this.vesuvio$quadCorners = 0;
		return false;
	}

	/** setUv, u (first float write, at the UV0 element vanilla found): 16 bits in the 20-byte format. */
	@WrapWithCondition(method = "setUv", at = @At(value = "INVOKE", target = "Lorg/lwjgl/system/MemoryUtil;memPutFloat(JF)V",
			ordinal = 0))
	private boolean vesuvio$compactU(long pointer, float u) {
		if (this.vesuvio$mode != 1) {
			return true;
		}
		CompactVertices.putUnorm(pointer, u);
		return false;
	}

	/** setUv, v (second float write, 4 bytes after u in vanilla's layout): right after u's 16 bits. */
	@WrapWithCondition(method = "setUv", at = @At(value = "INVOKE", target = "Lorg/lwjgl/system/MemoryUtil;memPutFloat(JF)V",
			ordinal = 1))
	private boolean vesuvio$compactV(long pointerPlus4, float v) {
		if (this.vesuvio$mode != 1) {
			return true;
		}
		CompactVertices.putUnorm(pointerPlus4 - 2L, v);
		return false;
	}
}
