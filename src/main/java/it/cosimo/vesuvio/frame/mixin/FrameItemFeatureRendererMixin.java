package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import net.minecraft.client.resources.model.geometry.BakedQuad;

/** Items (in the world, in hand, in the GUI item atlas): every quad written marks its sprite as seen. */
@Mixin(ItemFeatureRenderer.class)
public abstract class FrameItemFeatureRendererMixin {
	@ModifyArg(method = { "prepareMainSubmit", "prepareOutlineSubmit" }, at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuad(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;)V"),
			index = 1)
	private BakedQuad vesuvio$markQuad(BakedQuad quad) {
		AnimatedTextures.markQuad(quad);
		return quad;
	}

	@ModifyArg(method = "prepareMainSubmit", at = @At(value = "INVOKE",
			target = "Lcom/mojang/blaze3d/vertex/VertexConsumer;putBakedQuadWithGlint(Lcom/mojang/blaze3d/vertex/PoseStack$Pose;Lnet/minecraft/client/resources/model/geometry/BakedQuad;Lcom/mojang/blaze3d/vertex/QuadInstance;Lcom/mojang/blaze3d/vertex/PoseStack$Pose;)V"),
			index = 1)
	private BakedQuad vesuvio$markGlintQuad(BakedQuad quad) {
		AnimatedTextures.markQuad(quad);
		return quad;
	}
}
