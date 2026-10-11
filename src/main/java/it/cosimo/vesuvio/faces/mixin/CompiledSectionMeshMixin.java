package it.cosimo.vesuvio.faces.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.faces.FaceGroups;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;

/**
 * Face culling: each section mesh keeps where its facing groups start (a few ints per opaque layer). Set in
 * the constructor on the compile thread; the render thread reads it after the mesh is published through
 * RenderSection.sectionMesh (an AtomicReference), which makes it visible there.
 */
@Mixin(CompiledSectionMesh.class)
abstract class CompiledSectionMeshMixin implements FaceGroups {
	@Unique
	private int @Nullable [] @Nullable [] vesuvio$groups;
	@Unique
	private boolean vesuvio$regroup;

	@Inject(method = "<init>", at = @At("TAIL"))
	private void vesuvio$keepFaceGroups(TranslucencyPointOfView translucencyPointOfView, SectionCompiler.Results results,
			long compileTaskStartTimeNs, CallbackInfo ci) {
		vesuvio$groups = ((FaceGroups.Carrier) (Object) results).vesuvio$faceGroups();
	}

	@Override
	public int @Nullable [] @Nullable [] vesuvio$faceGroups() {
		return vesuvio$groups;
	}

	@Override
	public boolean vesuvio$regroupAsked() {
		return vesuvio$regroup;
	}

	@Override
	public void vesuvio$setRegroupAsked() {
		vesuvio$regroup = true;
	}
}
