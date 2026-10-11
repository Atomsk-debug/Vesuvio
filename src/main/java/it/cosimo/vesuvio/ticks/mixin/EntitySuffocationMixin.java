package it.cosimo.vesuvio.ticks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.ticks.TickStats;
import it.cosimo.vesuvio.ticks.WorldTicks;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Setting worldFastSuffocationCheck: Entity.isInWall runs for every living entity every server tick
 * (LivingEntity.baseTick). Vanilla builds a Stream over BlockPos.betweenClosed(eye box) and anyMatch-es a
 * capturing lambda. This is the same test with plain loops: same box (same AABB.ofSize call), same blocks in the
 * same order (betweenClosed: x fastest, then y, then z), same per-block test, stopping at the first hit. The
 * per-block test only reads the world (getBlockState, isSuffocating, collision shape), so nothing else changes.
 */
@Mixin(Entity.class)
abstract class EntitySuffocationMixin {
	@Shadow
	public boolean noPhysics;

	@Shadow
	private EntityDimensions dimensions;

	@Shadow
	public abstract Level level();

	/**
	 * Kept tiny (the loop is in {@link #vesuvio$inWall}) so the JIT inlines it into isInWall and can drop the
	 * CallbackInfoReturnable it would otherwise allocate per call.
	 */
	@Inject(method = "isInWall", at = @At("HEAD"), cancellable = true)
	private void vesuvio$loopInWall(CallbackInfoReturnable<Boolean> cir) {
		if (WorldTicks.SUFFOCATION.on() && !this.noPhysics) {
			cir.setReturnValue(this.vesuvio$inWall());
		}
	}

	@Unique
	private boolean vesuvio$inWall() {
		TickStats.suffocationChecks++;
		float checkWidth = this.dimensions.width() * 0.8F;
		AABB eyeBb = AABB.ofSize(((Entity) (Object) this).getEyePosition(), checkWidth, 1.0E-6, checkWidth);
		int minX = Mth.floor(eyeBb.minX);
		int minY = Mth.floor(eyeBb.minY);
		int minZ = Mth.floor(eyeBb.minZ);
		int maxX = Mth.floor(eyeBb.maxX);
		int maxY = Mth.floor(eyeBb.maxY);
		int maxZ = Mth.floor(eyeBb.maxZ);
		BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
		VoxelShape eyeShape = null;
		for (int z = minZ; z <= maxZ; z++) {
			for (int y = minY; y <= maxY; y++) {
				for (int x = minX; x <= maxX; x++) {
					pos.set(x, y, z);
					Level level = this.level();
					BlockState state = level.getBlockState(pos);
					if (!state.isAir() && state.isSuffocating(level, pos)) {
						if (eyeShape == null) {
							eyeShape = Shapes.create(eyeBb);
						}
						if (Shapes.joinIsNotEmpty(state.getCollisionShape(level, pos).move(pos), eyeShape, BooleanOp.AND)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
