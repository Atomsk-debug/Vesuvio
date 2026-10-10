package it.cosimo.vesuvio.faces.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.mojang.blaze3d.vertex.VertexSorting;

import it.cosimo.vesuvio.faces.FaceCulling;
import it.cosimo.vesuvio.faces.FaceGroups;
import it.cosimo.vesuvio.faces.FaceSorter;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.core.SectionPos;

/**
 * Face culling, build side: once a section's layers are built (and the translucent one sorted), sort the
 * opaque layers' quads by facing, before anything is uploaded. Runs on the section compile threads (or the
 * render thread for a section rebuilt at once); each compile has its own buffers. While face culling is
 * off nothing is touched, as in vanilla.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
	@Inject(method = "compile", at = @At("RETURN"))
	private void vesuvio$groupFaces(SectionPos sectionPos, RenderSectionRegion region, VertexSorting vertexSorting,
			SectionBufferBuilderPack builders, CallbackInfoReturnable<SectionCompiler.Results> cir) {
		if (FaceCulling.FACE_CULLING.on()) {
			SectionCompiler.Results results = cir.getReturnValue();
			((FaceGroups.Carrier) (Object) results).vesuvio$setFaceGroups(FaceSorter.groupAll(results.renderedLayers));
		}
	}
}
