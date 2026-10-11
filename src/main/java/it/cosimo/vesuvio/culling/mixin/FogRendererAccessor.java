package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.renderer.fog.FogRenderer;

/** The F3 fog toggle: while it is off the shaders get the empty fog block and nothing is fogged. */
@Mixin(FogRenderer.class)
public interface FogRendererAccessor {
	@Accessor("fogEnabled")
	static boolean vesuvio$fogEnabled() {
		throw new AssertionError();
	}
}
