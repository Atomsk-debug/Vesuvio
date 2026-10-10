package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.platform.Window;

import it.cosimo.vesuvio.frame.IdleFps;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.sprite.AtlasManager;

/** Minimized window: skip drawing (ticks, packets and sound still run every loop). */
@Mixin(Minecraft.class)
public abstract class FrameMinecraftMixin {
	@Shadow
	public abstract Window getWindow();

	@Inject(method = "runTick", at = @At("HEAD"))
	private void vesuvio$decideIdleFrame(boolean advanceGameTime, CallbackInfo ci) {
		IdleFps.beginLoop(this.getWindow());
	}

	@WrapOperation(method = "runTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/resources/model/sprite/AtlasManager;tick()V"))
	private void vesuvio$skipHiddenAnimationTick(AtlasManager atlasManager, Operation<Void> original) {
		if (!IdleFps.skipping()) {
			original.call(atlasManager);
		}
	}

	@WrapOperation(method = "runTick", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/Minecraft;renderFrame(Z)V"))
	private void vesuvio$skipHiddenFrame(Minecraft minecraft, boolean advanceGameTime, Operation<Void> original) {
		if (IdleFps.skipping()) {
			IdleFps.skipFrame();
			return;
		}
		original.call(minecraft, advanceGameTime);
		IdleFps.frameRendered();
	}
}
