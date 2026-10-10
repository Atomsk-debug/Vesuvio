package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import it.cosimo.vesuvio.memory.ShapeDedup;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareBlockShapes: the occlusion shape each block state caches in initCache is interned before
 * its six face shapes are taken, so equal states also share those face slices (VoxelShape caches them).
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateOcclusionShapeMixin {
	@ModifyExpressionValue(method = "initCache", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/Block;getOcclusionShape(Lnet/minecraft/world/level/block/state/BlockState;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape vesuvio$internOcclusion(VoxelShape shape) {
		return ShapeDedup.intern(shape);
	}
}
