package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.cosimo.vesuvio.entities.Culling;
import net.minecraft.client.particle.QuadParticleGroup;
import net.minecraft.client.renderer.culling.Frustum;

/**
 * Vanilla already skips particles outside the view frustum here ({@code Frustum.pointInFrustum}); this adds: skip
 * particles in sections vanilla's section occlusion graph can't reach from the camera (behind walls, other caves).
 */
@Mixin(QuadParticleGroup.class)
public abstract class QuadParticleGroupMixin {
	@WrapOperation(method = "extractRenderState", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/culling/Frustum;pointInFrustum(DDD)Z"))
	private boolean vesuvio$cullHiddenParticle(Frustum frustum, double x, double y, double z, Operation<Boolean> original) {
		return original.call(frustum, x, y, z) && !Culling.particleHidden(x, y, z);
	}
}
