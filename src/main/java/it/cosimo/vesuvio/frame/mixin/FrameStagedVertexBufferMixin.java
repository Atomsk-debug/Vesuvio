package it.cosimo.vesuvio.frame.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;

import it.cosimo.vesuvio.frame.MappedVertexPools;
import net.minecraft.client.renderer.StagedVertexBuffer;

/**
 * Setting mappedVertexPools ({@link MappedVertexPools}): when the vertex (and index) buffer just acquired can be
 * mapped, the upload writes into it directly instead of through the staging buffer and copyToBuffer.
 */
@Mixin(StagedVertexBuffer.class)
public abstract class FrameStagedVertexBufferMixin {
	@Inject(method = "uploadDrawsToBuffers", at = @At("HEAD"), cancellable = true)
	private void vesuvio$writeDirect(GpuDevice device, List<StagedVertexBuffer.Draw> draws, GpuBuffer vertexGpuBuffer,
			GpuBuffer indexGpuBuffer, int vertexBufferSize, int indexBufferSize, CallbackInfo ci) {
		if (MappedVertexPools.writeDirect(draws, vertexGpuBuffer, indexGpuBuffer, vertexBufferSize, indexBufferSize)) {
			ci.cancel();
		}
	}
}
