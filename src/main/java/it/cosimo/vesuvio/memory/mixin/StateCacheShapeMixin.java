package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import it.cosimo.vesuvio.memory.ShapeDedup;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareBlockShapes: the collision shape each block state caches at start-up
 * (BlockBehaviour.BlockStateBase.Cache) is interned too, for blocks that build it fresh on every call.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
abstract class StateCacheShapeMixin {
	@ModifyExpressionValue(method = "<init>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/level/block/Block;getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
	private VoxelShape vesuvio$internCollision(VoxelShape shape) {
		return ShapeDedup.intern(shape);
	}
}
