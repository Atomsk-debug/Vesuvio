# Vesuvio settings

Every setting lives in Options > Video Settings > **Vesuvio** and in `config/vesuvio.properties`.
This page fills in as each optimization lands.

## General

| Setting | Default | What it does |
|---|---|---|
| Vesuvio (`enabled`) | On | Master switch, also on **F8**. Off makes every part behave like vanilla, live. |
| `disabledMixins` (file only) | empty | Comma-separated names of Vesuvio's hooks to skip at start-up, for troubleshooting. |

## Rendering

| Setting | Default | What it does |
|---|---|---|
| Leaf culling (`leafCulling`) | Smart | Hides leaf faces buried inside a canopy (only with cutout leaves on). Off: vanilla. Smart: a face goes only when the next three blocks that way are leaves or solid, so trees look the same from outside. Aggressive: two blocks, a bit faster, canopies a little more see-through. All: every face between two leaves, trees become hollow shells. Applies live; the world rebuilds once. |
