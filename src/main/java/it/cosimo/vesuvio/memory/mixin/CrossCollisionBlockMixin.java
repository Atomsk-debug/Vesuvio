package it.cosimo.vesuvio.memory.mixin;

import java.util.List;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.cosimo.vesuvio.memory.ShapeDedup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CrossCollisionBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareBlockShapes: CrossCollisionBlock.makeShapes (fences, glass panes, iron/copper bars) gives the
 * same shapes for every block of one class built with the same numbers, so later blocks reuse the first one's
 * (ShapeDedup.sharedTable, which also checks the class is vanilla's).
 */
@Mixin(CrossCollisionBlock.class)
abstract class CrossCollisionBlockMixin {
	@WrapMethod(method = "makeShapes(FFFFF)Ljava/util/function/Function;")
	private Function<BlockState, VoxelShape> vesuvio$shareShapes(float postWidth, float postHeight, float wallWidth,
			float wallBottom, float wallTop, Operation<Function<BlockState, VoxelShape>> original) {
		return ShapeDedup.sharedTable((Block) (Object) this, List.of(postWidth, postHeight, wallWidth, wallBottom, wallTop),
				() -> original.call(postWidth, postHeight, wallWidth, wallBottom, wallTop));
	}
}
