package it.cosimo.vesuvio.frame.mixin;

import java.util.List;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.pipeline.IndexType;

import net.minecraft.client.renderer.StagedVertexBuffer;

/** Setting mappedVertexPools: what a direct upload needs from a draw (it.cosimo.vesuvio.frame.MappedVertexPools). */
@Mixin(StagedVertexBuffer.Draw.class)
public interface FrameStagedDrawAccessor {
	@Accessor("vertexOffset")
	int vesuvio$vertexOffset();

	@Accessor("indexOffset")
	int vesuvio$indexOffset();

	@Accessor("quadSorting")
	@Nullable
	VertexSorting vesuvio$quadSorting();

	@Accessor("vertexBufferSlices")
	List<ByteBufferBuilder.Result> vesuvio$vertexBufferSlices();

	@Invoker("indexType")
	IndexType vesuvio$indexType();

	@Invoker("freeVertexData")
	void vesuvio$freeVertexData();
}
