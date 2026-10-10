# Benchmarking Vesuvio

Vesuvio has its own benchmark: Options > Video Settings > Vesuvio > General > **Run benchmark**.

## Running it
1. Join a world, stand somewhere typical with all chunks loaded, and look the way you usually play.
2. Press **Run benchmark**. The menu closes and the run starts.
3. Don't touch anything until the results screen opens. The action bar shows the step and the time left (about
   5 minutes with the default 10 s steps).

Any key, click, move or turn, a menu opening, minimizing the window or leaving the world stops the run and puts
your settings back. Steps already measured are kept: they go to `logs/latest.log` and, after a key, click or
move, show on the results screen.

## What it measures
Each step settles for at least 3 seconds (longer while chunks are being rebuilt), then measures for
`benchmarkSeconds` (10 by default). The steps are: Vesuvio off, Vesuvio on, then every live setting outside the
General page changed on its own (a switch flipped, a number set to its lowest value), then on and off again. How
far the two "on" (or "off") steps land apart is the noise; differences smaller than that mean nothing.

The results table in `latest.log` has, per step: fps, mean frame time, 95th percentile frame time, the difference
from "on" in ms (a positive number means what that step switched off was saving time), the game's own CPU
frame time, and the end of the frame split in two: **encode**, ms per frame inside `vkQueueSubmit` (on macOS with
MoltenVK this is where the frame is turned into Metal commands, and where it waits for the screen with VSync on;
with Asynchronous Submit on it moves to another thread and this column only shows the hand-over), and **wait**, ms
per frame waiting for the graphics card to finish the frame before last (large when the graphics card is the
limit, near zero when the CPU is). The same two numbers, averaged over the last second, are on F3 as
`submit encode us` and `submit wait us`. The settings are never saved during a run.

## Tips for a fair run
* Use the same window mode each time (exclusive fullscreen is faster on macOS than a normal fullscreen window).
* Turn VSync off and set the frame limit to unlimited, or the numbers stop at your screen's refresh rate.
* Compare runs only with the same spot, render distance, shaders and window size; the first log line of each run
  records them.
