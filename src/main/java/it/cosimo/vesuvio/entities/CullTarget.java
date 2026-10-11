package it.cosimo.vesuvio.entities;

/**
 * Added to {@code Entity} and {@code BlockEntity} by mixins: the last occlusion result for this object, written by
 * the cull worker thread and read by the render thread. Packed as {@code (passNumber << 1) | hidden}, 0 = never tested.
 */
public interface CullTarget {
	long vesuvio$cullStamp();

	void vesuvio$setCullStamp(long stamp);
}
