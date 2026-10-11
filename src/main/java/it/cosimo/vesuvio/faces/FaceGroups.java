package it.cosimo.vesuvio.faces;

import org.jspecify.annotations.Nullable;

/**
 * Added to CompiledSectionMesh (by CompiledSectionMeshMixin): where each facing group of quads starts in the
 * mesh's opaque layers, as FaceSorter recorded it when the section was built.
 */
public interface FaceGroups {
	/**
	 * Per layer (by ChunkSectionLayer ordinal): 8 quad offsets, group g being quads [s[g], s[g + 1]) in the
	 * order of {@link FaceSorter}. A null entry: that layer is not grouped (translucent, or a format the
	 * sorter does not know), drawn whole. Null as a whole: the section was built while face culling was off.
	 */
	int @Nullable [] @Nullable [] vesuvio$faceGroups();

	/** True once a rebuild has been asked for this mesh to group it (it was built while face culling was off). */
	boolean vesuvio$regroupAsked();

	void vesuvio$setRegroupAsked();

	/** Added to SectionCompiler.Results: carries the groups from the compile to the mesh made of it. */
	interface Carrier {
		int @Nullable [] @Nullable [] vesuvio$faceGroups();

		void vesuvio$setFaceGroups(int @Nullable [] @Nullable [] groups);
	}
}
