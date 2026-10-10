# Vesuvio

A performance mod for **Minecraft 26.4 snapshots** on the game's own **Vulkan renderer** (Fabric).
Vesuvio makes the game do less work for the same picture: it skips what you can't see, stops redoing work
that hasn't changed, and uses less memory. It works on its own, or together with
[Etna](https://github.com/Atomsk-debug/etna-shaders), the shaders from the same author.

Made first for Apple Silicon Macs (MoltenVK), and meant to work on Windows and Linux as well.

> **Status: in development.** The first version (0.1.0) is being built now. The list below is what it brings;
> each part ships only once it is checked in game.

## What it does

**Rendering**
* Draws only the block faces that can face the camera (the trick that made Sodium fast), so the graphics
  chip skips the faces turned away from you before doing any work on them.
* Hides the inner faces of leaves buried inside a canopy, keeping the outer layer and the gaps you can see
  through.
* Skips mobs, chests, signs and particles hidden behind walls or outside your view.

**Every frame**
* Updates animated textures (water, lava, fire, portals) only when they are on screen.
* Lowers the frame rate when the game is in the background or minimised, and restores it the moment you
  come back.
* On Macs: lets MoltenVK compile shaders in parallel, so new effects don't stall the game.

**Memory and world**
* Smaller block-state tables, so less memory and fewer pauses from garbage collection.
* Faster world ticking in single player, with the game's rules exactly as in vanilla (same redstone,
  same mobs, same farms).

## Settings

Options > Video Settings > **Vesuvio**. Every optimization has its own switch with a short description,
and **F8** switches all of them on or off at once, so you can see the difference for yourself. A built-in
benchmark times the game with Vesuvio on, off, and with each part off in turn, and writes the table to
`logs/latest.log`.

Settings are saved in `config/vesuvio.properties`. If an optimization ever causes trouble, it can be
switched off there, even without starting the game.

When a switch is off, that part of the game behaves exactly like vanilla.

## Install

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.5 or newer for Minecraft 26.4-snapshot-3, with
   Java 25.
2. Put `vesuvio-<version>+mc26.4-snapshot-3.jar` in the `mods` folder. Fabric API is not needed.

Builds are attached to each run of the
[build workflow](https://github.com/Atomsk-debug/Vesuvio/actions/workflows/build.yml).

## Compatibility

* Made for the game's native Vulkan renderer. Sodium, VulkanMod and Nvidium replace that renderer, so they
  cannot run together with Vesuvio.
* Works with Etna (shaders) and with mods that don't change how the world is drawn.

## Credits and licence

MIT licence, see `LICENSE`. Code taken from other projects and techniques Vesuvio follows are listed in
`CREDITS.md`.
