package it.cosimo.vesuvio.frame;

import java.nio.LongBuffer;

import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFramebufferCreateInfo;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.ui.VesuvioStats;

/**
 * Setting framebufferCache (research-vulkan-engine.md, "Smaller items"). Vanilla's VulkanCommandEncoder.createRenderPass
 * makes a new VkFramebuffer for every render pass it begins and queues it for destruction straight away, so a frame
 * creates and destroys one per pass (20 or so in vanilla, many more with a shader pack). A framebuffer is only a
 * list of image views tied to a render pass layout and a size; the same pass over the same targets asks for an
 * identical one every frame.
 *
 * <p>Here the framebuffer is kept, keyed by everything vkCreateFramebuffer is given: the VkRenderPass, the attachment
 * views in order (VK_NULL_HANDLE slots included), width, height and layers (flags and pNext must be 0, which is
 * what vanilla passes; anything else is created as usual and not kept). The next pass with the same key begins on the
 * kept framebuffer. Vulkan only reads a framebuffer's views and size when a pass begins, so an identical one gives the
 * same rendering.
 *
 * <p>When it is destroyed:
 * <ul>
 * <li>an attachment view is closed (VulkanGpuTextureView.close): every kept framebuffer that uses it is queued on the
 * encoder's destroy queue before the view itself is, so it goes when the GPU is done with the frames that used it,
 * exactly like vanilla's per-pass framebuffer, and never outlives its view. A view handle cannot be reused for a new
 * view while a framebuffer naming it is still kept, so a stale key can never match;</li>
 * <li>more than {@link #MAX} are kept: the least recently used one is queued the same way;</li>
 * <li>the setting (or the master switch) is turned off: all of them are queued at the next pass, which then runs as
 * vanilla;</li>
 * <li>the device closes (VulkanCommandEncoder.destroy): all of them are queued before the encoder drains its queue
 * after waiting for the GPU.</li>
 * </ul>
 *
 * <p>Clear fusion (clears/) may end a pass and begin it again on the framebuffer it began with: that happens inside
 * one pass, while the framebuffer is in use and so cannot have been queued (a view cannot be closed in the middle of
 * a frame's use of it and destroyed before the frame ends: destruction is deferred like vanilla's). It may also begin
 * the pass again with a load-only VkRenderPass from the RenderPassCache: that pass is compatible with the one the
 * framebuffer was made for (same formats, only load ops differ), as vanilla's per-pass framebuffer also relied on.
 *
 * <p>Render thread, like the encoder; the table is locked anyway because a view may in principle be closed elsewhere.
 */
public final class FramebufferCache {
	/** Keep framebuffers across passes and frames. Live. */
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "framebufferCache", true, false);

	/** Most framebuffers kept at once. Vanilla uses about 20 distinct ones a frame; shader packs a few dozen. */
	static final int MAX = 128;

	private static volatile int reusedLastFrame;
	private static volatile int keptCount;

	private FramebufferCache() {
	}

	public static void init() {
		VesuvioStats.register("framebuffers reused", () -> reusedLastFrame);
		VesuvioStats.register("framebuffers kept", () -> keptCount);
	}

	/** The kept framebuffers of one encoder (one device). */
	public static final class Table {
		private final long[] renderPasses = new long[MAX];
		private final long[][] views = new long[MAX][];
		private final int[] widths = new int[MAX];
		private final int[] heights = new int[MAX];
		private final int[] layers = new int[MAX];
		private final int[] hashes = new int[MAX];
		private final long[] framebuffers = new long[MAX];
		private final long[] lastUse = new long[MAX];
		private int count;
		private long useClock;
		private int reused;
		private final VkDevice device;

		public Table(VkDevice device) {
			this.device = device;
		}

		/** Whether a framebuffer with this create info may be kept: vanilla's plain form only. */
		public static boolean cacheable(VkFramebufferCreateInfo info) {
			return info.flags() == 0 && info.pNext() == 0L && info.pAttachments() != null;
		}

		/** The kept framebuffer for this create info, or 0 (VK_NULL_HANDLE) when there is none. */
		public synchronized long find(VkFramebufferCreateInfo info) {
			LongBuffer attachments = info.pAttachments();
			long renderPass = info.renderPass();
			int width = info.width();
			int height = info.height();
			int layerCount = info.layers();
			int hash = hash(renderPass, attachments, width, height, layerCount);
			for (int i = 0; i < count; i++) {
				if (hashes[i] == hash && renderPasses[i] == renderPass && widths[i] == width && heights[i] == height
						&& layers[i] == layerCount && sameViews(views[i], attachments)) {
					lastUse[i] = ++useClock;
					reused++;
					return framebuffers[i];
				}
			}
			return VK10.VK_NULL_HANDLE;
		}

		/** Keeps a framebuffer just created from this info; when full, the least recently used one is queued. */
		public synchronized void put(VkFramebufferCreateInfo info, long framebuffer, VulkanCommandEncoder encoder) {
			LongBuffer attachments = info.pAttachments();
			int slot;
			if (count < MAX) {
				slot = count++;
			} else {
				slot = 0;
				for (int i = 1; i < MAX; i++) {
					if (lastUse[i] < lastUse[slot]) {
						slot = i;
					}
				}
				queueDestroy(encoder, framebuffers[slot]);
			}
			long[] copy = new long[attachments.remaining()];
			for (int j = 0; j < copy.length; j++) {
				copy[j] = attachments.get(attachments.position() + j);
			}
			renderPasses[slot] = info.renderPass();
			views[slot] = copy;
			widths[slot] = info.width();
			heights[slot] = info.height();
			layers[slot] = info.layers();
			hashes[slot] = hash(renderPasses[slot], attachments, widths[slot], heights[slot], layers[slot]);
			framebuffers[slot] = framebuffer;
			lastUse[slot] = ++useClock;
			keptCount = count;
		}

		/** An attachment view is being closed: every kept framebuffer naming it is queued for destruction. */
		public synchronized void viewClosed(long view, VulkanCommandEncoder encoder) {
			for (int i = count - 1; i >= 0; i--) {
				if (contains(views[i], view)) {
					queueDestroy(encoder, framebuffers[i]);
					removeAt(i);
				}
			}
			keptCount = count;
		}

		/** Queues every kept framebuffer for destruction (setting off, device closing). */
		public synchronized void clear(VulkanCommandEncoder encoder) {
			for (int i = 0; i < count; i++) {
				queueDestroy(encoder, framebuffers[i]);
				views[i] = null;
			}
			count = 0;
			keptCount = 0;
		}

		public synchronized boolean isEmpty() {
			return count == 0;
		}

		/** The frame was submitted: publish the counter. */
		public synchronized void endFrame() {
			reusedLastFrame = reused;
			reused = 0;
		}

		private void removeAt(int i) {
			int last = count - 1;
			if (i != last) {
				renderPasses[i] = renderPasses[last];
				views[i] = views[last];
				widths[i] = widths[last];
				heights[i] = heights[last];
				layers[i] = layers[last];
				hashes[i] = hashes[last];
				framebuffers[i] = framebuffers[last];
				lastUse[i] = lastUse[last];
			}
			views[last] = null;
			count = last;
		}

		private void queueDestroy(VulkanCommandEncoder encoder, long framebuffer) {
			// The same deferred destruction vanilla queues for its per-pass framebuffer.
			VkDevice vkDevice = this.device;
			encoder.queueForDestroy(() -> VK10.vkDestroyFramebuffer(vkDevice, framebuffer, null));
		}

		private static boolean sameViews(long[] kept, LongBuffer attachments) {
			int n = attachments.remaining();
			if (kept.length != n) {
				return false;
			}
			int base = attachments.position();
			for (int j = 0; j < n; j++) {
				if (kept[j] != attachments.get(base + j)) {
					return false;
				}
			}
			return true;
		}

		private static boolean contains(long[] kept, long view) {
			for (long v : kept) {
				if (v == view) {
					return true;
				}
			}
			return false;
		}

		private static int hash(long renderPass, LongBuffer attachments, int width, int height, int layerCount) {
			long h = renderPass * 31L + width;
			h = h * 31L + height;
			h = h * 31L + layerCount;
			int base = attachments.position();
			int n = attachments.remaining();
			for (int j = 0; j < n; j++) {
				h = h * 31L + attachments.get(base + j);
			}
			return (int) (h ^ (h >>> 32));
		}
	}
}
