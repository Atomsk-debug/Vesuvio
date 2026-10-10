# Vesuvio credits

Vesuvio is original code under the MIT licence (`LICENSE` in the repository root), with the exceptions
listed below.

## Code ported from other projects

| Vesuvio file | Source | Licence |
|---|---|---|

## Techniques Vesuvio's own code follows

Ideas only, no code copied:
- Settings screen laid out like Sodium's (pages on the left, options list, description box): layout idea only
  (`ui/VesuvioSettingsScreen.java`).
- Adding a key mapping by appending it to `Options.keyMappings` before `Options.load`: the approach Fabric API's
  key binding registry uses (Apache 2.0), idea only, own code (`ui/mixin/OptionsKeyMixin.java`).
