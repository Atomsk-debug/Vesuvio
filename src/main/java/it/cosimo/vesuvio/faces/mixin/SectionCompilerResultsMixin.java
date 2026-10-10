package it.cosimo.vesuvio.faces.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import it.cosimo.vesuvio.faces.FaceGroups;
import net.minecraft.client.renderer.chunk.SectionCompiler;

/** Face culling: the compile's results carry the facing groups to the mesh made of them. */
@Mixin(SectionCompiler.Results.class)
abstract class SectionCompilerResultsMixin implements FaceGroups.Carrier {
	@Unique
	private int @Nullable [] @Nullable [] vesuvio$groups;

	@Override
	public int @Nullable [] @Nullable [] vesuvio$faceGroups() {
		return vesuvio$groups;
	}

	@Override
	public void vesuvio$setFaceGroups(int @Nullable [] @Nullable [] groups) {
		vesuvio$groups = groups;
	}
}
