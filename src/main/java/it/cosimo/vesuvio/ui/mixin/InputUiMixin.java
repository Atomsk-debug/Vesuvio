package it.cosimo.vesuvio.ui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.InputConstants;
import it.cosimo.vesuvio.ui.VesuvioBenchmark;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;

/**
 * Any key pressed stops a running benchmark, before the key does anything else (the master key, Escape's
 * pause menu), so the key acts on the player's own settings.
 */
@Mixin(KeyboardHandler.class)
public abstract class InputUiMixin {
	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
	private void vesuvio$key(long handle, int action, KeyEvent event, CallbackInfo ci) {
		if (action == InputConstants.PRESS) {
			VesuvioBenchmark.input();
		}
	}
}
