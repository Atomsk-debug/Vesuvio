package it.cosimo.vesuvio.memory.mixin;

import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import it.cosimo.vesuvio.memory.ShapeDedup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareBlockShapes: Block.getShapeForEachState (both versions) is where walls, fences, panes, fire,
 * redstone wire, vines, buttons, levers... build a shape per state when the block is made; their results go
 * through ShapeDedup.intern so equal shapes are one object.
 */
@Mixin(Block.class)
abstract class BlockShapeTableMixin {
	@ModifyVariable(method = {
			"getShapeForEachState(Ljava/util/function/Function;)Ljava/util/function/Function;",
			"getShapeForEachState(Ljava/util/function/Function;[Lnet/minecraft/world/level/block/state/properties/Property;)Ljava/util/function/Function;"},
			at = @At("HEAD"), argsOnly = true, require = 2)
	private Function<BlockState, VoxelShape> vesuvio$internResults(Function<BlockState, VoxelShape> shapeCalculator) {
		return ShapeDedup.interning(shapeCalculator);
	}
}
