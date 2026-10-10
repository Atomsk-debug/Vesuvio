package it.cosimo.vesuvio.frame;

import java.nio.IntBuffer;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.EXTLayerSettings;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkLayerSettingEXT;
import org.lwjgl.vulkan.VkLayerSettingsCreateInfoEXT;

import com.mojang.renderpearl.util.PlatformUtil;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.Vesuvio;

/**
 * MoltenVK settings passed by the game itself at Vulkan instance creation, through VK_EXT_layer_settings (the same
 * values as the MVK_CONFIG_* environment variables, which a launcher would otherwise have to set). macOS only, and
 * only when MoltenVK offers the extension; everywhere else nothing changes. Read once at start-up.
 *
 * <ul>
 * <li>{@link #FAST_COMPILE}: MVK_CONFIG_SHOULD_MAXIMIZE_CONCURRENT_COMPILATION = true. Metal compiles shaders on
 * more threads at once (macOS 13.3+), so the hitch when a pipeline is first used is shorter.</li>
 * <li>{@link #ASYNC_SUBMIT} (opt-in): MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS = false. MoltenVK turns the frame's
 * Vulkan commands into Metal on its own thread instead of inside vkQueueSubmit on the render thread. Helps only when
 * the CPU side is the limit; a less used MoltenVK path, so off by default.</li>
 * </ul>
 */
public final class MoltenVkSettings {
	public static final VesuvioConfig.Toggle FAST_COMPILE = VesuvioConfig.toggle("frame", "fastStartupCompile", true, true);
	public static final VesuvioConfig.Toggle ASYNC_SUBMIT = VesuvioConfig.toggle("frame", "asyncSubmit", false, true);

	private static final String EXTENSION = "VK_EXT_layer_settings";
	private static final String LAYER = "MoltenVK";

	/** What was applied at start-up, for the settings screen: "off", "not macOS", "no extension", or the list. */
	public static String status = "not applied";

	private MoltenVkSettings() {
	}

	public static void init() {
		// Touching the class creates the settings.
	}

	/** VulkanInstance constructor, around vkCreateInstance. Render thread, once. */
	public static int createInstance(VkInstanceCreateInfo info, Creator create) {
		boolean fastCompile = FAST_COMPILE.on();
		boolean asyncSubmit = ASYNC_SUBMIT.on();
		if (!fastCompile && !asyncSubmit) {
			status = "off";
			return create.create(info);
		}
		if (!PlatformUtil.IS_MACOS) {
			status = "not macOS";
			return create.create(info);
		}
		try {
			return createWithSettings(info, create, fastCompile, asyncSubmit);
		} catch (LinkageError | RuntimeException e) {
			Vesuvio.LOGGER.warn("Vesuvio: could not pass MoltenVK settings, creating the instance as usual", e);
			status = "failed";
			return create.create(info);
		}
	}

	private static int createWithSettings(VkInstanceCreateInfo info, Creator create, boolean fastCompile,
			boolean asyncSubmit) {
		if (!hasInstanceExtension(EXTENSION)) {
			status = "no extension";
			Vesuvio.LOGGER.info("Vesuvio: Vulkan driver has no {}, MoltenVK settings not passed", EXTENSION);
			return create.create(info);
		}
		long oldNext = info.pNext();
		PointerBuffer oldExtensions = info.ppEnabledExtensionNames();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			int oldCount = oldExtensions == null ? 0 : oldExtensions.remaining();
			boolean alreadyEnabled = false;
			for (int i = 0; i < oldCount; i++) {
				if (EXTENSION.equals(oldExtensions.getStringUTF8(i))) {
					alreadyEnabled = true;
				}
			}
			PointerBuffer extensions = stack.mallocPointer(oldCount + (alreadyEnabled ? 0 : 1));
			for (int i = 0; i < oldCount; i++) {
				extensions.put(i, oldExtensions.get(i));
			}
			if (!alreadyEnabled) {
				extensions.put(oldCount, stack.UTF8(EXTENSION));
			}

			int count = (fastCompile ? 1 : 0) + (asyncSubmit ? 1 : 0);
			VkLayerSettingEXT.Buffer settings = VkLayerSettingEXT.calloc(count, stack);
			int i = 0;
			StringBuilder applied = new StringBuilder();
			if (fastCompile) {
				bool(settings.get(i++), stack, "MVK_CONFIG_SHOULD_MAXIMIZE_CONCURRENT_COMPILATION", true);
				applied.append("concurrent compilation");
			}
			if (asyncSubmit) {
				bool(settings.get(i++), stack, "MVK_CONFIG_SYNCHRONOUS_QUEUE_SUBMITS", false);
				applied.append(applied.isEmpty() ? "" : ", ").append("asynchronous submits");
			}
			VkLayerSettingsCreateInfoEXT layerSettings = VkLayerSettingsCreateInfoEXT.calloc(stack)
					.sType$Default()
					.pNext(oldNext)
					.pSettings(settings);

			info.ppEnabledExtensionNames(extensions);
			info.pNext(layerSettings.address());
			int result;
			try {
				result = create.create(info);
			} finally {
				// The game keeps using info (VkInstance reads its extension list): give it back its own memory.
				info.pNext(oldNext);
				info.ppEnabledExtensionNames(oldExtensions);
			}
			if (result == VK10.VK_SUCCESS) {
				status = applied.toString();
				Vesuvio.LOGGER.info("Vesuvio: MoltenVK settings passed: {}", status);
				return result;
			}
			Vesuvio.LOGGER.warn("Vesuvio: vkCreateInstance with MoltenVK settings returned {}, retrying without",
					result);
			status = "failed";
			return create.create(info);
		}
	}

	private static void bool(VkLayerSettingEXT setting, MemoryStack stack, String name, boolean value) {
		IntBuffer v = stack.ints(value ? VK10.VK_TRUE : VK10.VK_FALSE);
		setting.pLayerName(stack.UTF8(LAYER))
				.pSettingName(stack.UTF8(name))
				.type(EXTLayerSettings.VK_LAYER_SETTING_TYPE_BOOL32_EXT)
				.pValues(MemoryUtil.memByteBuffer(v))
				// After pValues: the count is in values, not bytes.
				.valueCount(1);
	}

	private static boolean hasInstanceExtension(String name) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer count = stack.callocInt(1);
			if (VK10.vkEnumerateInstanceExtensionProperties((String) null, count, null) != VK10.VK_SUCCESS) {
				return false;
			}
			VkExtensionProperties.Buffer props = VkExtensionProperties.calloc(count.get(0), stack);
			if (VK10.vkEnumerateInstanceExtensionProperties((String) null, count, props) != VK10.VK_SUCCESS) {
				return false;
			}
			for (int i = 0; i < count.get(0); i++) {
				if (name.equals(props.get(i).extensionNameString())) {
					return true;
				}
			}
			return false;
		}
	}

	/** The game's own vkCreateInstance call. */
	@FunctionalInterface
	public interface Creator {
		int create(VkInstanceCreateInfo info);
	}
}
