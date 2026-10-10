package it.cosimo.vesuvio.ui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.ui.VesuvioBenchmark;
import it.cosimo.vesuvio.ui.VesuvioUi;
import net.minecraft.client.Minecraft;

/**
 * The benchmark's frame clock (start of each pass of the game loop, render thread), the master key
 * (with the other key bindings, only while no menu is open) and the stats log (end of each client tick).
 * Each is one static check when nothing is going on.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftUiMixin {
	@Inject(method = "runTick(Z)V", at = @At("HEAD"))
	private void vesuvio$frame(boolean advanceGameTime, CallbackInfo ci) {
		VesuvioBenchmark.frame();
	}

	@Inject(method = "handleKeybinds()V", at = @At("HEAD"))
	private void vesuvio$keys(CallbackInfo ci) {
		VesuvioUi.handleKeys((Minecraft) (Object) this);
	}

	@Inject(method = "tick()V", at = @At("RETURN"))
	private void vesuvio$afterTick(CallbackInfo ci) {
		VesuvioUi.afterTick((Minecraft) (Object) this);
	}
}
