![](https://runelite.net/img/logo.png)
# runelite [![CI](https://github.com/runelite/runelite/workflows/CI/badge.svg)](https://github.com/runelite/runelite/actions?query=workflow%3ACI+branch%3Amaster) [![Discord](https://img.shields.io/discord/301497432909414422.svg)](https://discord.gg/ArdAhnN)

RuneLite is a free, open source OldSchool RuneScape client.

If you have any questions, please join our IRC channel on [irc.rizon.net #runelite](http://qchat.rizon.net/?channels=runelite&uio=d4) or alternatively our [Discord](https://runelite.net/discord) server.

## Project Layout

- [cache](cache/src/main/java/net/runelite/cache) - Libraries used for reading/writing cache files, as well as the data in it
- [runelite-api](runelite-api/src/main/java/net/runelite/api) - RuneLite API, interfaces for accessing the client
- [runelite-client](runelite-client/src/main/java/net/runelite/client) - Game client with plugins

## Usage

Open the project in your IDE as a Gradle project, and then run the RuneLite class in runelite-client.  
For more information visit the [RuneLite Wiki](https://github.com/runelite/runelite/wiki).

### License

RuneLite is licensed under the BSD 2-clause license. See the license header in the respective file to be sure.

## Contribute and Develop

Please view our [Developer Guide](https://github.com/runelite/runelite/wiki/Developer-Guide) on the RuneLite Wiki.

## Motherlode Miner notes

- Dragon pickaxe special: skipped for a whole run when the sack has room for 12 or fewer pay-dirt (capacity minus sack contents and pay-dirt still washing; the current inventory is not counted). Normal use comes back on the run after the sack is emptied.
- Water wheel repair: as soon as the crate hammer lands in the inventory, the crate hover is cleared and the mouse goes straight to the broken strut. If the strut is clickable on screen it is clicked once and the mouse stays still while walking; otherwise the script walks via the minimap and clicks on arrival.

## Reloading routines while the client runs

The panel's **Reload** button does not run Gradle; Gradle can't rebuild while the client is running. Instead it:

1. Finds the routine sources. It checks the **Routine source directory** panel field, then `-Drsbuddy.routineSources` (or the previous `-Drsbuddy.scriptSources`), then `rsbuddy/src/main/java` walking up from the client's working directory, then `src/main/java` next to the plugin's `build` folder. The field accepts `rsbuddy/src/main/java`, the `rsbuddy` module folder, or the repo root.
2. Compiles every file in `net/runelite/rsbuddy/routines` in-process with the JDK compiler (`javax.tools`) into a new temp folder, using the running client's classpath.
3. Loads the selected routine from that folder with a child-first class loader (routines package only), stops it if it was running, and swaps it into the panel. Press Start to run the new code with the current settings.

Compile errors (first 3) show in the panel and the log with a `[Reload]` prefix. The client must run on a JDK, not a JRE. Only classes in the `routines` package are reloaded. Changes to shared helpers (GameInput, Routine, walker, inventory, plugin, panel, config) still need a normal build and a client restart.
- No hovering while running (all legs: sack, bank chest, hopper, ladder, crate, strut). While the player moves, the mouse does nothing and any aim or follow target is dropped (logged as `... dropping the X target`). After a click, the script waits about 1.3s for the walk to start rather than aiming at the same target again. The sack target is dropped as soon as the sack search finishes.

Reload class loading: routine classes load child-first from the fresh compile. Everything else (RuneLite API, client, shared rsbuddy code) resolves through the plugin's loader first, then the loaders that defined `Client`, `EquipmentInventorySlot`, `ConfigManager` and `Routine`, then the thread's context loader. Routine failures log their cause on one line: `Routine X failed: <error> caused by <root> at <frame>`.

## Input backends (OS hardware only, no Java fallback)

Every mouse move, click, scroll and key goes through an OS-level backend:

- **Linux X11 (XTest)**: libXtst via JNA (XFCE or any X11 session). Needs `DISPLAY` and the XTEST extension (`libxtst6`).
- **Windows SendInput**: Win32 `SendInput` via JNA user32. Absolute virtual-desktop mouse coordinates (DPI-aware) and scan-code keys (`KEYEVENTF_SCANCODE`). Untested on real Windows.

Detection runs when the plugin starts. Auto picks the backend by OS and runs a harmless check: X11 opens the display and queries XTEST, Windows loads user32 and reads the cursor position. The result is logged as `[Input] using <backend>`, shown in the side panel (green or red), and cached. If a backend call throws, it is dropped, and the next routine Start detects again. Override it with the panel dropdown or the wrench config **Input backend** (Auto / Linux X11 / Windows SendInput); the two stay in sync. Java input (AWT Robot, synthetic AWT events) is never used. With no backend, routines refuse to start with an `[Input]` error.
