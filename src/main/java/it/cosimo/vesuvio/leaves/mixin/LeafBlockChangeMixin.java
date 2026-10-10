package it.cosimo.vesuvio.leaves.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.leaves.LeafCulling;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * ClientLevel.sendBlockUpdated: vanilla rebuilds the sections within one block of a changed block; leaf culling
 * looks up to three blocks ahead, so {@link LeafCulling#blockChanged} adds the few sections that margin misses.
 * Does nothing while leaf culling is off.
 */
@Mixin(ClientLevel.class)
public abstract class LeafBlockChangeMixin {
	@Inject(method = "sendBlockUpdated", at = @At("TAIL"))
	private void vesuvio$rebuildLeafLookAhead(BlockPos pos, BlockState old, BlockState current, int updateFlags,
			CallbackInfo ci) {
		LeafCulling.blockChanged((ClientLevel) (Object) this, pos, old, current);
	}
}
