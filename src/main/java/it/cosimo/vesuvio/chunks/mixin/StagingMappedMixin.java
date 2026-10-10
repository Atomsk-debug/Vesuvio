package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.blaze3d.vertex.StagingBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.device.GpuDevice;

import it.cosimo.vesuvio.chunks.ChunkStaging;
import it.cosimo.vesuvio.chunks.ChunkStagingHost;

/**
 * StagingBuffer, setting mappedChunkStaging ({@link ChunkStaging}): gives the chunk staging area a mapped stand-in
 * when it is created, and lets the stand-in follow the setting only where nothing is staged: at vanilla's
 * rotateBuffer point (everything sent, the area starts over) and at the start of an upload with nothing written.
 * Staging areas other than the game's "Chunk" one, and vanilla's own mapped variant, are left alone.
 */
@Mixin(StagingBuffer.class)
public abstract class StagingMappedMixin {
	@Shadow
	private int nextWriteOffset;

	@ModifyReturnValue(method = "create", at = @At("RETURN"))
	private static StagingBuffer vesuvio$attachMappedArea(StagingBuffer staging, String name, GpuDevice device,
			int bufferSize) {
		if ("Chunk".equals(name) && staging instanceof ChunkStagingHost host
				&& device.getDeviceInfo().features().persistentMapping()) {
			ChunkStaging.Area area = new ChunkStaging.Area(name, bufferSize);
			host.vesuvio$setStagingArea(area);
			area.sync();
		}
		return staging;
	}

	@Inject(method = "startUploading", at = @At("HEAD"))
	private void vesuvio$followSetting(CommandEncoder encoder, CallbackInfoReturnable<StagingBuffer.Uploader> cir) {
		if (this.nextWriteOffset == 0 && (Object) this instanceof ChunkStagingHost host
				&& host.vesuvio$stagingArea() != null) {
			host.vesuvio$stagingArea().sync();
		}
	}

	// Base-class method (the Cpu area does not override it): reached right before the write offset goes back to 0.
	@Inject(method = "rotateBuffer", at = @At("HEAD"))
	private void vesuvio$restartMappedArea(CallbackInfo ci) {
		if ((Object) this instanceof ChunkStagingHost host && host.vesuvio$stagingArea() != null) {
			host.vesuvio$stagingArea().restart();
		}
	}
}
