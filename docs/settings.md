# Vesuvio settings

Every setting lives in Options > Video Settings > **Vesuvio** and in `config/vesuvio.properties`.
A switch that is off makes that part of the game behave exactly like vanilla. Settings marked "restart" take
effect the next time the game starts; all others apply live.

## General

| Setting | Default | What it does |
|---|---|---|
| Vesuvio (`enabled`) | On | Master switch, also on **F8**. Off makes every part behave like vanilla, live. |
| Log counters (`statsLog`) | Off | Writes fps and Vesuvio's counters (what each part skipped) to `logs/latest.log` every 5 seconds. |
| Benchmark step (`benchmarkSeconds`) | 10 | Seconds the built-in benchmark measures each step, after it settles. See [benchmark.md](benchmark.md). |
| `disabledMixins` (file only) | empty | Comma-separated names of Vesuvio's hooks to skip at start-up, for troubleshooting. |

## Rendering

| Setting | Default | What it does |
|---|---|---|
| Leaf Culling (`leafCulling`) | Smart | Hides leaf faces buried inside a canopy (only with cutout leaves on). Off: vanilla. Smart: a face goes only when the next three blocks that way are leaves or solid, so trees look the same from outside. Aggressive: two blocks, a bit faster, canopies a little more see-through. All: every face between two leaves (trees become hollow shells). The world rebuilds once when changed. |
| Face Culling (`faceCulling`) | On | Skips terrain faces that point away from you before the graphics card works on them. |
| Face Culling: Split From (`faceCullingMinGap`) | 64 | How many hidden faces are worth one extra draw call. Lower skips more faces but costs more CPU. |
| Fewer Water/Glass Re-sorts (`translucentSortThrottle`) | On | Far see-through sections are re-sorted only after you have moved far enough for it to matter. |

## Entities

| Setting | Default | What it does |
|---|---|---|
| Entity Culling (`entityCulling`) | On | Skips drawing mobs, items and other entities hidden behind solid blocks (checked on a background thread). |
| Block Entity Culling (`blockEntityCulling`) | On | Skips drawing chests, signs, banners and other block entities outside your view or behind solid blocks. |
| Particle Culling (`particleCulling`) | On | Skips particles in places the game already knows you cannot see. |

## Memory (all need a restart)

The first row is the **memory check**, not a setting: after two minutes in a world it says whether the
launcher's maximum memory (Prism: Edit instance > Settings > Java > Maximum memory allocation) is too low, too
high for the computer, or fine, and which number to use. Too low makes the game clean up memory over and over
(stutter); too high leaves the rest of the computer short, and on Apple silicon the graphics share that memory,
so the system starts swapping (stutter too). In between, more memory does not make the game faster. The advice
is twice the heap the game keeps after cleanups (highest 5-minute floor) plus 1 GB, at least 3 GB, and at most
half the computer's memory on Apple silicon or all but 4 GB elsewhere. The same line is on F3.

Turned off automatically when FerriteCore or ModernFix is installed, since they do the same.

| Setting | Default | What it does |
|---|---|---|
| Light Chunk Locks (`memoryLightThreadChecks`) | On | Chunk data uses a tiny check instead of a lock object in every section. About 10-50 MB less. |
| Share Block Shapes (`memoryShareBlockShapes`) | On | Blocks with identical shapes share one shape object. |
| Share Block State Tables (`memoryShareStateTables`) | On | Block states with identical face tables share one table. About 1.5 MB less. |
| Share Id Names (`memoryShareIdNamespaces`) | On | Ids share one "minecraft" text instead of a copy each. About 1 MB less. |

## Frame

| Setting | Default | What it does |
|---|---|---|
| Animate Only Visible Textures (`animatedTextures`) | On | Water, lava, fire, portal and other animated block textures are redrawn only while something on screen uses them. |
| Reduce FPS When Idle (`idleFps`) | On | Caps the frame rate while the window is not focused, and draws almost nothing while it is minimized. |
| Idle FPS Limit (`idleFpsLimit`) | 10 | Frame rate while the window is open but not focused (3 to 60). |
| Faster Shader Compiling (`fastStartupCompile`) | On, restart | macOS only: lets Metal compile shaders on more threads, so first-time stutters are shorter. |
| Asynchronous Submit (`asyncSubmit`) | Off, restart | macOS only, experimental: MoltenVK converts each frame to Metal on its own thread. Can help when the CPU is the limit. |

## World

| Setting | Default | What it does |
|---|---|---|
| Smooth Chunk Building (`chunkScheduler`) | On | Builds chunks nearest first, a few at a time, instead of copying every changed section in one frame. Blocks you change are still rebuilt at once. |
| Skip Empty Sections (`skipEmptySections`) | On | Pieces of the world that hold only air (most of the sky) are not built at all. |
| Lighter Section Copies (`trimSectionCopies`) | On | Each section copy keeps only its own chests, signs and other block entities. |
| Chunk Upload Budget (`chunkUploadBudget`) | 2 ms | Most time per frame spent sending finished chunks to the graphics card. 0 is vanilla (everything at once). |
| Fast Entity Lookups (`worldEntitySectionLookup`) | On | Entity searches look only at the areas inside the search box. |
| Skip Entities Nothing Can Bump (`worldRareEntityIndex`) | On, restart | Collision checks and hoppers look only at the few entity kinds that can matter to them. |
| Remember Pathfinding Block Types (`worldPathTypeCache`) | On | Mobs' path finding remembers each block's type instead of working it out again. |
| Narrow Villager and Bee Searches (`worldPoiVerticalRange`) | On | Searches for beds, workstations and hives skip heights outside their sphere. |
| Faster Suffocation Check (`worldFastSuffocationCheck`) | On | The per-tick "head inside a block" check without extra objects. |

The world settings keep the game's rules exactly as in vanilla: same entities in the same order, same results.
