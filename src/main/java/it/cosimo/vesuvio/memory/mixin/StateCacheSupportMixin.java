package it.cosimo.vesuvio.memory.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.memory.StateTableDedup;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Setting memoryShareStateTables: the face-support table of BlockBehaviour.BlockStateBase.Cache (built once per
 * state at start-up, read-only afterwards) is shared between states with equal tables.
 */
@Mixin(targets = "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase$Cache")
abstract class StateCacheSupportMixin {
	@Shadow
	@Final
	@Mutable
	private boolean[] faceSturdy;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void vesuvio$shareSupport(BlockState state, CallbackInfo ci) {
		this.faceSturdy = StateTableDedup.support(this.faceSturdy);
	}
}
