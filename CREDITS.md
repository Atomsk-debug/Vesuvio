# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:
- Animate only the animated sprites that are visible (`frame/AnimatedTextures.java`): idea from Sodium
  (CaffeineMC, PolyForm Shield 1.0.0), own code and own visibility tracking.
- Lower frame rate while unfocused, skip drawing while minimized (`frame/IdleFps.java`): idea from Dynamic FPS
  (juliand665 and LostLuma, MIT), own code, nothing ported.
- MoltenVK settings through VK_EXT_layer_settings at instance creation (`frame/MoltenVkSettings.java`): the
  documented MoltenVK configuration parameters (Khronos MoltenVK, Apache 2.0), own code.
