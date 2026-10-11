package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;

import it.cosimo.vesuvio.culling.DetailCulling;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.blockentity.AbstractSignRenderer;
import net.minecraft.client.renderer.blockentity.state.SignRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.level.block.entity.SignText;

/**
 * Sign text culling: each side's text (front, then back) is submitted only if it can cover a pixel. The sign board is
 * terrain; text is all this renderer draws.
 */
@Mixin(AbstractSignRenderer.class)
abstract class SignTextMixin {
	@WrapWithCondition(
		method = "submit(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
		at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/blockentity/AbstractSignRenderer;submitSignText(Lnet/minecraft/client/renderer/blockentity/state/SignRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/world/level/block/entity/SignText;)V")
	)
	private boolean vesuvio$cullSignText(AbstractSignRenderer<?> self, SignRenderState state, PoseStack poseStack,
			SubmitNodeCollector collector, SignText text, @Local(argsOnly = true) CameraRenderState camera) {
		return !DetailCulling.signTextHidden(state, poseStack.last().pose(), camera);
	}
}
