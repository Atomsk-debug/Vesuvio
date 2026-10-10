package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;

import it.cosimo.vesuvio.chunks.ChunkUploads;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * LevelRenderer.render: the end-of-frame chunk upload is the only one the budget applies to. The other caller,
 * a synchronous build that found the staging area full, still sends everything at once as in vanilla.
 */
@Mixin(LevelRenderer.class)
public abstract class UploadBudgetMixin {
	@WrapOperation(method = "render", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;uploadTerrainBuffersToGpu()V"))
	private void vesuvio$budgetedUpload(SectionRenderDispatcher dispatcher, Operation<Void> original) {
		ChunkUploads.beginFrame();
		try {
			original.call(dispatcher);
		} finally {
			ChunkUploads.endFrame();
		}
	}
}
