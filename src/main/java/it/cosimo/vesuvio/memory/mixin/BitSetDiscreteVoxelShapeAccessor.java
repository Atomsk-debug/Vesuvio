package it.cosimo.vesuvio.memory.mixin;

import java.util.BitSet;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.phys.shapes.BitSetDiscreteVoxelShape;

/** Read access to a voxel grid's bits, for comparing shapes structurally (ShapeDedup). */
@Mixin(BitSetDiscreteVoxelShape.class)
public interface BitSetDiscreteVoxelShapeAccessor {
	@Accessor("storage")
	BitSet vesuvio$getStorage();
}
