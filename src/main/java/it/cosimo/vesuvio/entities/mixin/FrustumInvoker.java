package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.culling.Frustum;

/** Frustum test of a box without allocating an AABB (-2 inside, -1 intersecting, anything else outside). */
@Mixin(Frustum.class)
public interface FrustumInvoker {
	@Invoker("cubeInFrustum")
	int vesuvio$cubeInFrustum(double minX, double minY, double minZ, double maxX, double maxY, double maxZ);
}
