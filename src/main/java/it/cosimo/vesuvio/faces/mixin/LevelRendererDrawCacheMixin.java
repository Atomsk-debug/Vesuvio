package it.cosimo.vesuvio.faces.mixin;

import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.cosimo.vesuvio.faces.SectionDrawCache;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

/**
 * Setting sectionDrawCache ({@link SectionDrawCache}): the extractSectionDrawGroups call of both prepare methods
 * goes through the cache, which serves vanilla's main-view call (marked by LevelRendererFacesMixin) from the last
 * build when nothing it depends on changed, and runs the call as usual otherwise.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererDrawCacheMixin {
	@Shadow
	@Final
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

	@Shadow
	private @Nullable SectionRenderDispatcher sectionRenderDispatcher;

	@Shadow
	@Final
	private OptionsRenderState optionsRenderState;

	@WrapOperation(
		method = {"prepareChunkRendersIndirect", "prepareChunkRenders"},
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;extractSectionDrawGroups(ZLjava/util/List;Ljava/util/Map;)I"
		),
		require = 2
	)
	private int vesuvio$cachedDrawGroups(LevelRenderer self, boolean respectTranslucentOrder, List<?> sectionInfos,
			Map<?, ?> drawGroups, Operation<Integer> original) {
		return SectionDrawCache.extract(this.sectionRenderDispatcher, this.visibleSections,
				Util.toMillis(this.optionsRenderState.chunkSectionFadeInTime), respectTranslucentOrder, sectionInfos,
				drawGroups, () -> original.call(self, respectTranslucentOrder, sectionInfos, drawGroups));
	}
}
