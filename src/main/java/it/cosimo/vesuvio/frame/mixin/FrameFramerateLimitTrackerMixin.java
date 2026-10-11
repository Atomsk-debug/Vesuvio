package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.platform.FramerateLimitTracker;

import it.cosimo.vesuvio.frame.IdleFps;
import net.minecraft.client.Minecraft;

/** Unfocused or hidden window, single player paused behind a menu: cap the frame rate. */
@Mixin(FramerateLimitTracker.class)
public abstract class FrameFramerateLimitTrackerMixin {
	@Shadow
	@Final
	private Minecraft minecraft;

	@ModifyReturnValue(method = "getFramerateLimit", at = @At("RETURN"))
	private int vesuvio$idleLimit(int vanilla) {
		return IdleFps.limit(vanilla, this.minecraft);
	}
}
