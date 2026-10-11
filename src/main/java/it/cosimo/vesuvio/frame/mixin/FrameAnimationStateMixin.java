package it.cosimo.vesuvio.frame.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import it.cosimo.vesuvio.frame.AnimationStateExt;
import it.cosimo.vesuvio.frame.SpriteContentsExt;
import net.minecraft.client.renderer.texture.SpriteContents;

/**
 * A hidden sprite answers "no" to needsToDraw (its frame counter still advances in tick, untouched). When it is seen
 * again, or the setting goes off, it is marked dirty so its current frame is drawn on that same tick.
 */
@Mixin(SpriteContents.AnimationState.class)
public abstract class FrameAnimationStateMixin implements AnimationStateExt {
	@Shadow
	private boolean isDirty;

	@Unique
	private @Nullable SpriteContents vesuvio$ownerSprite;
	@Unique
	private boolean vesuvio$skipped;

	@Override
	public @Nullable SpriteContents vesuvio$owner() {
		return this.vesuvio$ownerSprite;
	}

	@Override
	public void vesuvio$setOwner(SpriteContents owner) {
		this.vesuvio$ownerSprite = owner;
	}

	@Override
	public boolean vesuvio$stale() {
		return this.vesuvio$skipped;
	}

	@Override
	public void vesuvio$setStale(boolean stale) {
		this.vesuvio$skipped = stale;
	}

	@Inject(method = "tick", at = @At("TAIL"))
	private void vesuvio$resumeIfSeen(CallbackInfo ci) {
		if (AnimatedTextures.afterStateTick(this)) {
			this.isDirty = true;
		}
	}

	@ModifyReturnValue(method = "needsToDraw", at = @At("RETURN"))
	private boolean vesuvio$skipHidden(boolean original) {
		return AnimatedTextures.needsToDraw(this, original);
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void vesuvio$unlink(CallbackInfo ci) {
		if (this.vesuvio$ownerSprite != null) {
			((SpriteContentsExt) this.vesuvio$ownerSprite).vesuvio$setLive(false);
		}
	}
}
