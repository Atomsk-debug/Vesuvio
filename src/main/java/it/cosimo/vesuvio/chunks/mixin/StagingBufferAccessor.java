package it.cosimo.vesuvio.chunks.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.StagingBuffer;

/** StagingBuffer: how much of the staging area is in use (it is only reused once every staged buffer was sent). */
@Mixin(StagingBuffer.class)
public interface StagingBufferAccessor {
	@Accessor("nextWriteOffset")
	int vesuvio$nextWriteOffset();

	@Invoker("getWriteBuffer")
	ByteBuffer vesuvio$writeBuffer();
}
