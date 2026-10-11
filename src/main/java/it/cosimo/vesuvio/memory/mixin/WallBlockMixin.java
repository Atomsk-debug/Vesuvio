package it.cosimo.vesuvio.memory.mixin;

import java.util.List;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;

import it.cosimo.vesuvio.memory.ShapeDedup;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WallBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareBlockShapes: WallBlock.makeShapes(postHeight, wallTop) gives the same shapes for every wall
 * (they depend only on the two numbers and the state's properties), so the second wall onwards reuses the first
 * wall's shapes instead of running 324 shape unions again (ShapeDedup.sharedTable).
 */
@Mixin(WallBlock.class)
abstract class WallBlockMixin {
	@WrapMethod(method = "makeShapes(FF)Ljava/util/function/Function;")
	private Function<BlockState, VoxelShape> vesuvio$shareShapes(float postHeight, float wallTop,
			Operation<Function<BlockState, VoxelShape>> original) {
		return ShapeDedup.sharedTable((Block) (Object) this, List.of(postHeight, wallTop),
				() -> original.call(postHeight, wallTop));
	}
}
