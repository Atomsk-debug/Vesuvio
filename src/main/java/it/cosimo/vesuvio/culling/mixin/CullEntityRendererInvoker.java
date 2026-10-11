package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** The box vanilla frustum-culls an entity with, and whether the renderer allows culling at all (fog culling). */
@Mixin(EntityRenderer.class)
public interface CullEntityRendererInvoker {
	@Invoker("getBoundingBoxForCulling")
	AABB vesuvio$getBoundingBoxForCulling(Entity entity, float partialTicks);

	@Invoker("affectedByCulling")
	boolean vesuvio$affectedByCulling(Entity entity);
}
