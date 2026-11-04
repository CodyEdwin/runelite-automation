# XP Logger

Standalone RuneLite plugin. Session XP for skills you have gained, with a sidebar that controls the overlay.

```sh
./gradlew :xplogger:installPlugin
./gradlew :client:run
```

`:client:run` already depends on `:xplogger:installPlugin`. Sideloaded plugins load only with `--developer-mode` (the `run` task passes it).

Enable **XP Logger** in the plugin list, then open the XP Logger sidebar. The panel persists every option through the plugin config: visibility, corner, Shift-drag (and Alt-drag), colors and opacity, font, title, bar thickness and colors, animation, which skills and which stats to show, padding, row spacing, and icon size. Hold **Shift** and drag the overlay to place it.
