package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.chunks.ChunkScheduler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.SectionUpdateTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * LevelExtractor.extract, "sectionUpdates" step (render thread): the list vanilla's loop walks to find dirty sections
 * to rebuild is fetched with the one visibleSections() call of this method. {@link ChunkScheduler} does that loop's
 * work itself (same conditions, same SectionUpdateRenderState entries) and hands vanilla an empty list; with both
 * settings off it hands back the real list and vanilla's loop runs unchanged.
 */
@Mixin(LevelExtractor.class)
public abstract class ExtractSchedulingMixin {
	@Shadow
	private ClientLevel level;
	@Shadow
	private SectionUpdateTracker sectionUpdateTracker;
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	@Final
	private LevelRenderer levelRenderer;

	@ModifyExpressionValue(method = "extract", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;visibleSections()Lit/unimi/dsi/fastutil/objects/ObjectArrayList;"))
	private ObjectArrayList<SectionRenderDispatcher.RenderSection> vesuvio$scheduleSections(
			ObjectArrayList<SectionRenderDispatcher.RenderSection> visible, @Local(argsOnly = true) Camera camera,
			@Local RenderRegionCache cache) {
		return ChunkScheduler.schedule(visible, levelRenderer, level, sectionUpdateTracker, levelRenderState,
				camera.position(), cache);
	}
}
