package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import it.cosimo.vesuvio.frame.ModelSprites;
import it.cosimo.vesuvio.frame.SpriteCollectorHolder;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.renderer.block.FluidStateModelSet;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;

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
	@ModifyExpressionValue(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/block/BlockStateModelSet;get(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/client/renderer/block/dispatch/BlockStateModel;"))
	private BlockStateModel vesuvio$collectModelSprites(BlockStateModel model, @Local SectionCompiler.Results results) {
		SpriteSet sprites = ((SpriteCollectorHolder) (Object) results).vesuvio$sprites();
		if (sprites != null) {
			ModelSprites.collect(model, sprites);
		}
		return model;
	}

	/**
	 * Every fluid: its model's sprites. Only water and lava have vanilla fluid models; any other fluid (from a mod) is
	 * drawn with sprites this cannot know (Fabric API's fluid render handlers), so its section is "unknown". The hook
	 * is the "has a fluid" test right before the fluid is tesselated; what it collects does not depend on the
	 * tesselation, so collecting just before it instead of just after gives the same set.
	 */
	@ModifyExpressionValue(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/material/FluidState;isEmpty()Z"))
	private boolean vesuvio$collectFluidSprites(boolean empty, @Local FluidState fluidState,
			@Local SectionCompiler.Results results) {
		if (!empty) {
			SpriteSet sprites = ((SpriteCollectorHolder) (Object) results).vesuvio$sprites();
			if (sprites != null) {
				this.vesuvio$addFluid(fluidState, sprites);
			}
		}
		return empty;
	}

	private void vesuvio$addFluid(FluidState fluidState, SpriteSet sprites) {
		Fluid type = fluidState.getType();
		if (type != Fluids.WATER && type != Fluids.FLOWING_WATER && type != Fluids.LAVA && type != Fluids.FLOWING_LAVA) {
			sprites.markUnknown();
			return;
		}
		FluidModel model = this.fluidModelSet.get(fluidState);
		sprites.add(model.stillMaterial().sprite());
		sprites.add(model.flowingMaterial().sprite());
		if (model.overlayMaterial() != null) {
			sprites.add(model.overlayMaterial().sprite());
		}
	}
}
