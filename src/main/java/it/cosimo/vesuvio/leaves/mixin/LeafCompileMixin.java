package it.cosimo.vesuvio.leaves.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.leaves.LeafCulling;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * SectionCompiler.compile: tells {@link LeafCulling} which block is about to be tesselated. The hook is the block's
 * seed lookup, an argument of the tesselate call: Fabric API redirects that call to its own renderer but the argument
 * is still computed here, right before it.
 */
@Mixin(SectionCompiler.class)
public abstract class LeafCompileMixin {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/state/BlockState;getSeed(Lnet/minecraft/core/BlockPos;)J"))
	private long vesuvio$recordLeaf(BlockState state, BlockPos pos, Operation<Long> original,
			@Local(argsOnly = true) RenderSectionRegion region) {
		LeafCulling.beginBlock(region, pos, state);
		return original.call(state, pos);
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private void vesuvio$forgetRegion(CallbackInfoReturnable<SectionCompiler.Results> cir) {
		LeafCulling.endCompile();
	}
}
