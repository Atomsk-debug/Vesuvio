<div align="center">

# 🌋 Vesuvio

**Same picture, more frames.**
A performance mod for Minecraft's native Vulkan renderer.

*Part of a family of Italian volcanoes:* [Etna](https://github.com/Atomsk-debug/Etna) (shaders) ·
**Vesuvio** (performance) · [Stromboli](https://github.com/Atomsk-debug/Stromboli) (quality of life)

</div>

---

Vesuvio is a Fabric mod for **Minecraft 26.4 snapshots** on the game's own **Vulkan renderer**. It makes the
game do less work for the same picture: it skips what you can't see, stops redoing work that hasn't changed,
and uses less memory. It runs on its own or next to Etna, and Etna's shadows get faster with it too.

Made first for Apple Silicon Macs (MoltenVK), and meant to work on Windows and Linux as well.

> **Status: in development.** Version 0.2.0 is in testing. Each part below ships only once it has been
> checked in game.

## Features

**Rendering**
* Draws only the block faces that can face the camera, so the graphics chip skips the ones turned away from
  you before doing any work on them.
* Hides leaf faces buried inside a canopy, keeping the outer layers you actually see.
* Skips mobs, chests, signs and particles hidden behind walls or outside your view.
* Skips terrain, mobs and particles lost in thick fog (under water, in lava, in the Nether, with Blindness),
  sign text seen from behind, item frames behind their wall and beacon beams out of view.
* Talks to the Vulkan renderer directly: clears are folded into the passes that follow them, back-to-back
  uploads share one GPU barrier, repeated binds are skipped, framebuffers are kept instead of rebuilt every
  pass, and terrain draw lists are reused while nothing changed.
* On Apple silicon (shared memory): mob, particle and GUI vertices are written straight where the GPU reads
  them, with no extra copy.

**Every frame**
* Updates animated textures (water, lava, fire, portals) only when they are on screen.
* Lowers the frame rate while the game is in the background or minimised, and restores it the moment you
  come back.
* Smoother chunk loading when you move fast.
* On Macs: lets MoltenVK compile shaders in parallel, so new effects don't stall the game.
* Keeps compiled shaders on disk, so the next launch skips the driver's work and first-use hitches.
* Caps the frame rate while paused behind a menu or while the window is covered.

**Memory and world**
* Smaller block-state tables: less memory, fewer pauses for garbage collection.
* Memory check: measures what the game really uses and tells you if the launcher's maximum memory is too low or
  too high for your computer, with the number to set.
* Faster world ticking in single player, with the game's rules exactly as in vanilla: same redstone, same
  mobs, same farms.
* Smoother chunk loading: chunks are built nearest first, a few at a time, and sent to the graphics card
  within a time budget, so joining a world or flying over new terrain no longer brings long frames; empty sky
  sections are not built at all.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.4-snapshot-3, with
   Java 25.
2. Put `vesuvio-<version>+mc26.4-snapshot-3.jar` in your `mods` folder. Fabric API is not needed.

Builds are attached to each run of the
[build workflow](https://github.com/Atomsk-debug/Vesuvio/actions/workflows/build.yml).

## Settings

Options > Video Settings > **Vesuvio**. Every optimization has its own switch and a short description, and
**F8** turns all of them on or off at once, so you can see the difference for yourself.

* Settings are saved in `config/vesuvio.properties`.
* With a switch off, that part of the game behaves exactly like vanilla.
* A built-in benchmark times the game with Vesuvio on, off, and with each part off in turn, and writes the
  table to `logs/latest.log`.

Every setting, explained: [docs/settings.md](docs/settings.md) · How to benchmark:
[docs/benchmark.md](docs/benchmark.md)

## Compatibility

* Needs the game's native Vulkan renderer. Sodium, VulkanMod and Nvidium replace that renderer, so they can't
  run together with Vesuvio.
* Works with Etna and Stromboli, and with mods that don't change how the world is drawn.

## FAQ

**Does it change how the game looks?**
No, not with the default settings. Leaf culling hides faces deep inside trees; you can turn it off or make it
stronger.

**Does it change how the game plays?**
No. The world-ticking speedups keep vanilla's rules exactly.

**Something looks wrong or the game crashes.**
Press F8: if the problem goes away, turn the optimizations back on one by one to find the culprit. If the game
won't start, put the name of the part in `disabledMixins` in `config/vesuvio.properties` (the crash report names
it). Then please open an issue.

**Do I need Etna?**
No. Vesuvio works with any look, vanilla included.

## Credits and licence

MIT licence, see [LICENSE](LICENSE). Code taken from other projects and the techniques Vesuvio follows are
listed in [CREDITS.md](CREDITS.md).
