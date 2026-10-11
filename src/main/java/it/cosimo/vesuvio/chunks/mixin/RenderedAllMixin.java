package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import it.cosimo.vesuvio.chunks.ChunkScheduler;
import net.minecraft.client.renderer.LevelRenderer;

/**
 * LevelRenderer.hasRenderedAllSections (used for the world-list screenshot): with the scheduler, an empty compile
 * queue no longer means nothing is pending, as dirty sections in view may still wait for their turn.
 */
@Mixin(LevelRenderer.class)
public abstract class RenderedAllMixin {
	@ModifyReturnValue(method = "hasRenderedAllSections", at = @At("RETURN"))
	private boolean vesuvio$includeDeferred(boolean original) {
		return original && ChunkScheduler.nothingDeferred();
	}
}
