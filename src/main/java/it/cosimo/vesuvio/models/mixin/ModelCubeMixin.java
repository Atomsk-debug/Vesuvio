package it.cosimo.vesuvio.models.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import it.cosimo.vesuvio.models.ModelFastPath;
import net.minecraft.client.model.geom.ModelPart;

/**
 * Setting fastEntityModels: each cube's 8 corners are transformed once instead of once per face (24 vertices), with
 * the same bytes written ({@link ModelFastPath}). The corner layout is kept on the cube and checked before each use.
 */
@Mixin(ModelPart.Cube.class)
abstract class ModelCubeMixin {
	@Shadow
	@Final
	public ModelPart.Polygon[] polygons;

	@Unique
	private ModelFastPath.Layout vesuvio$layout;

	@Inject(method = "compile", at = @At("HEAD"), cancellable = true)
	private void vesuvio$fastCompile(PoseStack.Pose pose, VertexConsumer builder, int lightCoords, int overlayCoords,
			int color, CallbackInfo ci) {
		if (!ModelFastPath.FAST_MODELS.on()) {
			return;
		}
		ModelFastPath.Layout layout = this.vesuvio$layout;
		if (layout == null || !layout.matches(this.polygons)) {
			layout = ModelFastPath.Layout.of(this.polygons);
			this.vesuvio$layout = layout;
		}
		if (ModelFastPath.compile(layout, pose, builder, lightCoords, overlayCoords, color)) {
			ci.cancel();
		}
	}
}
