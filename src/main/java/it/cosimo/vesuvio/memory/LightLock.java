package it.cosimo.vesuvio.memory;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

import net.minecraft.ReportedException;
import net.minecraft.util.ThreadingDetector;
import net.minecraft.world.level.chunk.PalettedContainer;

/**
 * The lock-free stand-in for PalettedContainer's ThreadingDetector (idea from FerriteCore's "small threading
 * detector", own code). Vanilla gives every chunk-section container a ThreadingDetector holding a Semaphore and a
 * ReentrantLock (~128 bytes, 5 objects) only to crash with a clear report when two threads write one container at
 * once. This keeps that check with one field in the container: the thread that holds it, swapped with one atomic
 * operation. Same outcome on misuse (both threads get the "Accessing PalettedContainer from multiple threads"
 * crash, with both stack traces); same behaviour otherwise.
 */
public final class LightLock {
	/** Name of the field PalettedContainerMixin adds: null (free), the owning Thread, or a ReportedException. */
	public static final String FIELD = "vesuvio$holder";
	private static final VarHandle HOLDER = find();
	/** False if the field can't be found: containers then keep vanilla's detector (PalettedContainerMixin). */
	public static final boolean AVAILABLE = HOLDER != null;
	/**
	 * The detector every container gets when LightLock is used: one shared object, never locked, that only means
	 * "use LightLock". (Mixin rejects null from a constructor redirect at run time, so a marker stands in for "none".)
	 */
	public static final ThreadingDetector MARKER = new ThreadingDetector("vesuvio-light-lock");

	private LightLock() {
	}

	private static VarHandle find() {
		try {
			MethodHandles.Lookup lookup = MethodHandles.privateLookupIn(PalettedContainer.class, MethodHandles.lookup());
			for (Field f : PalettedContainer.class.getDeclaredFields()) {
				// Mixin keeps a @Unique name unless it clashes, in which case it only adds a suffix.
				if (f.getName().startsWith(FIELD) && f.getType() == Object.class) {
					return lookup.findVarHandle(PalettedContainer.class, f.getName(), Object.class);
				}
			}
			it.cosimo.vesuvio.Vesuvio.LOGGER.warn("Vesuvio: light chunk thread checks unavailable, using vanilla's");
		} catch (ReflectiveOperationException | RuntimeException e) {
			it.cosimo.vesuvio.Vesuvio.LOGGER.warn("Vesuvio: light chunk thread checks unavailable, using vanilla's", e);
		}
		return null;
	}

	/** Vanilla ThreadingDetector.checkAndLock. */
	public static void lock(PalettedContainer<?> container) {
		Thread me = Thread.currentThread();
		Object previous = HOLDER.compareAndExchange(container, (Object) null, (Object) me);
		if (previous != null) {
			// Another thread is inside (or it is a bug in this thread). Like vanilla: build the report with both
			// threads' stacks, make the holder's release throw it too, and throw it here.
			ReportedException failure = ThreadingDetector.makeThreadingException("PalettedContainer",
					previous instanceof Thread t ? t : null);
			HOLDER.compareAndSet(container, previous, (Object) failure);
			throw failure;
		}
	}

	/** Vanilla ThreadingDetector.checkAndUnlock. */
	public static void unlock(PalettedContainer<?> container) {
		Object previous = HOLDER.getAndSet(container, (Object) null);
		if (previous instanceof ReportedException failure) {
			throw failure;
		}
	}
}
