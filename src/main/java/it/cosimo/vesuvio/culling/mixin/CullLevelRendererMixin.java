package it.cosimo.vesuvio.culling.mixin;

import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.cosimo.vesuvio.culling.DetailCulling;
import it.cosimo.vesuvio.culling.FogCulling;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.gizmos.DrawableGizmoPrimitives;
import net.minecraft.client.renderer.state.OptionsRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;

/**
 * Fog culling, terrain side. Vanilla's own two prepare calls in render() are wrapped to mark the main view (a shader
 * mod's shadow pass prepares outside the mark and keeps every section); inside, extractSectionDrawGroups sees a
 * fully fogged section's mesh as empty, so it adds no draw for it ({@link CullSectionMeshMixin}). Also notes whether the frame draws debug gizmos
 * (no fog on them), and brackets submitFeatures for the sign text counter.
 */
@Mixin(LevelRenderer.class)
abstract class CullLevelRendererMixin {
	@Shadow
	@Final
	private LevelRenderState levelRenderState;

	@Shadow
	@Final
	private OptionsRenderState optionsRenderState;

	@Shadow
	@Final
	private GameRenderer gameRenderer;

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"))
	private ChunkSectionsToRender vesuvio$fogMainViewIndirect(LevelRenderer self, Matrix4fc modelView,
			boolean respectTranslucentOrder, Operation<ChunkSectionsToRender> original) {
		FogCulling.beginMainView(this.levelRenderState, this.gameRenderer.useImprovedTransparency(),
				this.optionsRenderState.cloudStatus);
		try {
			return original.call(self, modelView, respectTranslucentOrder);
		} finally {
			FogCulling.endMainView();
		}
	}

	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"))
	private ChunkSectionsToRender vesuvio$fogMainView(LevelRenderer self, Matrix4fc modelView,
			boolean respectTranslucentOrder, Operation<ChunkSectionsToRender> original) {
		FogCulling.beginMainView(this.levelRenderState, this.gameRenderer.useImprovedTransparency(),
				this.optionsRenderState.cloudStatus);
		try {
			return original.call(self, modelView, respectTranslucentOrder);
		} finally {
			FogCulling.endMainView();
		}
	}

	@Inject(method = "submitFeatures", at = @At("HEAD"))
	private void vesuvio$beginSubmit(LevelRenderState state, SubmitNodeCollector collector, boolean renderOutline,
			CallbackInfo ci) {
		DetailCulling.beginSubmit();
	}

	@Inject(method = "submitFeatures", at = @At("RETURN"))
	private void vesuvio$endSubmit(LevelRenderState state, SubmitNodeCollector collector, boolean renderOutline,
			CallbackInfo ci) {
		DetailCulling.endSubmit();
	}

	/** {@code this.finalizedGizmos.standardPrimitives().submit(...)}: depth-tested lines and boxes, some unfogged. */
	@WrapOperation(method = "submitFeatures", at = @At(value = "INVOKE", ordinal = 0,
			target = "Lnet/minecraft/client/renderer/gizmos/DrawableGizmoPrimitives;submit(Lnet/minecraft/client/renderer/SubmitNodeCollector;Lnet/minecraft/client/renderer/state/level/CameraRenderState;Z)V"))
	private void vesuvio$noteGizmos(DrawableGizmoPrimitives primitives, SubmitNodeCollector collector,
			CameraRenderState camera, boolean onTop, Operation<Void> original) {
		FogCulling.noteGizmos(!primitives.isEmpty());
		original.call(primitives, collector, camera, onTop);
	}
}
