# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:

- Per-direction face culling of terrain sections (`it.cosimo.vesuvio.faces`): sorting each section's quads by
  the way they face when it is built, and drawing only the facings that can face the camera. Idea from Sodium
  (PolyForm Shield); no Sodium code was read or copied.
