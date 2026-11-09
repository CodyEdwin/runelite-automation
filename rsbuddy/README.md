# RSBuddy

RSBuddy is a RuneLite plugin that adds a routine library: a sidebar of built-in routines you can
search, favorite, configure, and start. It is a separate Gradle module and is loaded from RuneLite's
sideloaded plugin directory.

## Build and run

```sh
./gradlew :rsbuddy:installPlugin
./gradlew :client:run
```

`:client:run` depends on `:rsbuddy:installPlugin` and `:xplogger:installPlugin`, so it builds both
plugins and copies `rsbuddy.jar` and `xplogger.jar` to `~/.runelite/sideloaded-plugins` before starting
the client. The client must run
with `--developer-mode` (the `run` task already passes it) for sideloaded plugins to load.

The plugin appears as **RSBuddy**. Its sidebar icon tooltip and panel title are **RSBuddy**. The
panel is the Routine Library: a search field and a list of every registered routine.

## Layout

| Package | Purpose |
| --- | --- |
| `net.runelite.rsbuddy.input` | Human-like mouse movement, camera control, clicks, keys, and menu interactions |
| `net.runelite.rsbuddy.inventory` | Inventory slot, fish, and tab helpers |
| `net.runelite.rsbuddy.target` | Finding nearby objects, NPCs, and widgets |
| `net.runelite.rsbuddy.routine` | Routine base class, context, and tick-driven manager |
| `net.runelite.rsbuddy.routines` | Built-in routines |
| `net.runelite.rsbuddy.plugin` | Plugin, config, sidebar panel, and overlay |

## Writing a routine

### Architecture

```
Routine -> TaskRoutine -> your tasks          (what to do)
Actions facade -> Aim / Interact / Movement / Bank / Keys / Waits / ActionLog
  -> GameInput (human mouse paths, physical clicks only)
    -> hardware backend (X11 XTest or Windows SendInput)
```

| Module (routine/) | Purpose |
|---|---|
| `Actions` | Facade every routine uses: `actions.clickObject(...)`, `actions.walkTo(...)` etc. |
| `Aim` | Dead-centre target, follows drift, drops aim while moving, 80-200 ms reaction |
| `Interact` | Verified real clicks on objects/NPCs/widgets/items, right-click options, recovery |
| `Movement` | Minimap walking, `isMoving`, click-on-screen-or-walk (`goAndClick`) |
| `Bank` | Open nearest bank, deposit all, withdraw |
| `Keys` | Key presses, Make-All / make option |
| `Waits` | `waitUntil` with timeout, `isAnimating` |
| `ActionLog` | `[Prefix]` logs and `+Nms` timing between actions |
| `TaskRoutine` | Priority task list and tick loop |
| `RoutineSetting` | Declared settings, drawn in the panel and stored in config |

### Steps

1. Copy `routines/ExampleRoutine.java` (a template, not registered) to `routines/MyRoutine.java`.
2. Set the name/description in the no-arg constructor.
3. Declare settings in `getRoutineSettings()` (keys prefixed with your routine name).
4. Add tasks in `setup()`, highest priority first; each calls one `actions.*` method.
5. Register it in `RoutineLibrary` (or load it as a jar, below) and press Reload.

All clicks are real, visible and verified; never call `menuAction` yourself.


Extend `Routine`, implement `onGameTick`, and register it in `RoutineLibrary.create`:

```java
public final class ExampleScript extends Routine
{
    public ExampleScript()
    {
        super("Example", "Clicks a nearby tree");
    }

    @Override
    protected void onGameTick()
    {
        RoutineContext context = getContext();
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

Routines tick once per game tick. Keep each tick short and use a cooldown or a small state machine
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

`RoutineContext` exposes:

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
expanding ring at each click, so real mouse movement and clicks are always visible. While a routine
is running, a second X shows the simulated mouse position, which makes it easy to compare the two.
Simulated clicks show the same blue/red rings as real clicks.

## XP overlays

XP Logger is a separate plugin in the `xplogger` module. RSBuddy only draws **XP Progress**, and only while the active routine reports progress bars.

Those bars are 2.5x thicker than the original. The animation has moving energy stripes and twirling embers. When progress increases, a white sheen sweeps to the end of the fill, then the flash, an end-of-bar glow and the spark burst fire there. The glow fades out with the sparks.

- **Move:** hold **Shift** and drag the overlay. An orange outline shows while Shift is held over it. The spot is saved through RuneLite's overlay settings. Alt-drag works too.
- **Config:** *XP bar animations* (on/off), *XP bar thickness %* (100–400, default 250), *XP spark intensity %* (0–200, default 100).

## Routine jars

Routines can be shipped as jars and loaded like sideloaded plugins.

**Loading.** On startup, on every Reload and on **Rescan** (Developer section), RSBuddy scans the
routine jar folder (`~/.runelite/rsbuddy/routines` by default; change it in the panel's
"Routine jar folder" field or the *Routine jar folder* config). Each jar gets its own class
loader whose parent is the plugin loader. Routines are found from
`META-INF/services/net.runelite.rsbuddy.routine.Routine`; a jar without it is scanned for public
concrete `Routine` subclasses with a public no-arg constructor. They show in the list as
`Name (jar)`, get the same physical-click lock and the same `RoutineSetting` form as built-ins.
Added, changed and removed jars are picked up on rescan; a routine running from a removed or
changed jar is stopped and its loader closed. A broken jar is logged with `[JarRoutines]` and
skipped. Reload on a jar routine just rescans (replace the jar, then press Reload).

**Making a jar in the client (no Gradle).** Open a routine's settings, press **Export as jar**.
RSBuddy compiles the routine sources (same source directory as Reload) and writes
`<routines folder>/<ClassName>.jar` with the class, its nested classes and the services file,
then rescans.

**Making a jar with Gradle.**

```
./gradlew :rsbuddy:routineJar -Proutine=MyRoutine             # -> rsbuddy/build/routine-jars/MyRoutine.jar
./gradlew :rsbuddy:routineJar -Proutine=MyRoutine -PinstallRoutine   # also copies it into ~/.runelite/rsbuddy/routines
```

`-Proutine` takes a simple name in `net.runelite.rsbuddy.routines` or a fully qualified class.

**Limits.** A jar holds one routine class plus its `$` nested classes. Helper classes in other
files must already be in the plugin (or packaged by hand). Classes in the jar load child-first, so
they cannot use package-private members of plugin classes in the same package. A jar can not
replace the framework (`net.runelite.rsbuddy.*` outside `routines`). The settings appear in the
RSBuddy panel only, not RuneLite's config page.

## Custom routines

`net.runelite.rsbuddy.routines.CustomScript` is a starter routine intended to be edited. Change its
constants and `onGameTick` logic, rebuild the plugin, then load it from the panel:

```sh
./gradlew :rsbuddy:installPlugin
```

Restart the client so the plugin loads the rebuilt class, then select **Custom Routine** in the
panel and press **Start**.

The panel's **Custom routine** field accepts a fully-qualified class name of any class that:

- extends `Routine`
- has a public or accessible no-arg constructor

Press **Load** and the routine is added to the library. The class name is saved in the plugin
config (`customRoutineClass`) and is loaded automatically on the next plugin start. To load a new
routine, create another class in the module (or copy `CustomScript.java`), rebuild with
`./gradlew :rsbuddy:installPlugin`, restart the client, and enter its class name in the panel.

For example:

```java
package net.runelite.rsbuddy.routines;

import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineContext;

public final class MyScript extends Routine
{
    public MyScript()
    {
        super("My Routine", "Does my thing");
    }

    @Override
    protected void onGameTick()
    {
        RoutineContext context = getContext();
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
fishing rod and feathers are in your inventory, and start the routine.

It will:

1. Left-click fishing spot NPC `1526` with **Lure** until the inventory is full.
2. Click fire object `43475`, then press space to select the first raw fish and cook it all. The
   **All** option is used as a fallback if the quantity interface stays open.
3. Click the fire again and repeat for the second fish type.
4. Left-click each trout, salmon, and burnt fish in the inventory to drop it, two per game tick.

If the fishing spot or fire is not visible, the camera is turned toward it first. The routine never
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
if the key is not in the inventory after depositing, the routine logs a `[HillGiant]` warning and
withdraws one from the bank. If the bank has none either, it stops at the bank with the status
"Stopped: no brass key in inventory or bank" instead of walking back without a key.

- Uses the fast mouse profile (`Routine.useFastMouse()`): a 90-300 ms reaction delay, then a quick,
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

`GemCrabKillerRoutine` is registered automatically and appears in the panel as **Gem Crab Killer**.
Start it next to the Gemstone Crab (NPC `14779`) or its cave.

It will:

1. Left-click the Gemstone Crab and wait, without re-clicking, until it dies.
2. Wait about 5 seconds, then click the nearby **Cave** (**Crawl-through**) exactly once. A crawl
   is only armed by a kill.
3. Wait for the crawl to finish (teleport, or moved/animated then stood still), without clicking
   the cave again. If the click did nothing for about 5 seconds it retries, at most 3 clicks.
4. Wait for the new crab (live `14779`, not dying), never clicking the cave, and attack it the
   same tick it appears. Repeat.

If no crab appears within about 36 seconds, one recovery crawl is allowed. Starting with no crab
nearby waits for a spawn instead of crawling. State changes are logged as
`[GemCrab] OLD -> NEW: reason`. Level-up scrolls are dismissed with Space so they do not stop combat.

If the crab or cave is off screen, the camera is turned toward it first. The overlay shows the
current state and kills per hour.

To avoid the idle logout, every 30 to 60 seconds (re-randomized each time) the routine briefly
holds the left or right arrow key (random direction, 150-450 ms) to rotate the camera a little.
The nudge only happens while the simulated mouse is idle and no menu is open.

If potions are in the inventory, the routine drinks one (lowest dose first) whenever the matching
Strength or Ranged boost is at or below the threshold (default 3), including at the start when
unboosted. Without potions it simply keeps fighting. Which potions it drinks is set in the RSBuddy
plugin's config (RuneLite Configuration, wrench icon, then the "RSBuddy" entry):

- **Gem crab: potions**: `Strength potion` (regular 4-1 doses only), `Super strength`
  (super strength 4-1 doses only: 2440, 157, 159, 161), `Either (prefer super)` (default: super
  strength first, then regular), `Ranging potion` (regular 4-1 doses only), or `Divine ranging
  potion` (divine 4-1 doses only: 23733, 23736, 23739, 23742). The ranged options track the
  Ranged boost instead of Strength. Divine potions are only re-drunk after their five-minute
  boost has expired, never during it.
- **Gem crab: re-drink at boost**: drink again once the matching boost is this many levels or less
  (0-15, default 3).

Settings are read when the routine is started, so change them, then press Start (or Stop and Start).
The overlay shows the mode and threshold next to the potion count.

## Animated Suit Killer (Warriors' Guild)

`AnimatedSuitKillerScript` appears in the panel as **Animated Suit Killer**. Start it next to a
Magical Animator (object `23955`, `ObjectID.WARGUILD_ANIMATOR_CENT`) with the chosen metal's full
helm, platebody and platelegs in the inventory.

**Choosing the armour:** RuneLite Configuration (wrench icon) → **RSBuddy** → tick one of
**Animated suit: Black**, **Mithril**, **Adamant** or **Rune**. Ticking one unticks the others. If
none (or somehow more than one) is ticked, the routine will not run and shows "Tick exactly one
armour type". Settings are read on Start.

| Type | Animated NPC | Full helm | Platebody | Platelegs |
| --- | --- | --- | --- | --- |
| Black | 2453 | 1165 | 1125 | 1077 |
| Mithril | 2454 | 1159 | 1121 | 1071 |
| Adamant | 2455 | 1161 | 1123 | 1073 |
| Rune | 2456 | 1163 | 1127 | 1079 |

Warrior guild tokens are item `8851` (stackable); its stack-appearance ids 8852-8855 are accepted too.

Loop:

1. Check the 3 pieces are in the inventory. Missing pieces lying within 8 tiles are picked up
   first; otherwise it stops with a status naming what is missing.
2. Click **Animate** on the animator once and wait (up to 20 ticks) for the armour to appear. Three
   Animate clicks in a row without a spawn stops the routine.
3. Fight it. The armour attacks you on its own; the routine only clicks Attack if you are not
   fighting it after a short 3-tick grace. A fight is capped at 200 ticks.
4. Loot with left clicks only, the mouse glued to the pile: the cursor sits on a fixed random
   spot on the pile (never the dead centre) and stays there while the player walks over and the
   camera moves. Whenever the hovered top option is **Take** for any of our items on that tile
   (armour piece or tokens) it left-clicks, and after each pickup it clicks again 80-250 ms later.
   The left-click option is read the way the client sorts the menu (deprioritized rows such as
   Examine are skipped). Once the cursor has settled on the tile it clicks when that option is
   Take for any of our items; if no Take shows after 300 ms settled it left-clicks the tile anyway
   and checks the result (an item gone, the inventory changed, or the player walking to it). Only
   after a click that picked nothing up does it aim at another point on the tile (alternating near
   the centre and elsewhere). The aim point only moves when the tile itself moves by more than a
   few pixels, so the cursor never wanders. There is no right-click or menu-free Take: 3 failed
   clicks log it and skip that pile for this
   round, and 25 ticks without any pickup gives up. If pieces are then still on the ground, the
   armour check loots them again (up to 3 rounds) instead of stopping. Every 5 frames while
   hovering it logs `hover pile=… cursor=… target=… dist=… top='…' -> decision`. An off-screen pile gets one small camera turn.
   Drops are searched within 3 tiles of the death tile and of the player. The round only ends once
   the Warrior guild tokens are in too (it waits up to 4 extra ticks for them). The loot start logs
   every nearby ground item (`id x qty @ tile own=ownership`), and every click logs
   `[AnimatedSuit loot] pile=x,y click #n point=x,y top='Take ...'`.
5. Repeat.

Priority each tick: **eat > drink > fight/loot/animate**.

- **Eating:** below 50% HP it eats any inventory item that has an **Eat** option (any food; never
  the armour pieces or tokens), in every state, including mid-fight. After each bite it waits for
  HP to rise or the food to leave the inventory (up to 4 ticks) before eating again.
- **Low HP, no food:** it keeps fighting the current suit and logs a warning, but will not animate
  a new suit; it waits ("Low HP, no food: waiting to regenerate") until HP is back to 50% or food
  is added.
- **Strength potions:** RSBuddy → **Animated suit: strength potions**: `Off`, `Strength potion`,
  `Super strength`, or `Either (prefer super)` (default). **Animated suit: drink when boost below**
  (1-16, default 5): drinks the lowest dose first whenever the Strength boost is below that number
  (default: at a boost of 4 or less, including unboosted).
  Without potions it simply keeps going.

Mouse movement uses the fast, attentive profile (short curved paths, random click points, short
varied reaction delays), like the Hill Giant routine. The overlay shows status, armour type, kills/hr, tokens (and tokens gained) and food
eaten, and the potion mode with potions drunk. State changes log `[AnimatedSuit] OLD -> NEW: reason`; more than 5 ticks in one state logs a
`stall?` line (repeated every 20 ticks). Stuck mouse, camera or menus are reset on a tick cap. This
routine has no random anti-idle camera nudges; the camera only turns to bring an off-screen pile,
animator or armour into view.

Pile tracking is thread-safe: every frame (`ClientTick`, client thread) `GameInput` recomputes the
pile's screen point and the hover check and publishes them through volatile fields; the mouse timer
only reads that point, steers the cursor every 8 ms and presses when a click is requested. It never
calls the client.

## Bow Fletcher

`BowFletcherScript` appears in the panel as **Bow Fletcher**. Stand next to a bank with a knife
in the **locked first inventory slot** and the logs in the bank.

Settings: select **Bow Fletcher** in the RSBuddy side panel and they show under **Routine settings**
(Bow to make, Amount (0 = unlimited), Bank at closest booth). The same settings are in RuneLite
Configuration → **RSBuddy**; both places stay in sync and are read on Start:

- **Bow fletcher: bow** (default `Longbow (u) - regular logs`): shortbow (u) or longbow (u) for
  regular, oak, willow, maple, yew or magic logs. The logs to withdraw follow from it.
- **Bow fletcher: amount** (default `0` = until the bank has no logs left): stop after this many
  bows. The inventory being fletched when the count is reached may still finish.
- **Bow fletcher: bank at closest booth** (default on): open the closest bank, whether a booth
  (Grand Exchange booths included), bank chest or banker NPC (e.g. the GE Banker, id 1613). Off:
  prefer a banker NPC, then a booth or chest.

Settings are read on Start.

In-game setup it relies on: left click on the logs in the bank set to **Withdraw-All**, and the
knife's left click set to **Use**. The make menu's quantity should already be **All**.

Loop (every click is a left click, made only after the hovered left-click option is checked):

1. Open the bank (booth/chest: `Bank`/`Use`; banker: `Bank`). If that left click never shows the
   right option after 3 tries it runs the exact Bank action instead. Off-screen targets get one
   camera turn.
2. **Deposit inventory** (the locked knife stays), then left-click the logs (`Withdraw-All`).
3. **Escape** to close the bank (the close button if Escape does nothing).
4. Left-click the knife (`Use`), then the logs (`Use Knife -> Logs`).
5. In the make menu it finds the option for the chosen bow by its item/name (no hard-coded
   positions) and presses its number key (1 = first option). Once that option has been seen
   working, later batches press **Space**, which repeats the last-made option.
6. While fletching (animation / log count going down) it waits. A level-up is caught on the tick
   it happens (Fletching level goes up or the level-up dialog opens); otherwise 2 ticks with no
   animation and no log used counts as stopped (3 before the first log). It then uses the knife
   on the logs again right away: each step (knife, logs, make menu) follows as soon as the mouse is
   free plus a 60-140 ms pause, instead of waiting for the next game tick.
7. When the logs are gone it banks and repeats.

It stops with a clear status when the amount is reached, the bank has no logs left, the knife is
missing, the knife/logs left click is not `Use`/`Withdraw-All`, or a step keeps failing. Every wait
is tick-capped. Logs are prefixed `[BowFletcher]` (state changes, every click with the hovered
top option, bank and make-menu steps).

## Gem Cutter

`GemCutterScript` appears in the panel as **Gem Cutter**. Built for the Grand Exchange bank booths
(works at any bank). Keep a chisel in the **locked first inventory slot** and the uncut gems in the
bank. It works like the Bow Fletcher.

Settings: select **Gem Cutter** in the RSBuddy side panel and they show under **Routine settings**
(Gem to cut, Amount (0 = unlimited), Bank at closest booth). The same settings are in RuneLite
Configuration → **RSBuddy**; both places stay in sync and are read on Start:

- **Gem cutter: gem** (default `Sapphire`): Opal, Jade, Red topaz, Sapphire, Emerald, Ruby,
  Diamond, Dragonstone, Onyx or Zenyte.
- **Gem cutter: amount** (default `0` = until the bank has no uncut gems left). The inventory
  being cut when the count is reached may still finish.
- **Gem cutter: bank at closest booth** (default on): open the closest bank, whether a booth
  (Grand Exchange booths included), bank chest or banker NPC (e.g. the GE Banker, id 1613). Off:
  prefer a banker NPC, then a booth or chest.

In-game setup: the uncut gem's bank left click set to **Withdraw-All**, the chisel's left click set
to **Use**, make-menu quantity **All**.

Loop (left clicks only, each after checking the hovered left-click option): open the bank,
**Deposit inventory** (chisel stays), left-click the uncut gems, **Escape**, left-click the chisel
then an uncut gem, pick the cut gem in the make menu (its number key first, then **Space** once
confirmed), wait while cutting. A single uncut gem that cuts without the make menu is handled too.
A level-up (Crafting level up or the level-up dialog) is caught on the tick it happens, or 2
ticks with no animation and no gem cut → chisel on gem again right away (same fast restart as the
Bow Fletcher, shared `SkillInterruptWatcher`). When the uncuts are gone it banks
and repeats. Stops with a clear status when the amount is reached, the bank has no uncuts, the
chisel is missing, a left click is not `Use`/`Withdraw-All`, or a step keeps failing. Logs are
prefixed `[GemCutter]`.

Bank detection (shared by Bow Fletcher and Gem Cutter, `target/BankFinder`) scans every object
layer within 20 tiles with impostors resolved: any object with a **Bank** option (bank booth, Grand
Exchange booth, counter, chest) or a bank chest with **Use**, plus bankers (id 1613, named Banker,
or any NPC with **Bank**). It logs the candidates it picked from, and if nothing matches it logs the
nearby bank-like objects, other objects and NPCs before stopping.

## Edgeville Smelter

`EdgevilleSmelterScript` appears in the panel as **Edgeville Smelter**. Keep the ores for the chosen
bar in the first bank tab and start the routine at the Edgeville bank.

Settings: select **Edgeville Smelter** in the RSBuddy side panel and choose the bar under
**Routine settings** (Bronze, Iron, Steel, Silver, Gold, Mithril, Adamantite or Runite). The same
setting is in RuneLite Configuration → **RSBuddy**. It is read on Start.

Recipes and ores per bar:

- Bronze: copper ore + tin ore
- Iron: iron ore
- Steel: iron ore + 2 coal
- Silver: silver ore
- Gold: gold ore
- Mithril: mithril ore + 2 coal
- Adamantite: adamantite ore + 4 coal
- Runite: runite ore + 6 coal

Loop:

1. Open the Edgeville bank and **Deposit inventory**. For Steel, the routine left-clicks iron ore
   (set it to **Withdraw-9**) and then coal (set it to **Withdraw-All**). Other bars use the bank's
   Withdraw-10/5/1 actions.
2. Click the visible Edgeville furnace while the bank is still open. If the bank covers it, the
   routine presses **Escape** and clicks it instead of walking by minimap.
3. When the smelting menu opens, Steel presses **2**; the other bars are picked by item/name. The
   routine clicks **All** if the quantity menu stays open.
4. Wait while the ores are used. A level-up dialog is dismissed; if the batch stops early, the
   furnace is clicked again.
5. When the metal ore is gone, walk back to the bank, deposit the bars and any leftover coal, and
   repeat until the bank runs out of the required ores.

The make-menu quantity should be **All** (the routine also clicks the All button when it can).
Ores must be in the first bank tab so the withdraw actions can see them. Stops with a clear status
when the bank lacks an ore or has too little for one bar, the bar is not available at the current
Smithing level, or the bank cannot withdraw the ores.

## West Varrock Smither

`WestVarrockSmitherRoutine` appears in the panel as **West Varrock Smither**. Put a hammer in the
**locked first inventory slot**, set the bank left click on the selected metal's bars (or regular
keel parts for large keel parts) to **Withdraw-All**, and start the routine at the Varrock west bank.

Before starting, smith the chosen item once manually so the anvil remembers it. On later trips the
routine opens the anvil menu and presses **Space** to repeat that last item instead of find-clicking
it. Large keel parts use the menu keys instead: 1 bronze, 2 iron, 3 steel, 4 mithril, 5 adamant,
6 rune (Space also repeats bronze). The anvil quantity is always **All**; if the menu stays open it
clicks the All button.

Settings: select **West Varrock Smither** in the RSBuddy side panel and choose the metal and item
under **Routine settings**. The same settings are in RuneLite Configuration → **RSBuddy** and are
read on Start.

- **West Varrock smither: metal** (default `Steel`): Bronze, Iron, Steel, Mithril, Adamant or Rune.
- **West Varrock smither: item** (default `Keel parts`): Dagger, Sword, Longsword, 2h sword,
  Scimitar, Mace, Warhammer, Axe, Battleaxe, Claws, Chainbody, Platelegs, Plateskirt, Platebody,
  Med helm, Full helm, Sq shield, Kiteshield, Keel parts or Large keel parts. The routine withdraws
  the right number of bars for the item (1, 2, 3 or 5 per item; keel parts use 5). Large keel parts
  are made from 5 regular keel parts each: choose **Large keel parts** and keep the regular keel
  parts in the first bank tab. It withdraws a full inventory of regular keel parts and makes as many
  large parts as possible.

Loop:

1. Open the nearest bank and deposit the previous trip's products. The locked hammer stays in slot 1.
2. Left-click the bars, or regular keel parts for large keel parts, in the bank so
   **Withdraw-All** fills the inventory. Click the anvil while the bank is still open; the bank is
   only closed manually if the anvil is not on screen.
3. Press **Space** in the anvil menu to repeat the last smithed item. For large keel parts, press
   1 for bronze, 2 for iron, 3 for steel, 4 for mithril, 5 for adamant, or 6 for rune. If the menu
   stays open, click **All**.
4. Wait while the bars are used. A level-up dialog is dismissed; if the batch stops early, the anvil
   is clicked and the item key is pressed again.
5. When the inventory batch is done, walk back to the bank and repeat. Stops with a clear status when
   the bank has no input for the selected item or the All button cannot be found.

The bars or regular keel parts must be in the first bank tab with **Withdraw-All** as their left
click. The routine does not drop anything, so keep unrelated inventory items to a minimum.

## Motherlode Miner

`MotherlodeMinerScript` (panel: **Motherlode Miner**, setting **Motherlode: upstairs**) mines
pay-dirt, deposits it in the hopper, empties the sack and banks the ores.

- **Sack:** keeps searching the sack and banking until the sack varbit
  (`MOTHERLODE_SACK_TRANSMIT`) has read 0 for 2 ticks and the inventory is banked; only then does
  it go back upstairs / to the veins. A missed click never counts as "sack empty". Three sack trips
  in a row that get nothing while the sack still shows ore stop the routine with a clear status.
- **Clicks:** the sack and ladder use a verified left click: curved move onto the dead centre of the
  object's hull, then (next tick) the centre is recomputed and the click is sent only if the cursor
  is still on the hull, within 6 px of the centre, and the top option is `Search … Sack` /
  `Climb … Ladder`. After 4 misses it turns the camera and walks a step closer, then re-aims; after 3 such
  recoveries it halts with a log. It never runs an action without a visible click. Ladder: up to 8 clicks, each
  waits for the floor change before re-clicking. Veins, hopper, strut, crate and bank chest are
  clicked at their hull centre too (no random offset).
- **Sack full:** sack space = 108 (189 with the bigger sack) minus the sack varbit and pay-dirt
  still washing. If the hopper takes only part of a load, or the sack is already full, the rest of
  the pay-dirt is dropped (first drop verified as `Drop Pay-dirt`, left-click Drop) and it goes
  to empty the sack (`[MotherlodeMiner] sack full, dropping N extra pay-dirt`).
- **Dragon pickaxe special** (setting **Motherlode: use dragon pickaxe special**, default off; also
  in the side panel as *Use dragon pickaxe special*): while mining, whenever special energy
  (varp 300) is 100%, the mouse moves to the centre of the special attack orb, the left click is
  verified as the special attack, and it clicks it (`[MotherlodeMiner] spec at 100%, using dragon
  pickaxe special`), then waits for the energy to drop; up to 6 tries per charge. Needs a dragon
  pickaxe (11920, 12797, 23677, 25376) wielded; if it is only in the inventory or missing, it logs
  once and skips.
- **Speed:** no wait-after-click ticks and no waiting to fully stop: each step runs the moment
  its trigger is met (ores out of the sack -> walk to the bank the same tick; bank open -> deposit;
  inventory empty -> minimap click toward the sack with no Escape, the bank closes itself). A
  per-frame hook fires verified clicks 80-200 ms after the mouse arrives. The mouse never chases the sack
  or bank chest while walking: if it is clickable on screen while standing still it is clicked
  once directly (the click walks there); otherwise it minimap-walks, leaves the mouse idle and
  hovers + clicks on the tick the player arrives. Each action logs
  `[MotherlodeMiner] +Nms <action> (after <previous>)`.
- **Camera:** no random camera nudges; it only turns when a target is off screen.
- **Physical clicks only:** Motherlode Miner, Bow Fletcher and Gem Cutter run with
  `GameInput.setPhysicalOnly(true)`: every helper that would run a menu action directly
  (`client.menuAction`) refuses and logs `[GameInput] refused direct menu action`. Bank chest,
  hammer withdraw, sack, ladder, hopper, veins, strut and crate are all hover → verify top
  option → real click; each bank-open attempt logs the cursor vs the hull centre.
- Log lines start with `[MotherlodeMiner]`.

## Panel and commands

The sidebar is a Routine Library, laid out like RuneLite's plugin list: a **Search routines** field,
then one row per routine with its name, short description, favorite star, start/stop switch, and
configure button. Favorites are stored in RuneLite config (`favoriteRoutines`) and sort to the top.
Adding a routine is a `RoutineRegistry.register` call in `RoutineLibrary`; the panel is not wired
per routine.

In-game commands:

```text
::rsbuddy status
::rsbuddy list
::rsbuddy start [name]
::rsbuddy stop
```

Target object, action, and search distance are configured in the plugin settings. Config-backed
routines are recreated from the current settings when started from the panel.

Shed return trip (strict order): walk on the minimap to the door's outside tile (3115, 3449), right-click the door and pick its exact Open entry, then latch only once the player has crossed from outside into the shed interior box (x 3113-3117, y 3450-3453, plane 0, derived from client logs; matches the wiki footprint) or stands in it north of the door row. A latch with the player outside the box is cleared at once and the routine goes back to the door; the ladder is never clicked from outside the box. The ladder is refused until the latch is set. Near the shed, walking uses only minimap clicks and the door/ladder are only clicked via their exact menu entry, so a click can never land on the ladder drawn over the wall. Every ladder click logs its reason under `[HillGiant shed]`.

Shed trip out (strict order, separate from the return trip): arriving on the surface by the dungeon ladder marks the player as inside the shed (no box check). The routine clicks the door once and sends no walk click until the player is south of the door's wall line (y 3449 or less). Only that crossing sets the exit latch; then it walks to the bank. A shed watchdog on both trips logs a `STUCK` line with all state after 10 ticks without movement, animation or a new action, then resets input and counters and releases the trip's latch if the player is on the wrong side.

Trip-out robustness: an out latch found while the player is still inside the shed is cleared at once and the door is clicked. If a door or ladder is off screen and the camera cannot turn to it (it already faces it, or the player stands on its tile), the option is run directly instead of waiting forever. The watchdog covers the whole bank trip: it fires after 10 ticks without movement or animation (20 if clicks keep being sent that do not move the player), logs `STUCK` and makes the next door/ladder click a direct one. Every bank-trip tick logs a `[HillGiant trip]` line, with the reason first so it is never cut off, and exceptions are logged with a stack trace under `[HillGiant]` instead of stopping the routine. Input stuck "busy" is reset after 15 ticks (was 100), and a camera turn after 10.

Loot also includes the Giant key (item 20754, `ItemID.HILLGIANT_BOSS_KEY`) and the Ensouled giant head (item 13474, `ItemID.ARCEUUS_CORPSE_GIANT_INITIAL`). Both are picked up before anything else within a few tiles and banked with the rest of the loot. Grimy irit leaf (item 209, `ItemID.UNIDENTIFIED_IRIT`), Snapegrass seed (item 22879, `ItemID.SNAPE_GRASS_SEED`), and Uncut emerald (item 1621, `ItemID.UNCUT_EMERALD`) are looted at normal priority. With a full inventory, stackable drops that fit onto a stack already held (snape grass seeds, runes) are still picked up before banking.

Every bank step logs a `[HillGiant bank]` line with the stage, attempts and inventory contents.

Loot pickups take one accurate click each, all driven from the routine tick (client thread); the Swing side only replays mouse events. Each attempt first logs a `[HillGiant loot] ... start` line, then hovers the item's freshly computed point. On the next tick it reads the hover menu. If this item's Take is the top option it left-clicks there. If something else is on top it right-clicks and clicks the exact Take row. If the item is no longer under the cursor it runs the exact Take for that item and tile. The next pickup waits at most 2 ticks for the player to settle. Caps: 3 attempts per item, 25 ticks in the loot state without a pickup landing, and 6 ticks of busy input; then it skips the item and goes back to attacking. A `[HillGiant state]` line is logged every tick once the state has not changed for more than 5 ticks.
