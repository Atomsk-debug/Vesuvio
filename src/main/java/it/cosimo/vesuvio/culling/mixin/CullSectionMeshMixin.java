package it.cosimo.vesuvio.culling.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.culling.FogCulling;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * Fog culling, terrain side: inside the main view's prepare (marked by {@link CullLevelRendererMixin}),
 * extractSectionDrawGroups sees a fully fogged section's mesh ({@code section.getSectionMesh()}, a plain read of the
 * section's mesh reference) as empty, so it adds no draw for it. A value hook: nothing is allocated per section.
 *
 * <p>Priority 1001: the animated texture tracking (frame.mixin.FrameLevelRendererMixin, priority 1000) reads the same
 * value to mark the section's sprites as seen. Of two value hooks on one call, the one applied later sees vanilla's
 * value first, so this one is applied last: a fogged section reaches that hook already empty and marks nothing, as it
 * did when this was a wrapping hook (applied after every value hook, so innermost).
 */
@Mixin(value = LevelRenderer.class, priority = 1001)
abstract class CullSectionMeshMixin {
	@ModifyExpressionValue(method = "extractSectionDrawGroups", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher$RenderSection;getSectionMesh()Lnet/minecraft/client/renderer/chunk/SectionMesh;"))
	private SectionMesh vesuvio$fogCullSection(SectionMesh mesh, @Local SectionRenderDispatcher.RenderSection section) {
		return FogCulling.sectionHidden(section) ? CompiledSectionMesh.EMPTY : mesh;
	}
}
