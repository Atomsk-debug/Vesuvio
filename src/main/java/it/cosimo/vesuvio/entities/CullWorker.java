package it.cosimo.vesuvio.entities;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.concurrent.locks.LockSupport;

import it.cosimo.vesuvio.Vesuvio;
import net.minecraft.client.multiplayer.ClientLevel;

/**
 * The background thread that runs the occlusion tests. The render thread fills a {@link Batch} each frame (the
 * boxes of the entities / block entities that passed vanilla's frustum test, copied, plus the camera position) and
 * drops it in a one-slot mailbox; the worker always takes the newest batch (older ones are recycled unseen), tests
 * every box and writes the result into the object ({@link CullTarget}), tagged with its pass number. The render
 * thread never waits: it uses whatever results are there and treats anything missing or stale as visible.
 *
 * <p>No allocation in steady state: batches are recycled through a small pool and only grow.
 */
final class CullWorker implements Runnable {
	/** Box tests per pass, at most this often (keeps one core from spinning at 500 fps). */
	private static final long MIN_PASS_NANOS = 4_000_000L;
	static final int RING = 16;

	static final class Batch {
		Object[] targets = new Object[512];
		double[] boxes = new double[512 * 6];
		int count;
		ClientLevel level;
		double camX;
		double camY;
		double camZ;
		long createdNanos;

		void add(CullTarget target, double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
			if (count == targets.length) {
				targets = Arrays.copyOf(targets, count * 2);
				boxes = Arrays.copyOf(boxes, count * 12);
			}
			targets[count] = target;
			int b = count * 6;
			boxes[b] = minX;
			boxes[b + 1] = minY;
			boxes[b + 2] = minZ;
			boxes[b + 3] = maxX;
			boxes[b + 4] = maxY;
			boxes[b + 5] = maxZ;
			count++;
		}

		void clear() {
			Arrays.fill(targets, 0, count, null);
			count = 0;
			level = null;
		}
	}

	private static final AtomicReference<Batch> MAILBOX = new AtomicReference<>();
	private static final AtomicReferenceArray<Batch> POOL = new AtomicReferenceArray<>(4);
	private static volatile Thread thread;
	private static volatile boolean waiting;

	/** Per pass: camera position and the time its batch was made, for the render thread's freshness check. */
	static final double[] RING_CAM = new double[RING * 3];
	static final long[] RING_TIME = new long[RING];
	/** Number of the last finished pass (0 = none yet). Written after the ring entry, so reading it first is safe. */
	static volatile long latestPass;
	static volatile long totalTests;
	static volatile long totalRays;
	static volatile int lastPassMicros;

	private final OcclusionTest test = new OcclusionTest();

	private CullWorker() {
	}

	static void ensureStarted() {
		if (thread == null) {
			synchronized (CullWorker.class) {
				if (thread == null) {
					Thread t = new Thread(new CullWorker(), "Vesuvio occlusion culling");
					t.setDaemon(true);
					t.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
					t.start();
					thread = t;
				}
			}
		}
	}

	/** Render thread: an empty batch to fill this frame. */
	static Batch acquire() {
		for (int i = 0; i < POOL.length(); i++) {
			Batch b = POOL.getAndSet(i, null);
			if (b != null) {
				return b;
			}
		}
		return new Batch();
	}

	/** Render thread: hand a filled batch to the worker (replacing one it has not started yet, which is recycled). */
	static void publish(Batch batch) {
		Batch stale = MAILBOX.getAndSet(batch);
		if (stale != null) {
			stale.clear();
			release(stale);
		}
		Thread t = thread;
		if (t != null && waiting) {
			LockSupport.unpark(t);
		}
	}

	private static void release(Batch batch) {
		for (int i = 0; i < POOL.length(); i++) {
			if (POOL.compareAndSet(i, null, batch)) {
				return;
			}
		}
		// Pool full (only after a race): let it be collected.
	}

	@Override
	public void run() {
		long pass = 0;
		while (true) {
			Batch batch = MAILBOX.getAndSet(null);
			if (batch == null) {
				waiting = true;
				if (MAILBOX.get() == null) {
					LockSupport.parkNanos(this, 50_000_000L);
				}
				waiting = false;
				continue;
			}
			long start = System.nanoTime();
			try {
				pass++;
				runPass(batch, pass);
			} catch (Throwable t) {
				// Never let the thread die; a failed pass just leaves older results, which go stale -> visible.
				Vesuvio.LOGGER.debug("Vesuvio: occlusion pass failed", t);
			} finally {
				test.end();
				batch.clear();
				release(batch);
			}
			long spent = System.nanoTime() - start;
			lastPassMicros = (int) Math.min(Integer.MAX_VALUE, spent / 1000L);
			if (spent < MIN_PASS_NANOS) {
				LockSupport.parkNanos(this, MIN_PASS_NANOS - spent);
			}
		}
	}

	private void runPass(Batch batch, long pass) {
		ClientLevel level = batch.level;
		if (level == null) {
			return;
		}
		test.begin(level, batch.camX, batch.camY, batch.camZ);
		long hiddenTag = (pass << 1) | 1L;
		long visibleTag = pass << 1;
		double[] boxes = batch.boxes;
		Object[] targets = batch.targets;
		int n = batch.count;
		for (int i = 0; i < n; i++) {
			int b = i * 6;
			boolean visible;
			try {
				visible = test.boxVisible(boxes[b], boxes[b + 1], boxes[b + 2], boxes[b + 3], boxes[b + 4], boxes[b + 5]);
			} catch (RuntimeException e) {
				visible = true;
			}
			((CullTarget) targets[i]).vesuvio$setCullStamp(visible ? visibleTag : hiddenTag);
		}
		int slot = (int) (pass & (RING - 1));
		RING_CAM[slot * 3] = batch.camX;
		RING_CAM[slot * 3 + 1] = batch.camY;
		RING_CAM[slot * 3 + 2] = batch.camZ;
		RING_TIME[slot] = batch.createdNanos;
		totalTests += n;
		totalRays = test.rays;
		latestPass = pass;
	}
}
