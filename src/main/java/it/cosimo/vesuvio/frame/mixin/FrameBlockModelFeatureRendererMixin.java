package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.renderer.feature.BlockModelFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Block models drawn by entities (carried blocks, minecart contents, TNT, block displays): mark each quad. */
@Mixin(BlockModelFeatureRenderer.class)
public abstract class FrameBlockModelFeatureRendererMixin {
	@ModifyArg(method = "putQuad", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),
			index = 1)
	private static BakedQuad vesuvio$markQuad(BakedQuad quad) {
		AnimatedTextures.markQuad(quad);
		return quad;
	}
}
