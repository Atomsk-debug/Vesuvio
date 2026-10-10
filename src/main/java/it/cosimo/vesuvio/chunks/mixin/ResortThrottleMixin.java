package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.chunks.TranslucentSort;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.world.phys.Vec3;

/**
 * LevelRenderer.scheduleResort (private, render thread): reached only where vanilla decided to re-sort; with the
 * setting on, {@link TranslucentSort} may postpone the re-sort of a far section the camera only moved a little for.
 */
@Mixin(LevelRenderer.class)
public abstract class ResortThrottleMixin {
	@WrapOperation(method = "scheduleResort", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;resortTransparency()V"))
	private void vesuvio$throttleResort(SectionRenderDispatcher.RenderSection section, Operation<Void> original,
			@Local(argsOnly = true) TranslucencyPointOfView pointOfView, @Local(argsOnly = true) Vec3 cameraPos,
			@Local(argsOnly = true, ordinal = 1) boolean isNearby) {
		if (TranslucentSort.shouldResort(section, pointOfView, cameraPos, isNearby)) {
			original.call(section);
		}
	}
}
