package it.cosimo.vesuvio.chunks;

import com.mojang.blaze3d.vertex.StagingBuffer;

import it.cosimo.vesuvio.VesuvioConfig;
import it.cosimo.vesuvio.chunks.mixin.StagedEntryAccessor;
import it.cosimo.vesuvio.chunks.mixin.StagingBufferAccessor;
import it.unimi.dsi.fastutil.objects.Object2ObjectMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;

/**
 * Time budget for sending finished chunk meshes to the GPU (render thread).
 *
 * <p>Compile workers copy each finished mesh into a 98 MB staging area; once a frame, at the end of
 * {@code LevelRenderer.render}, {@code SectionRenderDispatcher.uploadTerrainBuffersToGpu} sends everything staged:
 * for every vertex and index buffer of every finished section it allocates GPU space, copies the bytes again into
 * the frame's transfer memory (the Vulkan backend's writeToBuffer, 512 KB blocks allocated as needed), records a
 * copy command plus a full memory barrier, and swaps the section's mesh. After a join, a teleport or while flying,
 * one frame can carry hundreds of sections and tens of MB this way, all while the workers wait on the lock.
 *
 * <p>With a budget, each {@code UberGpuBuffer.uploadStagedAllocations} call of that frame only takes the entries
 * that fit in the time left (estimated from the measured cost per byte), at least one entry per frame; the others
 * stay staged, which vanilla itself already allows (it stops early after growing a GPU heap), and go first in line
 * next frame. A mesh is shown only once all its buffers are on the GPU (vanilla's rule), so a section never shows
 * half-built: it simply shows its old mesh a frame longer. If entries keep waiting, the budget grows each frame
 * (x2, x3, ...) and after {@link #FORCE_AFTER} frames everything is sent at once, as vanilla does, so nothing can
 * wait forever and the staging area is always emptied again.
 *
 * <p>The staging area is a bump allocator that starts over only after a frame that sent every staged buffer; while
 * anything is held it keeps filling, and once full the workers spin on the dispatcher's lock until it is emptied.
 * So a frame that finds it more than {@link #FLUSH_FILL} full sends everything (vanilla behaviour).
 */
public final class ChunkUploads {
	/** Frames in a row with entries left over after which one frame sends everything (vanilla behaviour). */
	static final int FORCE_AFTER = 10;
	/** Per-entry overhead (allocation, copy command, barrier, callback), counted as this many bytes. */
	static final long ENTRY_COST_BYTES = 4096;
	/** Share of the staging area in use above which the budget is ignored for the frame, so the area is reused. */
	static final double FLUSH_FILL = 0.5;

	/** True only during the end-of-frame upload called from LevelRenderer.render, while the budget applies. */
	private static boolean active;
	private static long deadline;
	private static boolean sentAny;
	private static long start;
	private static long units;
	private static int held;
	private static int backlogFrames;
	/** Measured cost of one unit (byte), nanoseconds; starts at about 4 GB/s. */
	private static double nsPerUnit = 0.25;

	private static volatile long uploadNanosLastFrame;
	private static volatile int heldLastFrame;

	private ChunkUploads() {
	}

	/** Counter: time the last end-of-frame chunk upload took, in nanoseconds. */
	public static long uploadNanosLastFrame() {
		return uploadNanosLastFrame;
	}

	/** Counter: staged buffers left for the next frame by the budget in the last frame (0 = all sent). */
	public static int heldLastFrame() {
		return heldLastFrame;
	}

	/** Counter: frames in a row that left buffers for the next one. */
	public static int backlogFrames() {
		return backlogFrames;
	}

	/** Render thread, before the end-of-frame upload. */
	public static void beginFrame() {
		int ms = VesuvioConfig.ENABLED.on() ? ChunkUpdates.UPLOAD_BUDGET.get() : 0;
		start = System.nanoTime();
		units = 0;
		held = 0;
		sentAny = false;
		if (ms <= 0) {
			backlogFrames = 0;
			active = false;
			return;
		}
		active = backlogFrames < FORCE_AFTER;
		deadline = start + ms * 1_000_000L * (1 + backlogFrames);
	}

	/** Render thread, after the end-of-frame upload (also when it threw). */
	public static void endFrame() {
		long took = System.nanoTime() - start;
		uploadNanosLastFrame = took;
		if (active && units >= 256 * 1024) {
			double measured = (double) took / units;
			nsPerUnit = Math.clamp(nsPerUnit * 0.8 + measured * 0.2, 0.02, 20.0);
		}
		heldLastFrame = held;
		backlogFrames = held > 0 ? backlogFrames + 1 : 0;
		active = false;
		ChunkStaging.endFrame();
	}

	/**
	 * Render thread, at the start of one UberGpuBuffer.uploadStagedAllocations call (under the dispatcher's lock):
	 * moves the staged entries that don't fit in the time left from {@code staged} to {@code held}, so vanilla's
	 * code that follows uploads only the rest. Does nothing unless the budget applies to this call.
	 */
	public static void select(Object2ObjectOpenHashMap<Object, Object> staged, Object2ObjectOpenHashMap<Object, Object> held,
			StagingBuffer staging) {
		if (!active || staged.isEmpty()) {
			return;
		}
		StagingBufferAccessor area = (StagingBufferAccessor) staging;
		if (area.vesuvio$nextWriteOffset() > area.vesuvio$writeBuffer().capacity() * FLUSH_FILL) {
			// Nearly full: send everything this frame (every call of the frame sees the same fill, nothing is
			// staged while the dispatcher's lock is held), so the area starts over.
			active = false;
			return;
		}
		double left = (deadline - System.nanoTime()) / nsPerUnit;
		long used = 0;
		ObjectIterator<Object2ObjectMap.Entry<Object, Object>> it = staged.object2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			Object2ObjectMap.Entry<Object, Object> entry = it.next();
			long cost = ((StagedEntryAccessor) entry.getValue()).vesuvio$buffer().size() + ENTRY_COST_BYTES;
			if (!sentAny || used + cost <= left) {
				used += cost;
				sentAny = true;
			} else {
				held.put(entry.getKey(), entry.getValue());
				it.remove();
			}
		}
		units += used;
	}

	/**
	 * Render thread, inside the same call just before vanilla empties its "skipped" set: puts the held entries
	 * back for the next frame, except those whose mesh was released meanwhile (in {@code skipped}): those are
	 * closed, as vanilla closes skipped entries, so the staging area can be reused.
	 */
	public static void restore(Object2ObjectOpenHashMap<Object, Object> staged, Object2ObjectOpenHashMap<Object, Object> held,
			ObjectOpenHashSet<Object> skipped) {
		if (held.isEmpty()) {
			return;
		}
		ObjectIterator<Object2ObjectMap.Entry<Object, Object>> it = held.object2ObjectEntrySet().fastIterator();
		while (it.hasNext()) {
			Object2ObjectMap.Entry<Object, Object> entry = it.next();
			if (skipped.contains(entry.getKey())) {
				((StagedEntryAccessor) entry.getValue()).vesuvio$buffer().close();
			} else {
				staged.put(entry.getKey(), entry.getValue());
				ChunkUploads.held++;
			}
		}
		held.clear();
	}
}
