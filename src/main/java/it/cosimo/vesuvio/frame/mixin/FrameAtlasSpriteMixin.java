package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.systems.RenderSystem;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;

/**
 * Anything that draws a sprite from its UVs (particles, entity fire, screen overlays, block entity textures through
 * SpriteCoordinateExpander, mods) asks for them here on the render thread: that counts as seen. One field compare
 * (and at most one write per texture tick) per call.
 */
@Mixin(TextureAtlasSprite.class)
public abstract class FrameAtlasSpriteMixin {
	@Shadow
	@Final
	private SpriteContents contents;

	@ModifyReturnValue(method = { "getU0", "getU1", "getV0", "getV1", "getU", "getV" }, at = @At("RETURN"))
	private float vesuvio$markSeen(float uv) {
		// Render thread only: compile workers read fluid UVs here too, and terrain is tracked per section instead.
		if (AnimatedTextures.active && RenderSystem.isOnRenderThread()) {
			AnimatedTextures.mark(this.contents);
		}
		return uv;
	}
}
