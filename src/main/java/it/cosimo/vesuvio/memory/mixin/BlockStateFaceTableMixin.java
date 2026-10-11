package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.memory.StateTableDedup;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting memoryShareStateTables: the per-face occlusion shape table BlockStateBase.initCache builds for partly
 * occluding states (stairs, slabs, walls...) is shared between states whose six face shapes are the same objects.
 * Vanilla already shares the table for empty and full blocks.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
abstract class BlockStateFaceTableMixin {
	@Shadow
	private VoxelShape[] occlusionShapesByFace;

	@Inject(method = "initCache", at = @At("RETURN"))
	private void vesuvio$shareFaces(CallbackInfo ci) {
		this.occlusionShapesByFace = StateTableDedup.faces(this.occlusionShapesByFace);
	}
}
