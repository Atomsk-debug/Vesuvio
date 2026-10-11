package it.cosimo.vesuvio.frame.mixin;

import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.device.GpuDevice;

import it.cosimo.vesuvio.frame.MappedVertexPools;

/**
 * Setting mappedVertexPools ({@link MappedVertexPools}): StagedVertexBuffer's vertex and index pools make mappable
 * buffers on unified memory, and an idle buffer made the other way is replaced instead of handed out. The staging
 * pool is left alone. Recycling (fences) is vanilla's.
 */
@Mixin(targets = "net.minecraft.client.renderer.StagedVertexBuffer$GpuBufferPool")
public abstract class FrameVertexPoolMixin {
	@Shadow
	@Final
	private int usage;

	@WrapOperation(method = "acquire", at = @At(value = "INVOKE",
			target = "Lcom/mojang/renderpearl/api/device/GpuDevice;createBuffer(Ljava/util/function/Supplier;IJ)Lcom/mojang/renderpearl/api/buffers/GpuBuffer;"))
	private GpuBuffer vesuvio$mappableBuffer(GpuDevice device, Supplier<String> label, int usage, long size,
			Operation<GpuBuffer> original) {
		return original.call(device, label, MappedVertexPools.createUsage(usage), size);
	}

	// Both returns of takeBestAvailable (exact size, best fit): only acquire calls it.
	@ModifyReturnValue(method = "takeBestAvailable", at = @At("RETURN"), require = 2)
	private GpuBuffer vesuvio$reuseMatching(GpuBuffer buffer) {
		if (buffer != null && !MappedVertexPools.reusable(this.usage, buffer)) {
			// Idle (it came back through the pool's fence): closing it only queues its destruction.
			buffer.close();
			return null;
		}
		return buffer;
	}
}
