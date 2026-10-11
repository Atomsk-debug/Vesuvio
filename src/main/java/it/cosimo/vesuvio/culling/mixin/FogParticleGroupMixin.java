package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.culling.FogCulling;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;

/**
 * Fog culling of particles, after vanilla's own frustum test of each one ({@code frustum.pointInFrustum(particle.x,
 * particle.y, particle.z)}): a particle it lets through can still be dropped. The test reads the same position from
 * the particle. A plain value hook: nothing is allocated per particle.
 *
 * <p>Priority 999: particle occlusion culling (entities.mixin.QuadParticleGroupMixin, priority 1000) narrows the same
 * answer. Of two value hooks on one call, the one applied later sees vanilla's value first, so this one is applied
 * first and its test runs second: occlusion, then fog, the order the game ran them in when both were wrapping hooks.
 */
@Mixin(value = QuadParticleGroup.class, priority = 999)
abstract class FogParticleGroupMixin {
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean vesuvio$fogCullParticle(boolean inFrustum, @Local SingleQuadParticle particle,
			@Local(argsOnly = true) float partialTickTime) {
		return inFrustum && !FogCulling.particleHidden(particle, partialTickTime);
	}
}
