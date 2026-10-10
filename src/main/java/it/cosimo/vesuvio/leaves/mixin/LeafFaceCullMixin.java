package it.cosimo.vesuvio.leaves.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.leaves.LeafCulling;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ModelBlockRenderer.shouldRenderFace (private, reached only when the renderer culls, i.e. from SectionCompiler):
 * after vanilla's own decision ({@code Block.shouldRenderFace}), a face it would draw may still be dropped by
 * {@link LeafCulling#cull}. Vanilla's answer is kept whenever it says "hidden", and with leaf culling off the wrapper
 * returns it untouched.
 */
@Mixin(ModelBlockRenderer.class)
public abstract class LeafFaceCullMixin {
	/** Scratch position for the look-ahead; one renderer is used by one compile thread at a time. */
	@Unique
	private final BlockPos.MutableBlockPos vesuvio$leafProbe = new BlockPos.MutableBlockPos();

	@WrapOperation(method = "shouldRenderFace", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/Block;shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z"))
	private boolean vesuvio$cullBuriedLeaves(BlockState state, BlockState neighborState, Direction direction,
			Operation<Boolean> original, @Local(argsOnly = true) BlockAndTintGetter level,
			@Local(argsOnly = true) BlockPos neighborPos) {
		return original.call(state, neighborState, direction)
				&& !LeafCulling.cull(level, state, neighborState, neighborPos, direction, vesuvio$leafProbe);
	}
}
