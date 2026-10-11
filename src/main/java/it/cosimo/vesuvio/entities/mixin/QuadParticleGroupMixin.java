package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.culling.mixin.ParticleAccessor;
import it.cosimo.vesuvio.entities.Culling;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.particle.SingleQuadParticle;

/**
 * Vanilla already skips particles outside the view frustum here ({@code Frustum.pointInFrustum}); this adds: skip
 * particles in sections vanilla's section occlusion graph can't reach from the camera (behind walls, other caves).
 */
@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMixin {
	/** The frustum test's own arguments are {@code particle.x, particle.y, particle.z}: read back from the particle. */
	@ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean vesuvio$cullHiddenParticle(boolean inFrustum, @Local SingleQuadParticle particle) {
		if (!inFrustum) {
			return false;
		}
		ParticleAccessor pos = (ParticleAccessor) particle;
		return !Culling.particleHidden(pos.vesuvio$x(), pos.vesuvio$y(), pos.vesuvio$z());
	}
}
