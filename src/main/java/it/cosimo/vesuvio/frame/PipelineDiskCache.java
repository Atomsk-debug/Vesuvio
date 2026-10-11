package it.cosimo.vesuvio.frame;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.LongBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPipelineCacheCreateInfo;

import it.cosimo.vesuvio.Vesuvio;
import it.cosimo.vesuvio.VesuvioConfig;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Setting pipelineCache. Vanilla compiles every render pipeline (shader stages plus fixed state) with no Vulkan
 * pipeline cache (VK_NULL_HANDLE in vkCreateGraphicsPipelines), so the driver redoes all of its work on every
 * launch, and the first time a pipeline shows up in a world (a new mob, effect or shader pack pass) the frame waits
 * for it. This keeps one VkPipelineCache for the device, saves it to {@code vesuvio-cache/} in the game folder when
 * the game closes (and every minute while new pipelines appear), and gives it back to the driver at the next launch.
 * The driver checks the file's header (vendor, device, cache UUID, which changes with the driver) and ignores data
 * that does not match; Vesuvio checks the same header first and starts empty on any mismatch or read error.
 *
 * <p>Pictures do not change: a pipeline cache only stores what the driver already produced for the same input. On
 * Windows drivers this removes most first-use shader hitches after the first launch; on macOS MoltenVK keeps its
 * SPIR-V to Metal translation in it (Metal keeps its own compiled cache), so the gain there is smaller.
 */
public final class PipelineDiskCache {
	public static final VesuvioConfig.Toggle ENABLED = VesuvioConfig.toggle("frame", "pipelineCache", true, true);

	private static final int HEADER = 32;
	/** Larger saved caches are ignored (and not written): real ones are a few MB. */
	private static final long MAX_BYTES = 64L * 1024 * 1024;
	private static final long SAVE_EVERY_NS = 60_000_000_000L;

	private static long cache;
	private static VkDevice device;
	private static Path file;
	private static long lastSave;
	private static volatile int created;
	private static int createdAtSave;
	/** Pipelines compiled and total time spent in vkCreateGraphicsPipelines this session, for the counters. */
	public static volatile int compiles;
	public static volatile long compileNs;
	public static String status = "not started";

	private PipelineDiskCache() {
	}

	public static void init() {
		it.cosimo.vesuvio.ui.VesuvioStats.register("pipeline compiles", () -> compiles);
		it.cosimo.vesuvio.ui.VesuvioStats.register("pipeline compile ms", () -> (int) (compileNs / 1_000_000L));
	}

	/** VulkanDevice constructor, while the physical device's properties are still readable. Render thread, once. */
	public static void open(VkDevice vkDevice, VkPhysicalDeviceProperties props) {
		if (!ENABLED.on() || cache != 0L) {
			status = cache != 0L ? status : "off";
			return;
		}
		try {
			file = FabricLoader.getInstance().getGameDir().resolve("vesuvio-cache").resolve(String.format(Locale.ROOT,
					"pipelines-%04x-%04x.bin", props.vendorID(), props.deviceID()));
			byte[] data = read(file, props);
			// The saved data goes in native heap memory: it can be megabytes, far more than LWJGL's small per-thread
			// stack holds (a 3.5 MB cache on the stack crashed 0.3.0 at launch).
			ByteBuffer initial = data == null ? null : MemoryUtil.memAlloc(data.length).put(data).flip();
			try (MemoryStack stack = MemoryStack.stackPush()) {
				long handle = create(vkDevice, initial, stack);
				if (handle == 0L && initial != null) {
					Vesuvio.LOGGER.info("Vesuvio: the driver refused the saved pipeline cache, starting a new one");
					handle = create(vkDevice, null, stack);
					data = null;
				}
				cache = handle;
			} finally {
				if (initial != null) {
					MemoryUtil.memFree(initial);
				}
			}
			device = vkDevice;
			lastSave = System.nanoTime();
			status = cache == 0L ? "failed" : data == null ? "new" : "loaded " + data.length / 1024 + " KB";
			Vesuvio.LOGGER.info("Vesuvio pipeline cache: {} ({})", status, file.getFileName());
		} catch (Throwable e) {
			// Anything at all (also an Error from native memory): the game starts without a cache, as vanilla does.
			if (cache != 0L) {
				try {
					VK10.vkDestroyPipelineCache(vkDevice, cache, null);
				} catch (Throwable ignored) {
					// Nothing more to do: the handle is dropped.
				}
			}
			cache = 0L;
			device = null;
			status = "failed";
			Vesuvio.LOGGER.warn("Vesuvio: pipeline cache not used", e);
		}
	}

	private static long create(VkDevice vkDevice, ByteBuffer initial, MemoryStack stack) {
		VkPipelineCacheCreateInfo info = VkPipelineCacheCreateInfo.calloc(stack).sType$Default();
		if (initial != null) {
			info.pInitialData(initial);
		}
		LongBuffer out = stack.callocLong(1);
		return VK10.vkCreatePipelineCache(vkDevice, info, null, out) == VK10.VK_SUCCESS ? out.get(0) : 0L;
	}

	/** The cache to pass to vkCreateGraphicsPipelines instead of VK_NULL_HANDLE (0 when not used). */
	public static long handle(VkDevice vkDevice) {
		return vkDevice == device ? cache : 0L;
	}

	/** After every pipeline compile (any thread). */
	public static void compiled(long ns) {
		created++;
		compiles++;
		compileNs += ns;
	}

	/** End of a client tick: saves once a minute if pipelines were compiled since the last save. */
	public static void maybeSave() {
		if (cache == 0L || created == createdAtSave) {
			return;
		}
		long now = System.nanoTime();
		if (now - lastSave >= SAVE_EVERY_NS) {
			lastSave = now;
			save();
		}
	}

	/** VulkanDevice.close, before the device goes: saves and destroys the cache. */
	public static void close(VkDevice vkDevice) {
		if (cache == 0L || vkDevice != device) {
			return;
		}
		save();
		VK10.vkDestroyPipelineCache(device, cache, null);
		cache = 0L;
		device = null;
	}

	private static void save() {
		int count = created;
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.mallocPointer(1);
			if (VK10.vkGetPipelineCacheData(device, cache, size, null) != VK10.VK_SUCCESS || size.get(0) <= HEADER
					|| size.get(0) > MAX_BYTES) {
				return;
			}
			ByteBuffer data = MemoryUtil.memAlloc((int) size.get(0));
			try {
				// The size can only grow between the two calls; VK_INCOMPLETE then means a truncated, unusable copy.
				if (VK10.vkGetPipelineCacheData(device, cache, size, data) != VK10.VK_SUCCESS) {
					return;
				}
				byte[] bytes = new byte[(int) size.get(0)];
				data.get(0, bytes);
				Files.createDirectories(file.getParent());
				Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
				Files.write(tmp, bytes);
				Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
				createdAtSave = count;
			} finally {
				MemoryUtil.memFree(data);
			}
		} catch (Throwable e) {
			Vesuvio.LOGGER.warn("Vesuvio: could not save the pipeline cache", e);
		}
	}

	/** The saved cache if its header matches this device and driver, else null. */
	private static byte[] read(Path path, VkPhysicalDeviceProperties props) {
		try {
			if (!Files.isRegularFile(path) || Files.size(path) <= HEADER || Files.size(path) > MAX_BYTES) {
				return null;
			}
			byte[] data = Files.readAllBytes(path);
			ByteBuffer b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
			if (b.getInt(0) < HEADER || b.getInt(4) != VK10.VK_PIPELINE_CACHE_HEADER_VERSION_ONE
					|| b.getInt(8) != props.vendorID() || b.getInt(12) != props.deviceID()) {
				return null;
			}
			ByteBuffer uuid = props.pipelineCacheUUID();
			for (int i = 0; i < VK10.VK_UUID_SIZE; i++) {
				if (data[16 + i] != uuid.get(i)) {
					return null; // another driver version
				}
			}
			return data;
		} catch (Throwable e) {
			Vesuvio.LOGGER.info("Vesuvio: saved pipeline cache unreadable, starting a new one ({})", e.toString());
			return null;
		}
	}
}
