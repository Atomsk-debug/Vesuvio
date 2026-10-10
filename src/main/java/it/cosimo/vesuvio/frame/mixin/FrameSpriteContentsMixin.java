package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.frame.AnimationStateExt;
import it.cosimo.vesuvio.frame.SpriteContentsExt;
import net.minecraft.client.renderer.texture.SpriteContents;

/** Remembers when a sprite was last seen, and links each animation state to its sprite. */
@Mixin(SpriteContents.class)
public abstract class FrameSpriteContentsMixin implements SpriteContentsExt {
	@Unique
	private int vesuvio$seenTick = -1_000_000;
	@Unique
	private boolean vesuvio$isLive; // render thread only (atlas upload, close, frame marking)

	@Override
	public int vesuvio$seen() {
		return this.vesuvio$seenTick;
	}

	@Override
	public void vesuvio$setSeen(int tick) {
		this.vesuvio$seenTick = tick;
	}

	@Override
	public boolean vesuvio$live() {
		return this.vesuvio$isLive;
	}

	@Override
	public void vesuvio$setLive(boolean live) {
		this.vesuvio$isLive = live;
	}

	@Inject(method = "createAnimationState", at = @At("RETURN"))
	private void vesuvio$linkState(CallbackInfoReturnable<SpriteContents.AnimationState> cir) {
		Object state = cir.getReturnValue();
		if (state != null) {
			((AnimationStateExt) state).vesuvio$setOwner((SpriteContents) (Object) this);
			this.vesuvio$isLive = true;
		}
	}
}
