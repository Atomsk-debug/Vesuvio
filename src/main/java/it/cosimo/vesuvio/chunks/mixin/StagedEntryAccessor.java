package it.cosimo.vesuvio.chunks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import com.mojang.blaze3d.vertex.StagingBuffer;

/** UberGpuBuffer.StagedAllocationEntry (private record): its staging handle, for size and closing. */
@Mixin(targets = "com.mojang.blaze3d.vertex.UberGpuBuffer$StagedAllocationEntry")
public interface StagedEntryAccessor {
	@Accessor("buffer")
	StagingBuffer.BufferHandle vesuvio$buffer();
}
