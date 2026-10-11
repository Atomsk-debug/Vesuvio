package it.cosimo.vesuvio.vertex;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.renderpearl.api.GpuFormat;

import it.cosimo.vesuvio.VesuvioConfig;

/**
 * The vertex format of chunk meshes this run, decided once from vesuvio.properties as saved at start-up: the master
 * switch and compactTerrainVertices both on (and none of its mixins switched off) give a compact format
 * ({@link CompactVertices}), otherwise vanilla's BLOCK: the same decision as VertexMixinPlugin's. Makes no setting,
 * so RenderPipelines' set-up can ask before Vesuvio's own start-up.
 */
public final class TerrainFormat {
	/** The setting key. */
	public static final String KEY = "compactTerrainVertices";
	/** Hidden key: true once a block atlas wider or taller than {@link #MAX_UNORM_ATLAS} was seen. */
	public static final String KEY_FLOAT_UV = "compactTerrainFloatUv";
	/** Largest block atlas side the 16-bit texture coordinates are used for. */
	public static final int MAX_UNORM_ATLAS = 8192;

	/** 20 bytes: half4 position (w unused), RGBA8 colour, unorm16 texture coordinates, short2 light. */
	public static final VertexFormat UNORM_UV = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGBA16_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG16_UNORM)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.build();
	/** 24 bytes: the same with float texture coordinates, for block atlases over {@link #MAX_UNORM_ATLAS}. */
	public static final VertexFormat FLOAT_UV = VertexFormat.builder(0)
			.addAttribute("Position", GpuFormat.RGBA16_FLOAT)
			.addAttribute("Color", GpuFormat.RGBA8_UNORM)
			.addAttribute("UV0", GpuFormat.RG32_FLOAT)
			.addAttribute("UV2", GpuFormat.RG16_SINT)
			.build();

	private static final boolean ACTIVE = VertexMixinPlugin.startDecision();
	private static final boolean USE_FLOAT_UV = on(KEY_FLOAT_UV, false);

	private TerrainFormat() {
	}

	private static boolean on(String key, boolean fallback) {
		return Boolean.parseBoolean(VesuvioConfig.raw(key, Boolean.toString(fallback)).trim());
	}

	/** Whether chunk meshes use a compact format this run. */
	public static boolean active() {
		return ACTIVE;
	}

	/** Whether the compact format keeps float texture coordinates (24 bytes) this run. */
	public static boolean floatUv() {
		return USE_FLOAT_UV;
	}

	/** The vertex format of the chunk pipelines this run, compact or vanilla's. */
	public static VertexFormat format() {
		return !ACTIVE ? DefaultVertexFormat.BLOCK : USE_FLOAT_UV ? FLOAT_UV : UNORM_UV;
	}

	/** 1 for the 20-byte format, 2 for the 24-byte one, 0 for any other (BufferBuilder's mode). */
	public static int mode(VertexFormat format) {
		return format == UNORM_UV ? 1 : format == FLOAT_UV ? 2 : 0;
	}
}
