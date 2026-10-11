package it.cosimo.vesuvio.chunks;

/** Added to vanilla's malloc-backed chunk staging area (StagingBuffer.Cpu) by StagingCpuMixin. */
public interface ChunkStagingHost {
	/** The mapped area that may stand in for the malloc'd one, or null for staging areas Vesuvio leaves alone. */
	ChunkStaging.Area vesuvio$stagingArea();

	void vesuvio$setStagingArea(ChunkStaging.Area area);
}
