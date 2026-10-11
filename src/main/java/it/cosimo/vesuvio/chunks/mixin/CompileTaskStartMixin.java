package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import it.cosimo.vesuvio.chunks.MeshOrder;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;

/**
 * SectionRenderDispatcher.RenderSection.CompileTask.doTask (worker thread, or the render thread for a synchronous
 * build): tells {@link MeshOrder} this task's start time, which its result carries only when it has geometry.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection$CompileTask")
public abstract class CompileTaskStartMixin {
	@Shadow
	@Final
	private long startTimeNs;

	@Inject(method = "doTask", at = @At("HEAD"))
	private void vesuvio$noteStart(SectionBufferBuilderPack buffers,
			CallbackInfoReturnable<SectionRenderDispatcher.RenderSection.SectionTask.SectionTaskResult> cir) {
		MeshOrder.taskStarted(startTimeNs);
	}
}
