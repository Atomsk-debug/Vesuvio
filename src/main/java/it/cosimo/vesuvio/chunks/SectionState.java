package it.cosimo.vesuvio.chunks;

/** Per-section data Vesuvio adds to SectionRenderDispatcher.RenderSection (see RenderSectionStateMixin). */
public interface SectionState {
	/** Compile start time (Util.getNanos) of the mesh the section shows; newer results only may replace it. */
	long vesuvio$shownStart();

	void vesuvio$setShownStart(long start);

	/** Camera position of the section's last translucent re-sort (NaN: none yet). Render thread only. */
	double vesuvio$sortX();

	double vesuvio$sortY();

	double vesuvio$sortZ();

	void vesuvio$setSortPos(double x, double y, double z);
}
