# Changelog

## 0.2.0 (in progress)
- Memory check (Memory page, F3): measures how much memory the game really needs and tells you whether the
  launcher's maximum memory setting is too low (stutter from constant cleanup), too high for your computer
  (the system and, on Apple silicon, the graphics run short and start swapping) or fine, with the number to set.
  A one-time chat message only when something is wrong.
- Keep Compiled Shaders (Frame page, on): vanilla compiles every render pipeline without a Vulkan pipeline cache,
  so the driver redoes all of it each launch. Vesuvio keeps one, saves it to `vesuvio-cache/` and reuses it:
  faster start and no first-use shader hitches after the first launch, mostly on Windows.

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
