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
- Settings screen laid out like Sodium's (pages on the left, options list, description box): layout idea only
  (`ui/VesuvioSettingsScreen.java`).
- Adding a key mapping by appending it to `Options.keyMappings` before `Options.load`: the approach Fabric API's
  key binding registry uses (Apache 2.0), idea only, own code (`ui/mixin/OptionsKeyMixin.java`).
- Vesuvio memory (`src/main/java/it/cosimo/vesuvio/memory/`), own code following:
  - FerriteCore by malte0811 (MIT, https://github.com/malte0811/FerriteCore): smaller PalettedContainer threading
    detector, block shape and block state cache deduplication, resource location deduplication.
  - ModernFix by embeddedt (LGPL, https://github.com/embeddedt/ModernFix): computing wall shapes once and sharing
    them between wall blocks (here also fences, panes and bars).

- Per-direction face culling of terrain sections (`it.cosimo.vesuvio.faces`): sorting each section's quads by
  the way they face when it is built, and drawing only the facings that can face the camera. Idea from Sodium
  (PolyForm Shield); no Sodium code was read or copied.
- Entity / block entity occlusion culling (`entities/OcclusionTest`, `entities/CullWorker`): visibility by casting
  rays from the camera through the block grid to sample points on the faces of the box that face the camera, on a
  background thread, results applied a frame later; idea from LogisticsCraft's OcclusionCulling
  (https://github.com/LogisticsCraft/OcclusionCulling, MIT, Copyright (c) tr7zw / LogisticsCraft) as used by
  EntityCulling. Independent implementation, no code copied from either.
- Animate only the animated sprites that are visible (`frame/AnimatedTextures.java`): idea from Sodium
  (CaffeineMC, PolyForm Shield 1.0.0), own code and own visibility tracking.
- Lower frame rate while unfocused or paused behind a menu, skip drawing while minimized or hidden
  (`frame/IdleFps.java`): idea from Dynamic FPS (juliand665 and LostLuma, MIT), own code, nothing ported.
- Sign text, item frame and beacon beam culling (`culling/DetailCulling.java`): ideas from More Culling by
  FX - PR0CESS (GPL-3.0, ideas only, no code read or copied); the sub-pixel and wall-projection tests are
  Vesuvio's own.
- Fog culling (`culling/FogCulling.java`): skipping what the fog fully covers, an old engine technique (and an idea
  several Minecraft mods use); own code built on the game's own fog values.
- MoltenVK settings through VK_EXT_layer_settings at instance creation (`frame/MoltenVkSettings.java`): the
  documented MoltenVK configuration parameters (Khronos MoltenVK, Apache 2.0), own code.
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
- Chunk update scheduling (`it.cosimo.vesuvio.chunks`): handing the build threads a bounded, nearest-first batch
  each frame, a per-frame time budget for mesh uploads and distance-based translucent re-sort triggers follow ideas
  from Sodium's chunk build system (PolyForm Shield: ideas only) and C2ME; skipping all-air sections, trimming the
  block entities of section copies and the newest-mesh-wins check are Vesuvio's own. All code is Vesuvio's own.
