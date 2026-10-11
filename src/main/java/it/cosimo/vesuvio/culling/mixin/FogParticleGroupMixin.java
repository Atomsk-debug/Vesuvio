package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.culling.FogCulling;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.client.renderer.culling.Frustum;

/** Fog culling of particles, after vanilla's own frustum test of each one. */
@Mixin(QuadParticleGroup.class)
abstract class FogParticleGroupMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean vesuvio$fogCullParticle(Frustum frustum, double x, double y, double z, Operation<Boolean> original,
			@Local SingleQuadParticle particle, @Local(argsOnly = true) float partialTickTime) {
		return original.call(frustum, x, y, z) && !FogCulling.particleHidden(particle, x, y, z, partialTickTime);
	}
}
