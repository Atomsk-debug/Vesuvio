# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:
- Entity section lookup by direct key instead of the sorted-set walk (`ticks/SectionLookup`): idea from Lithium
  (CaffeineMC, LGPL-3.0) and Paper; own code, order-preserving.
- Per-section lists of the few entity classes that can be collided with / are containers, for entity collisions
  and hopper container lookups (`ticks/RareEntities`): idea from Lithium's entity class groups (LGPL-3.0); own code.
- Remembering each block state's pathfinding type (`ticks/PathTypeMemo`): idea from Lithium's ai.pathing
  (LGPL-3.0); own code (memo of the vanilla method, invalidated on tag rebinds).
- POI sphere searches limited to the sub-chunks the sphere reaches (`ticks/mixin/PoiManagerMixin`): idea from
  Lithium's ai.poi (LGPL-3.0); own code.
- Suffocation check without streams (`ticks/mixin/EntitySuffocationMixin`): idea from Lithium's
  entity.fast_suffocation_check (LGPL-3.0); own code.
