# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:
- Leaf face culling (`it.cosimo.vesuvio.leaves`): dropping leaf faces buried behind other leaves follows the idea
  of OptiFine's "Smart Leaves" and of CullLeaves (MIT) / MoreCulling's leaf culling modes;
  the depth-3 "smart" default, the look-ahead dirtying and all code are Vesuvio's own.
- Chunk update scheduling (`it.cosimo.vesuvio.chunks`): handing the build threads a bounded, nearest-first batch
  each frame, a per-frame time budget for mesh uploads and distance-based translucent re-sort triggers follow ideas
  from Sodium's chunk build system (PolyForm Shield: ideas only) and C2ME; skipping all-air sections, trimming the
  block entities of section copies and the newest-mesh-wins check are Vesuvio's own. All code is Vesuvio's own.
