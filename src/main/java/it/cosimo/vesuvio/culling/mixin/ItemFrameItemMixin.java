package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;

import it.cosimo.vesuvio.culling.DetailCulling;
import net.minecraft.client.renderer.MapRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.entity.ItemFrameRenderer;
import net.minecraft.client.renderer.entity.state.ItemFrameRenderState;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.state.MapRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;

/** Item frame culling, submit side: the framed map or item is left out once it is smaller than a pixel. */
@Mixin(ItemFrameRenderer.class)
abstract class ItemFrameItemMixin {
	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/MapRenderer;render(Lnet/minecraft/client/renderer/state/MapRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;ZI)V"))
	private void vesuvio$cullFramedMap(MapRenderer renderer, MapRenderState map, PoseStack poseStack,
			SubmitNodeCollector collector, boolean showOnlyFrame, int lightCoords, Operation<Void> original,
			@Local(argsOnly = true) ItemFrameRenderState state, @Local(argsOnly = true) CameraRenderState camera) {
		if (!DetailCulling.framedItemHidden(poseStack.last().pose(), state.outlineColor, camera)) {
			original.call(renderer, map, poseStack, collector, showOnlyFrame, lightCoords);
		}
	}

	@WrapOperation(method = "submit(Lnet/minecraft/client/renderer/entity/state/ItemFrameRenderState;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;)V",
			at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/item/ItemStackRenderState;submit(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/SubmitNodeCollector;III)V"))
	private void vesuvio$cullFramedItem(ItemStackRenderState item, PoseStack poseStack, SubmitNodeCollector collector,
			int lightCoords, int overlayCoords, int outlineColor, Operation<Void> original,
			@Local(argsOnly = true) CameraRenderState camera) {
		if (!DetailCulling.framedItemHidden(poseStack.last().pose(), outlineColor, camera)) {
			original.call(item, poseStack, collector, lightCoords, overlayCoords, outlineColor);
		}
	}
}
