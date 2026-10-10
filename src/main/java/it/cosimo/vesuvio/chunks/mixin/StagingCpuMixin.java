package it.cosimo.vesuvio.chunks.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;

import it.cosimo.vesuvio.chunks.ChunkStaging;
import it.cosimo.vesuvio.chunks.ChunkStagingHost;

/**
 * StagingBuffer.Cpu (vanilla's chunk staging area on Vulkan), setting mappedChunkStaging ({@link ChunkStaging}):
 * while its mapped stand-in is in use, the workers write into the mapped buffer and the upload is one copyToBuffer
 * from it instead of writeToBuffer (a second memcpy into transient memory, then the same GPU copy). Without a
 * stand-in, or while it is unmapped, every method is vanilla.
 */
@Mixin(targets = "com.mojang.blaze3d.vertex.StagingBuffer$Cpu")
public abstract class StagingCpuMixin implements ChunkStagingHost {
	@Unique
	private ChunkStaging.Area vesuvio$area;

	@Override
	public ChunkStaging.Area vesuvio$stagingArea() {
		return this.vesuvio$area;
	}

	@Override
	public void vesuvio$setStagingArea(ChunkStaging.Area area) {
		this.vesuvio$area = area;
	}

	@ModifyReturnValue(method = "getWriteBuffer", at = @At("RETURN"))
	private ByteBuffer vesuvio$mappedWriteBuffer(ByteBuffer original) {
		ChunkStaging.Area area = this.vesuvio$area;
		ByteBuffer mapped = area != null ? area.data() : null;
		return mapped != null ? mapped : original;
	}

	@Inject(method = "copyTo", at = @At("HEAD"), cancellable = true)
	private void vesuvio$copyFromMapped(CommandEncoder encoder, GpuBuffer dstBuffer, long dstOffset,
			long stagingBufferOffset, long copySize, CallbackInfo ci) {
		ChunkStaging.Area area = this.vesuvio$area;
		if (area != null && area.mapped()) {
			area.copy(encoder, dstBuffer, dstOffset, stagingBufferOffset, copySize);
			ci.cancel();
		}
	}

	@Inject(method = "close", at = @At("HEAD"))
	private void vesuvio$closeMapped(CallbackInfo ci) {
		if (this.vesuvio$area != null) {
			this.vesuvio$area.close();
			this.vesuvio$area = null;
		}
	}
}
