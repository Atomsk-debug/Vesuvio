package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import it.cosimo.vesuvio.frame.CompiledMeshExt;
import it.cosimo.vesuvio.frame.SpriteCollectorHolder;
import it.cosimo.vesuvio.frame.SpriteSet;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.client.renderer.texture.SpriteContents;

/** The animated sprites of this mesh (null = unknown: compiled while the setting was off). */
@Mixin(CompiledSectionMesh.class)
public abstract class FrameCompiledSectionMeshMixin implements CompiledMeshExt {
	@Unique
	private SpriteContents[] vesuvio$sprites;

	@Override
	public SpriteContents[] vesuvio$animatedSprites() {
		return this.vesuvio$sprites;
	}

	@Inject(method = "<init>", at = @At("TAIL"))
	private void vesuvio$takeSprites(TranslucencyPointOfView pointOfView, SectionCompiler.Results results,
			long compileTaskStartTimeNs, CallbackInfo ci) {
		SpriteSet sprites = ((SpriteCollectorHolder) (Object) results).vesuvio$sprites();
		this.vesuvio$sprites = sprites == null ? null : sprites.toArray();
	}
}
