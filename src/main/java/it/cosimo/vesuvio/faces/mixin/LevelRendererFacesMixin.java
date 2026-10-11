package it.cosimo.vesuvio.faces.mixin;

import java.util.List;

import org.joml.Matrix4fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import it.cosimo.vesuvio.faces.FaceCulling;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.ChunkSectionsToRender;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.BlockPos;

/**
 * Face culling, draw side. Vanilla's own two prepare calls in render() are wrapped to mark them as the
 * main view (Etna's shadow pass, injected just before those calls, runs outside the mark and keeps whole
 * meshes). In extractSectionDrawGroups, the one place both prepare paths turn visible sections into
 * IndexedDraws, each opaque draw is replaced by the draws of the facing groups that can be seen.
 */
@Mixin(LevelRenderer.class)
abstract class LevelRendererFacesMixin {
	@WrapOperation(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRendersIndirect(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
		)
	)
	private ChunkSectionsToRender vesuvio$mainViewIndirect(LevelRenderer self, Matrix4fc modelView, boolean respectTranslucentOrder,
			Operation<ChunkSectionsToRender> original, @Local(argsOnly = true) CameraRenderState camera) {
		FaceCulling.beginMainView(camera.pos);
		try {
			return original.call(self, modelView, respectTranslucentOrder);
		} finally {
			FaceCulling.endMainView();
		}
	}

	@WrapOperation(
		method = "render",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareChunkRenders(Lorg/joml/Matrix4fc;Z)Lnet/minecraft/client/renderer/chunk/ChunkSectionsToRender;"
		)
	)
	private ChunkSectionsToRender vesuvio$mainView(LevelRenderer self, Matrix4fc modelView, boolean respectTranslucentOrder,
			Operation<ChunkSectionsToRender> original, @Local(argsOnly = true) CameraRenderState camera) {
		FaceCulling.beginMainView(camera.pos);
		try {
			return original.call(self, modelView, respectTranslucentOrder);
		} finally {
			FaceCulling.endMainView();
		}
	}

	@Inject(method = "extractSectionDrawGroups", at = @At("HEAD"))
	private void vesuvio$beginPrepare(boolean respectTranslucentOrder, List<?> sectionInfos, java.util.Map<?, ?> drawGroups,
			CallbackInfoReturnable<Integer> cir) {
		FaceCulling.beginPrepare();
	}

	@Inject(method = "extractSectionDrawGroups", at = @At("RETURN"))
	private void vesuvio$endPrepare(boolean respectTranslucentOrder, List<?> sectionInfos, java.util.Map<?, ?> drawGroups,
			CallbackInfoReturnable<Integer> cir) {
		FaceCulling.endPrepare();
	}

	/**
	 * {@code drawGroup.draws.add(new DynamicGpuData.IndexedDraw(...))}: the third List.add in the method, whose
	 * result vanilla drops. A WrapWithCondition: vanilla's add runs when the draw stays whole, else FaceCulling has
	 * added the cut draws itself. Per section and layer, so no Operation or local wrappers are allocated here.
	 */
	@WrapWithCondition(
		method = "extractSectionDrawGroups",
		at = @At(value = "INVOKE", target = "Ljava/util/List;add(Ljava/lang/Object;)Z", ordinal = 2)
	)
	private boolean vesuvio$cutByFacing(List<?> draws, Object draw, @Local SectionMesh sectionMesh,
			@Local ChunkSectionLayer layer, @Local BlockPos renderOffset) {
		return FaceCulling.keepWhole(draws, draw, sectionMesh, layer, renderOffset);
	}
}
