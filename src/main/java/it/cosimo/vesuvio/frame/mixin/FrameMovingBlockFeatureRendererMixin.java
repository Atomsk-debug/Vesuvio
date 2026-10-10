package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.renderer.feature.MovingBlockFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Moving blocks (pistons, falling blocks): mark each quad. */
@Mixin(MovingBlockFeatureRenderer.class)
public abstract class FrameMovingBlockFeatureRendererMixin {
	@ModifyArg(method = "putBakedQuad", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),
			index = 1)
	private BakedQuad vesuvio$markQuad(BakedQuad quad) {
		AnimatedTextures.markQuad(quad);
		return quad;
	}
}
