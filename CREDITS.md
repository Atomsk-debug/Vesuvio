# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:
- Vesuvio memory (`boost/src/main/java/it/cosimo/vesuvio/memory/`), own code following:
  - FerriteCore by malte0811 (MIT, https://github.com/malte0811/FerriteCore): smaller PalettedContainer threading
    detector, block shape and block state cache deduplication, resource location deduplication.
  - ModernFix by embeddedt (LGPL, https://github.com/embeddedt/ModernFix): computing wall shapes once and sharing
    them between wall blocks (here also fences, panes and bars).
