package it.cosimo.vesuvio.entities.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/** The box vanilla frustum-culls an entity with (renderer-specific: minecarts, tridents, sniffers...). */
@Mixin(EntityRenderer.class)
public interface EntityRendererInvoker {
	@Invoker("getBoundingBoxForCulling")
	AABB vesuvio$getBoundingBoxForCulling(Entity entity, float partialTicks);

	@Invoker("affectedByCulling")
	boolean vesuvio$affectedByCulling(Entity entity);
}
