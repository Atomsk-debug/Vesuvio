package it.cosimo.vesuvio.frame;

import it.cosimo.vesuvio.VesuvioConfig;

/**
 * Setting mergeTransferBarriers. The Vulkan backend follows every copy and buffer write with a full memory barrier
 * (all commands to all commands, every read and write). On MoltenVK each barrier outside a render pass also closes
 * the Metal blit encoder and makes the next one wait, so a frame that sends a few hundred chunk buffers records a few
 * hundred barriers and encoders; the per-frame uniform writes and entity/GUI vertex copies add 10 to 20 more.
 *
 * <p>Two copies only need a barrier between them when one touches what the other wrote, or writes what the other
 * read. So the barrier after a copy or buffer write (writeToBuffer, copyToBuffer, writeToTexture,
 * copyBufferToTexture) is held back: the next copy of that kind that touches none of the held ranges runs without
 * one, and exactly one barrier, the same full barrier, is recorded before anything else: a render pass, a clear, a
 * texture read-back or texture-to-texture copy, a timestamp, the end of the command buffer (submit, present,
 * semaphores), or a copy that overlaps a held range. Pass-end and clear barriers are left as they are.
 *
 * <p>Why the picture is the same: a pipeline barrier orders everything recorded before it (in submission order, not
 * only since the previous barrier) against everything after it. Every command that is not one of these copies still
 * has a full barrier between it and every copy before it, as in vanilla. Between two copies inside one held group
 * there is no barrier, but they share no memory written by either (buffer byte ranges, image rectangles of one mip
 * and layer), so there is no hazard for a barrier to resolve. The report (report-v020-uploads.md) has the long form.
 */
public final class TransferBarriers {
	/** Merge the barriers between back-to-back copies that touch different memory. Live. */
	public static final VesuvioConfig.Toggle SETTING = VesuvioConfig.toggle("frame", "mergeTransferBarriers", true, false);

	private static volatile int mergedLastFrame;
	private static long mergedTotal;

	private TransferBarriers() {
	}

	public static void init() {
		it.cosimo.vesuvio.ui.VesuvioStats.register("upload barriers merged", () -> mergedLastFrame);
	}

	/** Counter: barriers not recorded in the last submitted frame. */
	public static int mergedLastFrame() {
		return mergedLastFrame;
	}

	/** Counter: barriers not recorded since start-up. */
	public static long mergedTotal() {
		return mergedTotal;
	}

	/**
	 * The held barrier of one command encoder and what the copies since the last barrier touched (render thread
	 * only, like the encoder itself). Ranges are [start, end) in bytes per VkBuffer; image writes are rectangles per
	 * VkImage, mip level and layer. The lists are bounded: when one is full the barrier is recorded and they start
	 * over, so the overlap check stays a short scan.
	 */
	public static final class Tracker {
		static final int MAX = 256;

		/** True while a copy's barrier is held back. */
		public boolean pending;
		/** True between the start of a copy whose barrier will be held and the barrier call it replaces. */
		public boolean defer;
		private int merged;

		private final long[] writes = new long[MAX * 3];
		private int writeCount;
		private final long[] reads = new long[MAX * 3];
		private int readCount;
		private final long[] images = new long[MAX * 6];
		private int imageCount;

		// The copy being recorded now.
		private boolean hasSrc;
		private long srcBuffer;
		private long srcStart;
		private long srcEnd;
		private boolean hasDst;
		private long dstBuffer;
		private long dstStart;
		private long dstEnd;
		private boolean hasImage;
		private long image;
		private long subresource;
		private long rectLow;
		private long rectHigh;

		/**
		 * Start of a copy: whether its barrier will be held back (setting on). When false the caller records any
		 * held barrier first and the copy runs exactly as vanilla, with its own barrier.
		 */
		public boolean begin() {
			this.defer = SETTING.on();
			this.hasSrc = false;
			this.hasDst = false;
			this.hasImage = false;
			return this.defer;
		}

		public void source(long buffer, long start, long end) {
			this.hasSrc = true;
			this.srcBuffer = buffer;
			this.srcStart = start;
			this.srcEnd = end;
		}

		public void destination(long buffer, long start, long end) {
			this.hasDst = true;
			this.dstBuffer = buffer;
			this.dstStart = start;
			this.dstEnd = end;
		}

		public void destinationImage(long image, int mipLevel, int layer, int x, int y, int width, int height) {
			this.hasImage = true;
			this.image = image;
			this.subresource = ((long) mipLevel << 32) | (layer & 0xFFFFFFFFL);
			this.rectLow = ((long) x << 32) | (y & 0xFFFFFFFFL);
			this.rectHigh = ((long) (x + width) << 32) | ((y + height) & 0xFFFFFFFFL);
		}

		/**
		 * Whether the held barrier must be recorded before the copy described so far: it reads something a held copy
		 * wrote, writes something a held copy read or wrote, or a list is full. Does nothing when no barrier is held.
		 */
		public boolean mustFlush() {
			if (!this.pending) {
				return false;
			}
			if (this.writeCount >= MAX || this.readCount >= MAX || this.imageCount >= MAX) {
				return true;
			}
			if (this.hasSrc && overlaps(this.writes, this.writeCount, this.srcBuffer, this.srcStart, this.srcEnd)) {
				return true;
			}
			if (this.hasDst && (overlaps(this.writes, this.writeCount, this.dstBuffer, this.dstStart, this.dstEnd)
					|| overlaps(this.reads, this.readCount, this.dstBuffer, this.dstStart, this.dstEnd))) {
				return true;
			}
			return this.hasImage && overlapsImage();
		}

		/** The copy was recorded and its barrier held back: remember what it touched. */
		public void commit() {
			this.defer = false;
			if (this.hasSrc) {
				this.readCount = add(this.reads, this.readCount, this.srcBuffer, this.srcStart, this.srcEnd);
			}
			if (this.hasDst) {
				this.writeCount = add(this.writes, this.writeCount, this.dstBuffer, this.dstStart, this.dstEnd);
			}
			if (this.hasImage) {
				int i = this.imageCount * 6;
				this.images[i] = this.image;
				this.images[i + 1] = this.subresource;
				this.images[i + 2] = this.rectLow >> 32;
				this.images[i + 3] = (int) this.rectLow;
				this.images[i + 4] = this.rectHigh >> 32;
				this.images[i + 5] = (int) this.rectHigh;
				this.imageCount++;
			}
			if (this.pending) {
				this.merged++;
				mergedTotal++;
			}
			this.pending = true;
		}

		/** The held barrier was recorded (or there is nothing to record it into): start over. */
		public void clear() {
			this.pending = false;
			this.writeCount = 0;
			this.readCount = 0;
			this.imageCount = 0;
		}

		/** At submit: publishes the frame's count. */
		public void endFrame() {
			mergedLastFrame = this.merged;
			this.merged = 0;
		}

		private static boolean overlaps(long[] list, int count, long buffer, long start, long end) {
			for (int i = 0, n = count * 3; i < n; i += 3) {
				if (list[i] == buffer && start < list[i + 2] && list[i + 1] < end) {
					return true;
				}
			}
			return false;
		}

		/** Appends a range, or extends the last one when it is the same buffer and the new range starts at its end. */
		private static int add(long[] list, int count, long buffer, long start, long end) {
			if (count > 0) {
				int last = (count - 1) * 3;
				if (list[last] == buffer && list[last + 2] == start) {
					list[last + 2] = end;
					return count;
				}
			}
			int i = count * 3;
			list[i] = buffer;
			list[i + 1] = start;
			list[i + 2] = end;
			return count + 1;
		}

		private boolean overlapsImage() {
			long x0 = this.rectLow >> 32;
			long y0 = (int) this.rectLow;
			long x1 = this.rectHigh >> 32;
			long y1 = (int) this.rectHigh;
			for (int i = 0, n = this.imageCount * 6; i < n; i += 6) {
				if (this.images[i] == this.image && this.images[i + 1] == this.subresource
						&& x0 < this.images[i + 4] && this.images[i + 2] < x1
						&& y0 < this.images[i + 5] && this.images[i + 3] < y1) {
					return true;
				}
			}
			return false;
		}
	}
}
