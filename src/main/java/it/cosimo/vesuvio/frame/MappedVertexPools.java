package it.cosimo.vesuvio.frame;

import java.nio.ByteBuffer;
import java.util.List;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryHeap;
import org.lwjgl.vulkan.VkMemoryType;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexSorting;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.frame.mixin.FrameStagedDrawAccessor;
import it.cosimo.vesuvio.frame.mixin.FrameStagedVertexBufferInvoker;
import it.cosimo.vesuvio.ui.VesuvioStats;
import net.minecraft.client.renderer.StagedVertexBuffer;

/**
 * Setting mappedVertexPools (research-vulkan-engine.md, "Smaller items"): on unified memory, the vertex and index
 * buffers of StagedVertexBuffer (entities, block entities, particles, the GUI) are written by the CPU directly.
 *
 * <p>Vanilla writes each upload into a mapped staging buffer and then records copyToBuffer into the vertex buffer and
 * the index buffer, each followed by a full barrier: two to four GPU copies a frame, and on MoltenVK a blit encoder
 * between render encoders. When the GPU's memory is the computer's memory (Apple silicon: every memory heap is device
 * local and one of them has a host-visible, coherent memory type), a buffer the GPU reads as vertices can just as
 * well be host-visible: the pools' new buffers get the map-write usage (the backend then picks host-visible coherent
 * memory, the same kind the staging buffer uses), and the upload writes the same bytes at the same offsets into them
 * directly. Nothing else changes: same buffers bound, same offsets, same draws.
 *
 * <p><b>Why the GPU never reads a buffer while the CPU writes it</b> (two frames in flight). StagedVertexBuffer's
 * pools already recycle buffers by fence, and the CPU writes only into a buffer the pool just handed out:
 * <ol>
 * <li>acquire returns a new buffer (nothing ever read it) or one from the pool's available list;</li>
 * <li>a buffer enters the available list only in tryRecycleBuffers, when the fence made at the end of the frame
 * that used it reports completion. That fence holds the encoder's current submit index at that moment, and reports
 * completion only once the submit timeline semaphore has reached it (vkWaitSemaphores). The semaphore is signalled at
 * the end of each submit with all commands as its first scope, which also covers every earlier submit on the
 * queue;</li>
 * <li>every draw that reads the buffer is recorded between upload and endDraw, and endDraw comes before the pool's
 * endFrame that makes the fence, so every read is in that submit or an earlier one;</li>
 * <li>so when the buffer is written again, every GPU read of its previous contents has finished. The writes reach
 * coherent memory before the vkQueueSubmit of the frame whose draws read them, which makes them visible to those
 * draws (host writes are ordered before a submission).</li>
 * </ol>
 * This is exactly the ownership vanilla already relies on for its own mapped staging buffers (same pool class, same
 * fence), which the CPU writes and the GPU reads in the same way; only the reader changes from a copy to the draws.
 *
 * <p>Live: with the setting or the master switch off, new pool buffers are made as vanilla's and every upload goes
 * through the staging copy. A buffer made the other way that a pool hands out is closed and replaced at once (it is
 * idle: it came from the available list), so the pools turn over within a frame.
 */
public final class MappedVertexPools {
	/** Write StagedVertexBuffer's vertex and index buffers directly on unified memory. Live. */
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "mappedVertexPools", true, false);

	/** Set once the Vulkan device exists: every heap device local, one with a host-visible coherent type. */
	private static volatile boolean unified;
	/** "unified memory", "not unified", or "no device yet": for the log and the report. */
	public static volatile String status = "no device yet";

	private static int skipped;
	private static volatile int skippedLastFrame;

	private MappedVertexPools() {
	}

	public static void init() {
		VesuvioStats.register("vertex copies skipped", () -> skippedLastFrame);
	}

	/** VulkanDevice constructor: decides whether memory is unified (heaps and types, not the OS). */
	public static void deviceCreated(VkDevice vkDevice) {
		boolean result = false;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceMemoryProperties props = VkPhysicalDeviceMemoryProperties.calloc(stack);
			VK10.vkGetPhysicalDeviceMemoryProperties(vkDevice.getPhysicalDevice(), props);
			int heaps = props.memoryHeapCount();
			boolean allDeviceLocal = heaps > 0;
			for (int i = 0; i < heaps; i++) {
				VkMemoryHeap heap = props.memoryHeaps(i);
				if ((heap.flags() & VK10.VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) == 0) {
					allDeviceLocal = false;
				}
			}
			int wanted = VK10.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT | VK10.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
					| VK10.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
			boolean shared = false;
			for (int i = 0; i < props.memoryTypeCount(); i++) {
				VkMemoryType type = props.memoryTypes(i);
				if ((type.propertyFlags() & wanted) == wanted) {
					shared = true;
				}
			}
			result = allDeviceLocal && shared;
		} catch (RuntimeException | LinkageError e) {
			Vesuvio.LOGGER.warn("Vesuvio: could not read the GPU's memory heaps, vertex buffers stay as in vanilla", e);
		}
		unified = result;
		status = result ? "unified memory" : "not unified";
		Vesuvio.LOGGER.info("Vesuvio: GPU memory {}: entity and GUI vertex buffers {}", status,
				result ? "written directly (setting mappedVertexPools)" : "copied as in vanilla");
	}

	/** Whether the pools should hand out mappable vertex and index buffers right now. */
	private static boolean mapNow() {
		return unified && SETTING.on();
	}

	/** One of the vertex or index pools (not the staging pool, which is already mappable). */
	private static boolean isDrawPool(int poolUsage) {
		return (poolUsage & (GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_INDEX)) != 0
				&& (poolUsage & GpuBuffer.USAGE_MAP_WRITE) == 0;
	}

	/** GpuBufferPool.acquire, creating a buffer: the usage it is made with. */
	public static int createUsage(int poolUsage) {
		return isDrawPool(poolUsage) && mapNow() ? poolUsage | GpuBuffer.USAGE_MAP_WRITE : poolUsage;
	}

	/** GpuBufferPool.acquire, reusing an idle buffer: false when it was made the other way (it is then replaced). */
	public static boolean reusable(int poolUsage, GpuBuffer buffer) {
		if (!isDrawPool(poolUsage)) {
			return true;
		}
		return ((buffer.usage() & GpuBuffer.USAGE_MAP_WRITE) != 0) == mapNow();
	}

	/**
	 * StagedVertexBuffer.uploadDrawsToBuffers: writes the draws straight into the buffers when both can be mapped,
	 * with the same bytes at the same offsets as vanilla's staging copy. Returns false to let vanilla run.
	 */
	public static boolean writeDirect(List<StagedVertexBuffer.Draw> draws, GpuBuffer vertexBuffer,
			GpuBuffer indexBuffer, int vertexBufferSize, int indexBufferSize) {
		if (!SETTING.on() || (vertexBuffer.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0
				|| indexBuffer != null && (indexBuffer.usage() & GpuBuffer.USAGE_MAP_WRITE) == 0) {
			return false;
		}
		int n = draws.size();
		try (GpuBufferSlice.MappedView view = vertexBuffer.slice(0L, vertexBufferSize).map(false, true)) {
			ByteBuffer buffer = view.data();
			for (int i = 0; i < n; i++) {
				StagedVertexBuffer.Draw draw = draws.get(i);
				if (!draw.isEmpty()) {
					FrameStagedDrawAccessor access = (FrameStagedDrawAccessor) draw;
					buffer.position(access.vesuvio$vertexOffset());
					List<ByteBufferBuilder.Result> slices = access.vesuvio$vertexBufferSlices();
					for (int j = 0, m = slices.size(); j < m; j++) {
						buffer.put(slices.get(j).byteBuffer());
					}
				}
			}
		}
		skipped++;
		if (indexBuffer != null) {
			try (GpuBufferSlice.MappedView view = indexBuffer.slice(0L, indexBufferSize).map(false, true)) {
				ByteBuffer buffer = view.data();
				for (int i = 0; i < n; i++) {
					StagedVertexBuffer.Draw draw = draws.get(i);
					FrameStagedDrawAccessor access = (FrameStagedDrawAccessor) draw;
					VertexSorting sorting = access.vesuvio$quadSorting();
					if (!draw.isEmpty() && sorting != null) {
						MeshData.SortState sortState = new MeshData.SortState(
								FrameStagedVertexBufferInvoker.vesuvio$decodeSortingPoints(draw), access.vesuvio$indexType());
						buffer.position(access.vesuvio$indexOffset());
						sortState.writeSortedIndexBuffer(buffer, sorting);
					}
				}
			}
			skipped++;
		}
		for (int i = 0; i < n; i++) {
			StagedVertexBuffer.Draw draw = draws.get(i);
			if (!draw.isEmpty()) {
				((FrameStagedDrawAccessor) draw).vesuvio$freeVertexData();
			}
		}
		return true;
	}

	/** End of frame (submit): publish the counter. */
	public static void endFrame() {
		skippedLastFrame = skipped;
		skipped = 0;
	}
}
