package it.cosimo.vesuvio.frame.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.CompactVectorArray;

import net.minecraft.client.renderer.StagedVertexBuffer;

/** Setting mappedVertexPools: vanilla's own quad centroid decoding for sorted index buffers. */
@Mixin(StagedVertexBuffer.class)
public interface FrameStagedVertexBufferInvoker {
	@Invoker("decodeSortingPoints")
	static CompactVectorArray vesuvio$decodeSortingPoints(StagedVertexBuffer.Draw draw) {
		throw new AssertionError();
	}
}
