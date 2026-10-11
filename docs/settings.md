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
| Face Culling: Split From (`faceCullingMinGap`) | 64 | How many hidden faces are worth one extra draw call when the graphics card's time is what counts. Lower skips more faces but costs more CPU. With Face Culling: Smart Splits on, the Mac uses it only while the graphics card is the limit. |
| Face Culling: Smart Splits (`faceCullingAutoSplit`) | On | macOS only: while the CPU is the limit (the game spends less than about 10% of the frame waiting for the graphics card, measured every second), no section draw is split, because on the Mac every extra draw costs CPU time (about 1 µs in MoltenVK) and the vertex work it would save lands on a graphics card that is waiting anyway. Faces at either end of a section are still skipped. While the graphics card is the limit, `faceCullingMinGap` applies as set. Off: `faceCullingMinGap` always applies as set. Does nothing on Windows and Linux. Same picture either way. |
| Reuse Terrain Draw Lists (`sectionDrawCache`) | On | The list of terrain draws for the main view is built again only when something it depends on changed (visible sections, their meshes, where they sit in GPU memory, fade-in, the camera crossing a face culling boundary). Otherwise last frame's list is used. Less CPU time and garbage per frame, same picture. Etna's shadow pass is never served from it. |
| Skip Repeated Binds (`skipRedundantBinds`) | On | In each Vulkan render pass, the pipeline already bound is not bound again, and a new descriptor set (the textures and buffers a draw uses) is not made when the one already bound holds exactly the same ones. Saves CPU time per draw for entities, particles and the GUI (MoltenVK encodes each bind into Metal). Same picture. |
| Fewer Water/Glass Re-sorts (`translucentSortThrottle`) | On | Far see-through sections are re-sorted only after you have moved far enough for it to matter. |
| Clear Fusion (`clearFusion`) | On | A clear followed by drawing into the same picture becomes part of that drawing (one full write and read less), clears nothing can see are skipped, and post effects get no depth buffers they never read. Same picture. Counters: clears fused, dropped, split. |
| Fog Culling (`fogCulling`) | On | Skips terrain, mobs, block entities and particles so deep in the fog that they would be plain fog colour anyway (under water, lava, powder snow, Blindness/Darkness, the Nether, boss fog). Uses the game's own fog distance each frame; in clear air nothing is skipped. Off by itself with Improved Transparency, near the world border, with debug overlays and while Etna Shaders draws. |

## Entities

| Setting | Default | What it does |
|---|---|---|
| Entity Culling (`entityCulling`) | On | Skips drawing mobs, items and other entities hidden behind solid blocks (checked on a background thread). |
| Block Entity Culling (`blockEntityCulling`) | On | Skips drawing chests, signs, banners and other block entities outside your view or behind solid blocks. |
| Particle Culling (`particleCulling`) | On | Skips particles in places the game already knows you cannot see. |
| Sign Text Culling (`signTextCulling`) | On | Skips sign text on the side facing away from you, and all of it once each letter is under one pixel. |
| Item Frame Culling (`itemFrameCulling`) | On | Skips item frames when you are behind the solid wall they hang on, and the framed item or map once it is under one pixel. |
| Beacon Beam Culling (`beaconBeamCulling`) | On | Skips beacon beams wholly outside your view (vanilla draws every beam every frame). |

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
| Treat Hidden Window as Minimized (`hiddenWindowFps`) | On | A hidden or fully covered window (macOS: Cmd+H, another Space, covered by other windows) draws almost nothing, like a minimized one. Only where the system reports it. |
| Cap FPS When Paused (`pausedFps`) | On | Single player paused behind a menu: frame rate capped at the limit below. |
| Paused FPS Limit (`pausedFpsLimit`) | 60 | Frame rate while paused behind a menu (10 to 240). |
| Faster Shader Compiling (`fastStartupCompile`) | On, restart | macOS only: lets Metal compile shaders on more threads, so first-time stutters are shorter. |
| Keep Compiled Shaders (`pipelineCache`) | On, restart | Saves the driver's compiled shaders (Vulkan pipeline cache) to `vesuvio-cache/` when you quit and reuses them next launch: faster start, no hitch the first time a new mob, effect or shader pass shows. Checked against the GPU and driver. Biggest gain on Windows. |
| Asynchronous Submit (`asyncSubmit`) | Off, restart | macOS only, experimental: MoltenVK converts each frame to Metal on its own thread. Can help when the CPU is the limit. |
| No Metal Argument Buffers (`metalArgumentBuffersOff`) | Off, restart | macOS only, a benchmark experiment: sets MoltenVK's `MVK_CONFIG_USE_METAL_ARGUMENT_BUFFERS=0`, so each draw's descriptors are bound one by one instead of through a Metal argument buffer. Faster or slower is unknown: benchmark it both ways. Shader packs with more than 16 textures in one shader may fail with it on. |
| Merge Upload Barriers (`mergeTransferBarriers`) | On | Back-to-back copies to the graphics card that touch different memory share one full barrier instead of one each; the barrier is still recorded before anything that could use their data. Fewer GPU stalls and, on Mac, fewer Metal command groups when chunks load. |
| Keep Framebuffers (`framebufferCache`) | On | The Vulkan framebuffer of each render pass (which pictures it draws into) is kept and reused while the same pictures are drawn into again, instead of made and thrown away every pass. Thrown away when one of its pictures is. Same picture. Counters: framebuffers reused, framebuffers kept. |
| Direct Vertex Writes (`mappedVertexPools`) | On | Unified memory only (Apple silicon, integrated GPUs; read from the driver's memory heaps): entity, particle and GUI vertices are written straight into the buffers the GPU draws from, instead of a staging buffer plus a GPU copy and barrier each. A buffer is rewritten only after the GPU finished the frames that used it (the game's own buffer fences). Same picture. Counter: vertex copies skipped. |

## World

| Setting | Default | What it does |
|---|---|---|
| Smooth Chunk Building (`chunkScheduler`) | On | Builds chunks nearest first, a few at a time, instead of copying every changed section in one frame. Blocks you change are still rebuilt at once. |
| Skip Empty Sections (`skipEmptySections`) | On | Pieces of the world that hold only air (most of the sky) are not built at all. |
| Lighter Section Copies (`trimSectionCopies`) | On | Each section copy keeps only its own chests, signs and other block entities. |
| Chunk Upload Budget (`chunkUploadBudget`) | 2 ms | Most time per frame spent sending finished chunks to the graphics card. 0 is vanilla (everything at once). |
| Direct Chunk Staging (`mappedChunkStaging`) | On | Finished chunks are written straight into memory the graphics card reads, so each upload is one GPU copy without a second CPU copy first. Up to about 150 MB more memory. Takes effect the next time no chunk is waiting. |
| Fast Entity Lookups (`worldEntitySectionLookup`) | On | Entity searches look only at the areas inside the search box. |
| Skip Entities Nothing Can Bump (`worldRareEntityIndex`) | On, restart | Collision checks and hoppers look only at the few entity kinds that can matter to them. |
| Remember Pathfinding Block Types (`worldPathTypeCache`) | On | Mobs' path finding remembers each block's type instead of working it out again. |
| Narrow Villager and Bee Searches (`worldPoiVerticalRange`) | On | Searches for beds, workstations and hives skip heights outside their sphere. |
| Faster Suffocation Check (`worldFastSuffocationCheck`) | On | The per-tick "head inside a block" check without extra objects. |

The world settings keep the game's rules exactly as in vanilla: same entities in the same order, same results.
