package it.cosimo.vesuvio.models.mixin;

import org.joml.Vector3f;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
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
	private static final ModelPart.Polygon[] NO_POLYGONS = new ModelPart.Polygon[0];

	@Unique
	private ModelFastPath.Layout vesuvio$layout;

	/**
	 * The cube's face list as compile is about to loop over it: when the fast path has written the cube, the loop
	 * gets no faces and vanilla's compile ends having written nothing more. Unlike a cancellable HEAD inject this
	 * allocates no CallbackInfo per cube; the fast path reuses vanilla's scratch vector of the call.
	 */
	@ModifyExpressionValue(method = "compile", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD,
			target = "Lnet/minecraft/client/model/geom/ModelPart$Cube;polygons:[Lnet/minecraft/client/model/geom/ModelPart$Polygon;"))
	private ModelPart.Polygon[] vesuvio$fastCompile(ModelPart.Polygon[] polygons,
			@Local(argsOnly = true) PoseStack.Pose pose, @Local(argsOnly = true) VertexConsumer builder,
			@Local(argsOnly = true, ordinal = 0) int lightCoords, @Local(argsOnly = true, ordinal = 1) int overlayCoords,
			@Local(argsOnly = true, ordinal = 2) int color, @Local(ordinal = 0) Vector3f scratchVector) {
		if (!ModelFastPath.FAST_MODELS.on()) {
			return polygons;
		}
		ModelFastPath.Layout layout = this.vesuvio$layout;
		if (layout == null || !layout.matches(this.polygons)) {
			layout = ModelFastPath.Layout.of(this.polygons);
			this.vesuvio$layout = layout;
		}
		if (ModelFastPath.compile(layout, pose, builder, lightCoords, overlayCoords, color, scratchVector)) {
			return NO_POLYGONS;
		}
		return polygons;
	}
}
