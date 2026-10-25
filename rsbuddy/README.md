# RSBuddy

RSBuddy is a RuneLite developer plugin for building and running small autonomous scripts. It is a
separate Gradle module and is loaded from RuneLite's sideloaded plugin directory.

## Build and run

```sh
./gradlew :rsbuddy:installPlugin
./gradlew :client:run
```

`:client:run` depends on `:rsbuddy:installPlugin`, so it builds the plugin and copies
`rsbuddy.jar` to `~/.runelite/sideloaded-plugins` before starting the client. The client must run
with `--developer-mode` (the `run` task already passes it) for sideloaded plugins to load.

The plugin appears as **Test Plugin**. Its panel is available from the RSBuddy icon in the
sidebar.

## Layout

| Package | Purpose |
| --- | --- |
| `net.runelite.rsbuddy.input` | Human-like mouse movement, camera control, clicks, keys, and menu interactions |
| `net.runelite.rsbuddy.inventory` | Inventory slot, fish, and tab helpers |
| `net.runelite.rsbuddy.target` | Finding nearby objects, NPCs, and widgets |
| `net.runelite.rsbuddy.script` | Script base class, context, and tick-driven manager |
| `net.runelite.rsbuddy.scripts` | Built-in and example scripts |
| `net.runelite.rsbuddy.plugin` | Plugin, config, sidebar panel, and overlay |

## Writing a script

Extend `Script`, implement `onGameTick`, and register the instance in `TestPlugin.startUp`:

```java
public final class ExampleScript extends Script
{
    public ExampleScript()
    {
        super("Example", "Clicks a nearby tree");
    }

    @Override
    protected void onGameTick()
    {
        ScriptContext context = getContext();
        if (!context.isLoggedIn())
        {
            return;
        }

        context.getTargets()
            .findObject("Tree", "Chop down", 10)
            .ifPresent(object -> context.getInput().click(object));
    }
}
```

Scripts tick once per game tick. Keep each tick short and use a cooldown or a small state machine
for actions that take several ticks.

Override `getOverlayLines()` to add your own counters or progress to the status overlay. The
Barbarian Fisher & Cooker uses this for fish totals, per-hour rates, and fishing/cooking XP
remaining and time-to-level estimates:

```java
@Override
public List<String> getOverlayLines()
{
    return Arrays.asList("Trout: " + trout, "Bowls: " + bowls);
}
```

`ScriptContext` exposes:

- `getClient()`
- `getPlayer()` and `getPlayerLocation()`
- `isLoggedIn()`
- `getTargets()` for object, NPC, and widget lookup
- `getInventory()` for counting and finding fish and clicking inventory slots
- `getInput()` for human-like clicks, menu interactions, camera turning, keys, and tile walking

Clicks are animated along a curved path with variable speed and jitter, and `input.isBusy()` is true
while the simulated mouse is moving. Use `input.isVisible(npc)` or
`targets.findVisibleNpc(id, distance)` when a target must be on screen before clicking. If it is not
visible, call `input.turnCameraToward(worldPoint)` and wait a couple of ticks. It simulates holding
the left or right arrow key, never sets the camera yaw directly, and releases the key automatically
or when `input.stopCamera()` is called. For objects that may be ground objects, such as fires, use
`targets.findTileObject(id, distance)` and `targets.findVisibleTileObject(id, distance)`; these search
every tile-object layer.

While the plugin is enabled, the overlay draws an X at the current game mouse position and an
expanding ring at each click, so real mouse movement and clicks are always visible. While a script
is running, a second X shows the simulated mouse position, which makes it easy to compare the two.
Simulated clicks show the same blue/red rings as real clicks.

## Custom scripts

`net.runelite.rsbuddy.scripts.CustomScript` is a starter script intended to be edited. Change its
constants and `onGameTick` logic, rebuild the plugin, then load it from the panel:

```sh
./gradlew :rsbuddy:installPlugin
```

Restart the client so the plugin loads the rebuilt class, then select **Custom Script** in the
panel and press **Start**.

The panel's **Custom script** field accepts a fully-qualified class name of any class that:

- extends `Script`
- has a public or accessible no-arg constructor

Press **Load** and the script is registered in the selector. The class name is saved in the plugin
config (`customScriptClass`) and is loaded automatically on the next plugin start. To load a new
script, create another class in the module (or copy `CustomScript.java`), rebuild with
`./gradlew :rsbuddy:installPlugin`, restart the client, and enter its class name in the panel.

For example:

```java
package net.runelite.rsbuddy.scripts;

import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

public final class MyScript extends Script
{
    public MyScript()
    {
        super("My Script", "Does my thing");
    }

    @Override
    protected void onGameTick()
    {
        ScriptContext context = getContext();
        if (!context.isLoggedIn())
        {
            return;
        }

        // Your logic here.
    }
}
```

## Barbarian Fisher & Cooker

`BarbarianFisherCookerScript` is registered automatically and appears in the panel as
**Barbarian Fisher & Cooker**. Stand near the Barbarian Village fishing spot, make sure the fly
fishing rod and feathers are in your inventory, and start the script.

It will:

1. Left-click fishing spot NPC `1526` with **Lure** until the inventory is full.
2. Click fire object `43475`, then press space to select the first raw fish and cook it all. The
   **All** option is used as a fallback if the quantity interface stays open.
3. Click the fire again and repeat for the second fish type.
4. Left-click each trout, salmon, and burnt fish in the inventory to drop it, two per game tick.

If the fishing spot or fire is not visible, the camera is turned toward it first. The script never
walks; it expects you to already be at the spot.

The status overlay shows the current session's trout and salmon caught/cooked totals with per-hour
rates, plus the remaining XP and estimated time to the next Fishing and Cooking level.

## Panel and commands

The sidebar panel lists registered scripts and provides Start/Stop controls with live status and
the selected script's description.

In-game commands:

```text
::rsbuddy status
::rsbuddy list
::rsbuddy start [name]
::rsbuddy stop
```

Target object, action, and search distance are configured in the plugin settings. Config-backed
scripts are recreated from the current settings when started from the panel.
