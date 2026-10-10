package it.cosimo.vesuvio.ui.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.InputConstants;
import it.cosimo.vesuvio.ui.VesuvioBenchmark;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;

/** A mouse button pressed stops a running benchmark, like a key. */
@Mixin(MouseHandler.class)
public abstract class MouseUiMixin {
	@Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
	private void vesuvio$button(long handle, MouseButtonInfo button, int action, CallbackInfo ci) {
		if (action == InputConstants.PRESS) {
			VesuvioBenchmark.input();
		}
	}
}
