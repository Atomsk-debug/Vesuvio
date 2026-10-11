package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;

import it.cosimo.vesuvio.frame.AnimatedTextures;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionMesh;

/**
 * Each section prepared for drawing marks its animated sprites as seen. Both prepareChunkRenders variants go
 * through extractSectionDrawGroups, so Etna's shadow casters count too. Nothing about the drawing changes.
 */
@Mixin(LevelRenderer.class)
public abstract class FrameLevelRendererMixin {
	@ModifyExpressionValue(method = "extractSectionDrawGroups", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getSectionMesh()Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
	private SectionMesh vesuvio$markSectionSprites(SectionMesh mesh) {
		AnimatedTextures.markMesh(mesh);
		return mesh;
	}
}
