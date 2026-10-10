package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.phys.shapes.DiscreteVoxelShape;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Read access to a shape's voxel grid, for comparing shapes structurally (ShapeDedup). */
@Mixin(VoxelShape.class)
public interface VoxelShapeAccessor {
	@Accessor("shape")
	DiscreteVoxelShape vesuvio$getShape();
}
