# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:
- Entity / block entity occlusion culling (`entities/OcclusionTest`, `entities/CullWorker`): visibility by casting
  rays from the camera through the block grid to sample points on the faces of the box that face the camera, on a
  background thread, results applied a frame later; idea from LogisticsCraft's OcclusionCulling
  (https://github.com/LogisticsCraft/OcclusionCulling, MIT, Copyright (c) tr7zw / LogisticsCraft) as used by
  EntityCulling. Independent implementation, no code copied from either.
