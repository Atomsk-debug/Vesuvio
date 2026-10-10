package it.cosimo.vesuvio.chunks;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.chunk.TranslucencyPointOfView;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

/**
 * Fewer translucent re-sorts of far sections (render thread, LevelRenderer.scheduleResort).
 *
 * <p>Vanilla re-sorts a section's see-through faces (water, glass, ice...) when the camera crosses into another
 * section-sized region around it (the "point of view" changes), and also on every one-block camera step for any
 * section the camera is lined up with on one axis (same section row, column or layer: on a boat or at sea level that
 * is a whole layer of ocean) and for the sections within about 32 blocks. Each re-sort is a task that sorts all the
 * section's translucent quads and uploads a new index buffer, and it competes with chunk builds in the same queue.
 *
 * <p>The order of two faces only flips when the camera moves enough relative to their distance: from a section
 * {@code d} blocks away, a one-block step changes the difference of two face distances in it by at most about
 * {@code 28/d} blocks. Vanilla already lets the order go stale by up to one block of camera movement (it re-sorts
 * on block changes), i.e. up to about 1.5 blocks of error for a section 32 blocks away. Here the one-block-step
 * re-sort of a section farther than the nearby set happens once the camera moved at least {@code d/32} blocks since
 * that section's last sort, which keeps the error at that same 32-block level at any distance (every 2 blocks at
 * 64, every 8 at 256). Point-of-view changes, nearby sections and the first sort always go through as in vanilla.
 */
public final class TranslucentSort {
	private static long done;
	private static long skipped;

	private TranslucentSort() {
	}

	/** Counter: translucent re-sorts let through since start-up. */
	public static long resortsDone() {
		return done;
	}

	/** Counter: translucent re-sorts saved since start-up. */
	public static long resortsSkipped() {
		return skipped;
	}

	/** Called where vanilla decided to re-sort {@code section}; false skips it this time. */
	public static boolean shouldResort(SectionRenderDispatcher.RenderSection section, TranslucencyPointOfView pointOfView,
			Vec3 cameraPos, boolean isNearby) {
		SectionState state = (SectionState) section;
		if (ChunkUpdates.SORT_THROTTLE.on() && !isNearby && !section.getSectionMesh().isDifferentPointOfView(pointOfView)) {
			double lastX = state.vesuvio$sortX();
			if (!Double.isNaN(lastX)) {
				BlockPos origin = section.getRenderOrigin();
				double dx = origin.getX() + 8 - cameraPos.x;
				double dy = origin.getY() + 8 - cameraPos.y;
				double dz = origin.getZ() + 8 - cameraPos.z;
				double mx = cameraPos.x - lastX;
				double my = cameraPos.y - state.vesuvio$sortY();
				double mz = cameraPos.z - state.vesuvio$sortZ();
				// moved >= distance / 32
				if ((mx * mx + my * my + mz * mz) * 1024.0 < dx * dx + dy * dy + dz * dz) {
					skipped++;
					return false;
				}
			}
		}
		state.vesuvio$setSortPos(cameraPos.x, cameraPos.y, cameraPos.z);
		done++;
		return true;
	}
}
