package it.cosimo.vesuvio.faces.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;

import it.cosimo.vesuvio.faces.FaceCulling;
import it.cosimo.vesuvio.faces.FaceGroups;
import it.cosimo.vesuvio.faces.FaceSorter;
import net.minecraft.client.renderer.chunk.SectionCompiler;

/**
 * Face culling, build side: once a section's layers are built (and the translucent one sorted), sort the
 * opaque layers' quads by facing, before anything is uploaded. Runs on the section compile threads (or the
 * render thread for a section rebuilt at once); each compile has its own buffers. While face culling is
 * off nothing is touched, as in vanilla.
 */
@Mixin(SectionCompiler.class)
abstract class SectionCompilerMixin {
	/** A ModifyReturnValue handing back the same Results: no CallbackInfoReturnable per compile. */
	@ModifyReturnValue(method = "compile", at = @At("RETURN"))
	private SectionCompiler.Results vesuvio$groupFaces(SectionCompiler.Results results) {
		if (FaceCulling.FACE_CULLING.on()) {
			((FaceGroups.Carrier) (Object) results).vesuvio$setFaceGroups(FaceSorter.groupAll(results.renderedLayers));
		}
		return results;
	}
}
