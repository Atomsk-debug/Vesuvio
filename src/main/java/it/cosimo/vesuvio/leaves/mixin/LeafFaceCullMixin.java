package it.cosimo.vesuvio.leaves.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.leaves.LeafCulling;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block.shouldRenderFace, the face test both vanilla's block renderer and Fabric API's Indigo renderer use: a face
 * vanilla would draw may still be dropped by {@link LeafCulling#cullAtCompile} (only inside a section compile, only
 * leaves). Vanilla's "hidden" is never changed, and with leaf culling off the answer is returned untouched.
 */
@Mixin(Block.class)
public abstract class LeafFaceCullMixin {
	@ModifyReturnValue(method = "shouldRenderFace(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Z",
			at = @At("RETURN"))
	private static boolean vesuvio$cullBuriedLeaves(boolean draw, @Local(argsOnly = true, ordinal = 0) BlockState state,
			@Local(argsOnly = true, ordinal = 1) BlockState neighborState, @Local(argsOnly = true) Direction direction) {
		return draw && !LeafCulling.cullAtCompile(state, neighborState, direction);
	}
}
