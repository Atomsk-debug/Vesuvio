package it.cosimo.vesuvio.chunks;

import it.cosimo.vesuvio.VesuvioConfig;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.SectionMesh;

/**
 * Keeps a section from going back to an older mesh. A finished mesh replaces the shown one either straight from
 * the compile task (no geometry) or once all its buffers reached the GPU (RenderSection.checkSectionMesh). Vanilla
 * uploads everything staged every frame, so an older build of a section (already staged when a newer task was
 * made) normally lands first; with the upload budget an older mesh's buffers can wait while a newer build of the
 * same section goes through, and an all-air section is emptied on the render thread while a worker may still be
 * staging an older build. Here every candidate is compared with the compile start time of what the section shows:
 * an older one is dropped (released, like vanilla releases a replaced mesh) instead of being shown over newer data.
 * In vanilla's own order nothing changes (every accepted mesh is newer than the shown one).
 */
public final class MeshOrder {
	private static final ThreadLocal<long[]> TASK_START = ThreadLocal.withInitial(() -> new long[1]);
	private static volatile long dropped;

	private MeshOrder() {
	}

	/** Counter: outdated meshes dropped instead of shown since start-up. */
	public static long dropped() {
		return dropped;
	}

	/** Compile task or Vesuvio's empty-section path about to run on this thread, for meshes without a start time. */
	public static void taskStarted(long startNanos) {
		TASK_START.get()[0] = startNanos;
	}

	/** RenderSection.setSectionMesh (any thread): whether {@code mesh} may replace what the section shows. */
	public static boolean accept(SectionState section, SectionMesh mesh) {
		if (!VesuvioConfig.ENABLED.on()) {
			return true;
		}
		long start = mesh instanceof CompiledSectionMesh compiled ? compiled.getCompileTaskStartTime() : TASK_START.get()[0];
		if (start < section.vesuvio$shownStart()) {
			dropped++;
			return false;
		}
		section.vesuvio$setShownStart(start);
		return true;
	}
}
