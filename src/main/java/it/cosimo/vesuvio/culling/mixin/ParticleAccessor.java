package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.particle.Particle;

/** A particle's position last tick and now: it is drawn between the two. */
@Mixin(Particle.class)
public interface ParticleAccessor {
	@Accessor("xo")
	double vesuvio$xo();

	@Accessor("yo")
	double vesuvio$yo();

	@Accessor("zo")
	double vesuvio$zo();

	@Accessor("x")
	double vesuvio$x();

	@Accessor("y")
	double vesuvio$y();

	@Accessor("z")
	double vesuvio$z();
}
