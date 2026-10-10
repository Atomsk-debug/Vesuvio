package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import it.cosimo.vesuvio.frame.ModelSprites;
import it.cosimo.vesuvio.frame.SpriteCollectorHolder;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.BlockStateModelSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidRenderer;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * While a section compiles (worker thread), collect the animated sprites its block models and fluids use. The set
 * rides on the compile's Results into the CompiledSectionMesh. Off: nothing is collected and the mesh says "unknown".
 */
@Mixin(SectionCompiler.class)
public abstract class FrameSectionCompilerMixin {
	@Shadow
	@Final
	private FluidStateModelSet fluidModelSet;

	@ModifyExpressionValue(method = "compile", at = @At(value = "NEW",
			target = "net/minecraft/client/renderer/block/ModelBlockRenderer"))
	private ModelBlockRenderer vesuvio$collectSprites(ModelBlockRenderer renderer,
			@Local SectionCompiler.Results results) {
		if (AnimatedTextures.SETTING.on()) {
			((SpriteCollectorHolder) (Object) results).vesuvio$setSprites(new SpriteSet());
		}
		return renderer;
	}

	/**
	 * Every block with a model: its model's animated sprites. The model lookup is an argument of the tesselate call,
	 * so it runs whichever renderer then draws the block (Fabric API redirects that call to its Indigo renderer).
	 */
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockStateModelSet;get(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"))
	private BlockStateModel vesuvio$collectModelSprites(BlockStateModelSet modelSet, BlockState state,
			Operation<BlockStateModel> original, @Local SectionCompiler.Results results) {
		BlockStateModel model = original.call(modelSet, state);
		SpriteSet sprites = ((SpriteCollectorHolder) (Object) results).vesuvio$sprites();
		if (sprites != null) {
			ModelSprites.collect(model, sprites);
		}
		return model;
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/FluidRenderer;tesselate(Lnet/minecraft/client/renderer/block/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/client/renderer/block/FluidRenderer$Output;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/material/FluidState;)V"))
	private void vesuvio$collectFluidSprites(FluidRenderer fluidRenderer, BlockAndTintGetter level, BlockPos pos,
			FluidRenderer.Output output, BlockState blockState, FluidState fluidState, Operation<Void> original,
			@Local SectionCompiler.Results results) {
		original.call(fluidRenderer, level, pos, output, blockState, fluidState);
		SpriteSet sprites = ((SpriteCollectorHolder) (Object) results).vesuvio$sprites();
		if (sprites != null) {
			FluidModel model = this.fluidModelSet.get(fluidState);
			sprites.add(model.stillMaterial().sprite());
			sprites.add(model.flowingMaterial().sprite());
			if (model.overlayMaterial() != null) {
				sprites.add(model.overlayMaterial().sprite());
			}
		}
	}
}
