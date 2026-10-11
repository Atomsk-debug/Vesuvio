package it.cosimo.vesuvio.chunks;

import java.nio.ByteBuffer;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.GpuFence;

/**
 * Setting mappedChunkStaging: finished chunk meshes go straight into GPU-visible memory.
 *
 * <p>Vanilla's chunk staging area on Vulkan is plain CPU memory (StagingBuffer.Cpu, 98 MB): a compile worker copies
 * each finished mesh into it, and at upload time writeToBuffer copies the bytes a second time, into the frame's
 * transient staging memory, before the GPU copy into the chunk heap. The game also has a mapped variant
 * (PersistentlyMapped: workers write into a host-visible buffer and the upload is one GPU copy from it), but it only
 * picks it where writeToBuffer is slow, which is never on Vulkan. This uses the same idea inside the Cpu area, so it
 * can be switched live: while the setting is on, the area's write buffer is one of three host-visible, host-coherent
 * GPU buffers (half the area each, like the mapped variant), and the upload copies from it with copyToBuffer. One
 * memcpy per mesh fewer, no transient staging for chunks; the GPU copy is the same.
 *
 * <p>Safety with frames in flight. The staging area is a bump allocator that starts over only after a frame that
 * sent every staged mesh (vanilla's rotateBuffer point, after the budget of {@link ChunkUploads} let everything
 * through); nothing is written twice into one buffer before that. At that point the buffer just used is retired
 * with a fence on the frame being recorded (it signals once every copy recorded so far has finished on the GPU),
 * and the next buffer is taken only if its own fence has already signalled, checked without waiting. If none of the
 * three is free (three restarts within two frames), that round simply uses the malloc'd area with vanilla's
 * writeToBuffer path. Host writes made before vkQueueSubmit to host-coherent memory are visible to the commands of
 * that submit, and the workers write under the dispatcher's lock, which the render thread holds while it records the
 * copies (and before it submits). Mode changes happen only at those restart points or while nothing is staged, so
 * a staged mesh is always copied from the memory it was written to.
 */
public final class ChunkStaging {
	private static long bytesTotal;
	private static long bytesSinceFrame;
	private static volatile int kbLastFrame;
	private static volatile int fallbacks;

	private ChunkStaging() {
	}

	public static void init() {
		it.cosimo.vesuvio.ui.VesuvioStats.register("chunk KB staged direct", () -> kbLastFrame);
		it.cosimo.vesuvio.ui.VesuvioStats.register("chunk MB staged direct", () -> (int) (bytesTotal >> 20));
	}

	/** Counter: chunk bytes sent from mapped staging since start-up. */
	public static long bytesTotal() {
		return bytesTotal;
	}

	/** Counter: times all three mapped buffers were still in use (that round used vanilla's path). */
	public static int fallbacks() {
		return fallbacks;
	}

	/** Render thread, after the end-of-frame chunk upload (ChunkUploads.endFrame): publishes the frame's count. */
	static void endFrame() {
		kbLastFrame = (int) (bytesSinceFrame >> 10);
		bytesSinceFrame = 0;
	}

	/**
	 * The mapped stand-in for one chunk staging area. Every method runs on the render thread with the chunk
	 * dispatcher's lock held (StagingBuffer.create, startUploading and rotateBuffer are only reached that way), and
	 * the compile workers read {@link #data()} under the same lock, so no other synchronisation is needed.
	 */
	public static final class Area {
		private static final int COUNT = 3;

		private final String label;
		private final int size;
		private final GpuBuffer[] buffers = new GpuBuffer[COUNT];
		private final GpuFence[] fences = new GpuFence[COUNT];
		private int current = -1;
		private GpuBufferSlice.MappedView view;
		private ByteBuffer data;

		public Area(String name, int areaSize) {
			this.label = name + " staging (Vesuvio)";
			this.size = Math.max(1, areaSize / 2);
		}

		/** The mapped write buffer, or null while the malloc'd area is in use. */
		public ByteBuffer data() {
			return this.data;
		}

		public boolean mapped() {
			return this.view != null;
		}

		/** Nothing is staged (write offset 0): follows the setting now. */
		public void sync() {
			boolean want = ChunkUpdates.MAPPED_STAGING.on();
			if (want && this.view == null) {
				acquire();
			} else if (!want) {
				// Nothing was written into the current buffer since it was taken (the offset is still 0), so it can
				// be dropped without a fence; closing queues the GPU buffers for destruction after the frames in
				// flight, which may still be copying from the older ones.
				unmap();
				freeAll();
			}
		}

		/**
		 * Vanilla's rotateBuffer point: every staged buffer was copied (or dropped) and the area starts over. Retires
		 * the buffer in use with a fence on the frame being recorded, then takes a free one or goes back to the
		 * malloc'd area.
		 */
		public void restart() {
			if (this.view != null) {
				unmap();
				this.fences[this.current] = RenderSystem.getDevice().createCommandEncoder().createFence();
			}
			if (ChunkUpdates.MAPPED_STAGING.on()) {
				acquire();
			} else {
				freeAll();
			}
		}

		/** StagingBuffer.Cpu.copyTo while mapped: one GPU copy from the mapped buffer into the chunk heap. */
		public void copy(CommandEncoder encoder, GpuBuffer dst, long dstOffset, long stagingOffset, long copySize) {
			encoder.copyToBuffer(this.buffers[this.current].slice(stagingOffset, copySize), dst.slice(dstOffset, copySize));
			bytesSinceFrame += copySize;
			bytesTotal += copySize;
		}

		/** The staging area is closed (the chunk renderer goes away). */
		public void close() {
			unmap();
			freeAll();
		}

		/** Maps the next buffer whose last use has finished on the GPU, without waiting; none: stays unmapped. */
		private void acquire() {
			for (int k = 1; k <= COUNT; k++) {
				int i = Math.floorMod(this.current + k, COUNT);
				GpuFence fence = this.fences[i];
				if (fence != null) {
					if (!fence.awaitCompletion(0L)) {
						continue;
					}
					fence.close();
					this.fences[i] = null;
				}
				if (this.buffers[i] == null) {
					final int index = i;
					this.buffers[i] = RenderSystem.getDevice().createBuffer(() -> this.label + " #" + index,
							GpuBuffer.USAGE_MAP_WRITE | GpuBuffer.USAGE_COPY_SRC, this.size);
				}
				this.view = this.buffers[i].map(false, true);
				this.data = this.view.data();
				this.current = i;
				return;
			}
			fallbacks++;
		}

		private void unmap() {
			if (this.view != null) {
				this.view.close();
				this.view = null;
				this.data = null;
			}
		}

		private void freeAll() {
			for (int i = 0; i < COUNT; i++) {
				if (this.fences[i] != null) {
					this.fences[i].close();
					this.fences[i] = null;
				}
				if (this.buffers[i] != null) {
					this.buffers[i].close();
					this.buffers[i] = null;
				}
			}
			this.current = -1;
		}
	}
}
