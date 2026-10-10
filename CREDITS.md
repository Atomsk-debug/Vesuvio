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
- Vesuvio memory (`boost/src/main/java/it/cosimo/vesuvio/memory/`), own code following:
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
- Lower frame rate while unfocused, skip drawing while minimized (`frame/IdleFps.java`): idea from Dynamic FPS
  (juliand665 and LostLuma, MIT), own code, nothing ported.
- MoltenVK settings through VK_EXT_layer_settings at instance creation (`frame/MoltenVkSettings.java`): the
  documented MoltenVK configuration parameters (Khronos MoltenVK, Apache 2.0), own code.
