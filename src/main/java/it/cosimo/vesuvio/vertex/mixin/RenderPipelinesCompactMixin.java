package it.cosimo.vesuvio.vertex.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderPipeline;

import it.cosimo.vesuvio.vertex.TerrainFormat;
import net.minecraft.client.renderer.RenderPipelines;

/**
 * Setting compactTerrainVertices (only in the game when it was on at start-up, VertexMixinPlugin): while
 * RenderPipelines sets up its static fields, the snippet that gives terrain its shader ("core/terrain", vanilla's
 * UNLIT_TERRAIN_SNIPPET, the base of every chunk pipeline: solid, cutout, translucent, wireframe, improved fog,
 * multidraw and the improved-transparency sets) also gets the compact vertex format on buffer 0, in place of the
 * BLOCK format it took from GENERIC_BLOCKS_SNIPPET. GENERIC_BLOCKS_SNIPPET itself, and so the block pipelines drawn
 * outside chunks (moving blocks, falling blocks, block items), keep BLOCK.
 *
 * <p>This runs inside RenderPipelines' class initialiser, before any of its fields can be read, so nothing (not
 * ChunkSectionLayer, not Etna's copies of the terrain pipelines) ever sees the terrain pipelines with the old format.
 */
@Mixin(RenderPipelines.class)
public abstract class RenderPipelinesCompactMixin {
	@WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;withVertexShader(Ljava/lang/String;)Lcom/mojang/blaze3d/pipeline/RenderPipeline$Builder;"))
	private static RenderPipeline.Builder vesuvio$compactTerrainFormat(RenderPipeline.Builder builder, String shader,
			Operation<RenderPipeline.Builder> original) {
		RenderPipeline.Builder result = original.call(builder, shader);
		if ("core/terrain".equals(shader) && TerrainFormat.active()) {
			result.withVertexBinding(0, TerrainFormat.format());
		}
		return result;
	}
}
