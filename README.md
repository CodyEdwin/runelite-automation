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

## Reloading scripts while the client runs

The panel's **Reload** button does not run Gradle; Gradle can't rebuild while the client is running. Instead it:

1. Finds the script sources. It checks the **Script source directory** panel field, then `-Drsbuddy.scriptSources`, then `rsbuddy/src/main/java` walking up from the client's working directory, then `src/main/java` next to the plugin's `build` folder. The field accepts `rsbuddy/src/main/java`, the `rsbuddy` module folder, or the repo root.
2. Compiles every file in `net/runelite/rsbuddy/scripts` in-process with the JDK compiler (`javax.tools`) into a new temp folder, using the running client's classpath.
3. Loads the selected script from that folder with a child-first class loader (scripts package only), stops it if it was running, and swaps it into the panel. Press Start to run the new code with the current settings.

Compile errors (first 3) show in the panel and the log with a `[Reload]` prefix. The client must run on a JDK, not a JRE. Only classes in the `scripts` package are reloaded. Changes to shared helpers (GameInput, Script, walker, inventory, plugin, panel, config) still need a normal build and a client restart.
