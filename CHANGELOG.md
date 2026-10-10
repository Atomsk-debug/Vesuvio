# Changelog

## 0.2.0 (in progress)
- Clear Fusion (Rendering page, on): a texture cleared through the command encoder and then drawn into is now
  cleared by the render pass as it opens (LOAD_OP_CLEAR, same value, same pixels) instead of by a separate clear
  that writes the whole image and has the pass read it back; the GUI's dead depth clears are dropped; post chain
  targets no input reads as depth get no depth texture. Works for any mod's passes (Etna's post chains included).
  Counters: clears fused, dropped, split.
- Memory check (Memory page, F3): measures how much memory the game really needs and tells you whether the
  launcher's maximum memory setting is too low (stutter from constant cleanup), too high for your computer
  (the system and, on Apple silicon, the graphics run short and start swapping) or fine, with the number to set.
  A one-time chat message only when something is wrong.
- Keep Compiled Shaders (Frame page, on): vanilla compiles every render pipeline without a Vulkan pipeline cache,
  so the driver redoes all of it each launch. Vesuvio keeps one, saves it to `vesuvio-cache/` and reuses it:
  faster start and no first-use shader hitches after the first launch, mostly on Windows.
- Merge Upload Barriers (Frame page, on): the Vulkan backend put a full GPU barrier after every copy and buffer
  write. Back-to-back copies that touch different memory now share one, recorded before the next render pass,
  clear, read-back, overlapping copy or submit. Chunk-loading frames go from hundreds of barriers (and, on Mac,
  Metal blit encoders) to a handful. Counter "upload barriers merged".
- Direct Chunk Staging (World page, on): finished chunk meshes are written straight into mapped GPU-visible memory
  and sent with one GPU copy, instead of a second CPU copy into transient memory first. Works with the chunk upload
  budget. Counters "chunk KB staged direct" and "chunk MB staged direct".
- Skip Repeated Binds (Rendering page, on): in each Vulkan render pass the pipeline already bound is not bound
  again, and no new descriptor set is made when the bound one already holds the same buffers and textures. Fewer
  CPU-side commands for every entity, particle and GUI draw. F3 counters `binds skipped`, `desc sets skipped`, and
  `draws` (draws per frame, each indirect sub-draw counted: Metal draws on the Mac).
- Face Culling: Smart Splits (Rendering page, on, macOS only): no face culling split costs an extra Metal draw while
  the CPU is the limit; `faceCullingMinGap` applies as set while the graphics card is the limit, and always on
  Windows and Linux.
- Reuse Terrain Draw Lists (Rendering page, on): the main view's terrain draw lists are rebuilt only when the
  visible sections, their meshes or GPU placement, a fade-in or the camera's face culling cell changed. F3 counter
  `draw lists reused %`.
- Submit timers: F3 (`submit encode us`, `submit wait us`, `gpu bound`) and the benchmark table (`encode`, `wait`
  columns) show how much of each frame goes into `vkQueueSubmit` (MoltenVK's Metal encoding on the Mac) and into
  waiting for the graphics card.

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
