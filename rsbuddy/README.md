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

## Hill Giant (Edgeville)

`HillGiantScript` kills hill giants in the Edgeville Dungeon, loots big bones, talismans, uncut
diamonds, herbs and runes, and banks at Varrock West through the brass-key shed (brass key and
sardines expected).

Banking: keep the brass key in inventory slot 1 and lock that slot (the bank's inventory slot
lock), so the bank's Deposit inventory button leaves the key in place. Banking is just Deposit
inventory (done as soon as no loot items are left; the key, food and anything in a locked slot
are ignored), close the bank, walk back; the key is no longer withdrawn each trip. Safety fallback:
if the key is not in the inventory after depositing, the script logs a `[HillGiant]` warning and
withdraws one from the bank. If the bank has none either, it stops at the bank with the status
"Stopped: no brass key in inventory or bank" instead of walking back without a key.

- Uses the fast mouse profile (`Script.useFastMouse()`): a 90-300 ms reaction delay, then a quick,
  nearly straight movement (about 160 ms for 50 px, 330 ms for 500 px).
- Prefers a giant already attacking you, then the nearest one nobody else is fighting.
- Spots a kill on the tick the death animation starts, moves the mouse onto the next giant while
  the corpse fades, and loots as soon as the drops appear, nearest first.
- Never clicks again while fighting, walking to loot, or climbing.
- The shed trip direction comes from the inventory: full means trip out (climb up, open the door
  once, walk to the bank); free space means the return trip (open the door, go through, climb down).
  After a door click moves the player, a latch blocks every further door click until the player has
  climbed down (or reached the bank on the way out). Only three ladder clicks that don't move the
  player at all, or walking more than 8 tiles from the shed, release it.
- Every shed tick is logged to `~/.runelite/logs/client.log` as `[HillGiant shed] ...`. Each line
  has the location, step, latch, door id and actions, and why a click was chosen. The overlay shows
  the latch and the last reason.

## Gem Crab Killer

`GemCrabKillerScript` is registered automatically and appears in the panel as **Gem Crab Killer**.
Start it next to the Gemstone Crab (NPC `14779`) or its cave.

It will:

1. Attack the Gemstone Crab and wait, without re-clicking, until it dies.
2. Right-click the nearby **Cave** and choose **Crawl-through**.
3. Wait up to about 36 seconds for the crab to spawn at the new location, attack it, and repeat.

If the crab or cave is off screen, the camera is turned toward it first. The overlay shows the
current state and kills per hour.

To avoid the idle logout, every 30 to 60 seconds (re-randomized each time) the script briefly
holds the left or right arrow key (random direction, 150-450 ms) to rotate the camera a little.
The nudge only happens while the simulated mouse is idle and no menu is open.

If strength potions are in the inventory, the script drinks one (lowest dose first) whenever the
Strength boost is 3 or less, including at the start when unboosted. Super strength potions are used
as a fallback. Without potions it simply keeps fighting.

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

Shed return trip (strict order): walk on the minimap to the door's outside tile (3115, 3449), right-click the door and pick its exact Open entry, then latch only once the player has crossed from outside into the shed interior box (x 3113-3117, y 3450-3453, plane 0, derived from client logs; matches the wiki footprint) or stands in it north of the door row. A latch with the player outside the box is cleared at once and the script goes back to the door; the ladder is never clicked from outside the box. The ladder is refused until the latch is set. Near the shed, walking uses only minimap clicks and the door/ladder are only clicked via their exact menu entry, so a click can never land on the ladder drawn over the wall. Every ladder click logs its reason under `[HillGiant shed]`.

Shed trip out (strict order, separate from the return trip): arriving on the surface by the dungeon ladder marks the player as inside the shed (no box check). The script clicks the door once and sends no walk click until the player is south of the door's wall line (y 3449 or less). Only that crossing sets the exit latch; then it walks to the bank. A shed watchdog on both trips logs a `STUCK` line with all state after 10 ticks without movement, animation or a new action, then resets input and counters and releases the trip's latch if the player is on the wrong side.

Trip-out robustness: an out latch found while the player is still inside the shed is cleared at once and the door is clicked. If a door or ladder is off screen and the camera cannot turn to it (it already faces it, or the player stands on its tile), the option is run directly instead of waiting forever. The watchdog covers the whole bank trip: it fires after 10 ticks without movement or animation (20 if clicks keep being sent that do not move the player), logs `STUCK` and makes the next door/ladder click a direct one. Every bank-trip tick logs a `[HillGiant trip]` line, with the reason first so it is never cut off, and exceptions are logged with a stack trace under `[HillGiant]` instead of stopping the script. Input stuck "busy" is reset after 15 ticks (was 100), and a camera turn after 10.

Loot also includes the Giant key (item 20754, `ItemID.HILLGIANT_BOSS_KEY`) and the Ensouled giant head (item 13474, `ItemID.ARCEUUS_CORPSE_GIANT_INITIAL`). Both are picked up before anything else within a few tiles and banked with the rest of the loot.

Every bank step logs a `[HillGiant bank]` line with the stage, attempts and inventory contents.

Loot pickups take one accurate click each. The item's screen point is recomputed on the client thread during the whole mouse move, and the move re-aims (short corrective flicks, up to 4) when the item drifts more than 3 px. Just before pressing, the hover menu decides: a left click if this item's Take is the top option, otherwise a right click and its exact Take row. If the item is not under the cursor at all, its exact Take action is run directly. The next pickup waits (at most 2 ticks) until the player's model has settled on the previous item's tile, so the camera is no longer sliding. Every attempt logs a `[HillGiant loot]` line: item, tile, target point, re-aims and result.
