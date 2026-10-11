package it.cosimo.vesuvio.vertex.mixin;

import java.nio.ByteBuffer;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.vertex.CompactVectorArray;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;

import it.cosimo.vesuvio.vertex.CompactVertices;

/**
 * Setting compactTerrainVertices (only in the game when it was on at start-up): the translucent sort reads each
 * quad's centre back from the vertex bytes (decodeQuadCentroids, from MeshData.sortQuads), as floats. For a mesh
 * in a compact format it reads the half-float positions instead, the values the GPU draws; any other format goes
 * through vanilla's code.
 */
@Mixin(MeshData.class)
public abstract class MeshDataCompactMixin {
	@Inject(method = "decodeQuadCentroids", at = @At("HEAD"), cancellable = true)
	private static void vesuvio$halfCentroids(ByteBuffer vertexBuffer, int vertexCount, VertexFormat format,
			CompactVectorArray output, int outputIndex, CallbackInfo ci) {
		if (CompactVertices.decodeQuadCentroids(vertexBuffer, vertexCount, format, output, outputIndex)) {
			ci.cancel();
		}
	}
}
