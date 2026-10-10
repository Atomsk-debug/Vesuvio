package it.cosimo.vesuvio.leaves.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.leaves.LeafCulling;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.extract.LevelExtractor;

/**
 * LevelExtractor.extract (render thread, once a frame, before the sections are scheduled): applies a change of
 * the leaf culling setting or of the master switch by rebuilding every loaded section ({@code allChanged}, as
 * vanilla does for graphics options). One int compare per frame otherwise.
 */
@Mixin(LevelExtractor.class)
public abstract class LeafSettingPollMixin {
	@Inject(method = "extract", at = @At("HEAD"))
	private void vesuvio$applyLeafCulling(DeltaTracker deltaTracker, Camera camera, float worldPartialTicks,
			CallbackInfo ci) {
		LeafCulling.poll();
	}
}
