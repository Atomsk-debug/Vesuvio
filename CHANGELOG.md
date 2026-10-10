# Changelog

## 0.1.2
- Benchmark: a run stopped early (a key, a click, a move) now keeps the steps it already measured, in latest.log
  and on the results screen, instead of throwing them away.
- Benchmark: the action bar shows the step and the time left while it runs.
- Benchmark: the player still drifting just after the menu closes no longer stops the run at once.

## 0.1.1
- Fixed a crash when entering a world with saved chunks ("Exception loading chunk", from the Light Chunk Locks
  memory setting).
- Listing a hook in `disabledMixins` now also skips the hooks that depend on it, instead of crashing later.

## 0.1.0
- First version: leaf culling, face culling, entity/block entity/particle culling, animated textures only when
  visible, idle frame cap, MoltenVK settings on macOS, memory savings, smoother chunk loading, faster world ticks,
  settings screen with F8 and a built-in benchmark.
