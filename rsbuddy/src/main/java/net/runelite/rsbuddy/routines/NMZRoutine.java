/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rsbuddy.routines;

import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import net.runelite.api.Actor;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineContext;

/**
 * Nightmare Zone: buys a dream from Dominic Onion (Dream, 4, Space, 1), drinks
 * from the vial, accepts, then inside turns quick prayers on, keeps prayer up
 * with prayer potions, grabs power-ups and fights by priority. Never attacks
 * Tanglefoot. Every action waits a random 750-1500 ms (never a round value).
 */
public class NMZRoutine extends Routine
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(NMZRoutine.class);

	private static final long EXTRA_DELAY_MS = 1000L;
	private static final long DELAY_MIN_MS = 750L;
	private static final long DELAY_MAX_MS = 1500L;
	private static final long ATTACK_DELAY_MIN_MS = 500L;
	private static final long ATTACK_DELAY_MAX_MS = 1500L;
	private static final long POWERUP_DELAY_MIN_MS = 250L;
	private static final long POWERUP_DELAY_MAX_MS = 650L;
	private static final long STEP_TIMEOUT_NS = 20_000_000_000L;
	private static final long RECLICK_NS = 8_000_000_000L;
	private static final long ATTACK_GRACE_NS = 2_700_000_000L;
	private static final long DRINK_CONFIRM_NS = 3_600_000_000L;
	private static final long POWERUP_RECLICK_NS = 12_000_000_000L;
	private static final long POWERUP_SETTLE_NS = 1_800_000_000L;
	private static final long NPC_STILL_MIN_MS = 250L;
	private static final long NPC_STILL_MAX_MS = 550L;
	private static final int CAMERA_SETTLED_UNITS = 128;
	private static final long CAMERA_SETTLE_MIN_MS = 350L;
	private static final long CAMERA_SETTLE_MAX_MS = 700L;
	private static final long CAMERA_TURN_GAP_NS = 6_000_000_000L;
	private static final long POPUP_WAIT_NS = 22_000_000_000L;
	private static final int[] MAIN_MODALS = {
		InterfaceID.ToplevelOsrsStretch.MAINMODAL,
		InterfaceID.ToplevelPreEoc.MAINMODAL,
		InterfaceID.Toplevel.MAINMODAL,
		InterfaceID.ToplevelOsm.MAINMODAL
	};

	private static final int PRAYER_MIN = 10;
	private static final int PRAYER_BELOW_MAX = 42;
	private static final int PRAYER_TOP_UP = 35;
	private static final int SEARCH_DISTANCE = 20;
	private static final int[] NMZ_REGION = {9033};
	private static final WorldPoint VIAL_LOCATION = new WorldPoint(2605, 3117, 0);

	private static final String DOMINIC = "Dominic Onion";
	private static final String TANGLEFOOT = "tanglefoot";
	private static final String[] PRIORITY = {"black demon", "ice troll king", "dad"};

	private static final int[] PRAYER_POTIONS = {
		ItemID._1DOSEPRAYERRESTORE,
		ItemID._2DOSEPRAYERRESTORE,
		ItemID._3DOSEPRAYERRESTORE,
		ItemID._4DOSEPRAYERRESTORE
	};

	private static final int[] OVERLOADS = {
		ItemID.NZONE1DOSEOVERLOADPOTION,
		ItemID.NZONE2DOSEOVERLOADPOTION,
		ItemID.NZONE3DOSEOVERLOADPOTION,
		ItemID.NZONE4DOSEOVERLOADPOTION
	};

	private static final int[] COMBAT_POTIONS = {
		ItemID._1DOSE2COMBAT,
		ItemID._2DOSE2COMBAT,
		ItemID._3DOSE2COMBAT,
		ItemID._4DOSE2COMBAT,
		ItemID._1DOSECOMBAT,
		ItemID._2DOSECOMBAT,
		ItemID._3DOSECOMBAT,
		ItemID._4DOSECOMBAT
	};

	private static final int BOOST_MIN = 0;
	private static final int BOOST_MAX = 3;
	private static final int OVERLOAD_MIN_HP = 51;
	private static final long BOOST_CONFIRM_NS = 7_000_000_000L;
	private static final long OVERLOAD_DURATION_NS = 300_000_000_000L;
	private static final int OVERLOAD_HP_JUMP = 20;

	private static final int[] MAIN_WEAPONS = {ItemID.DUAL_MACUAHUITL, ItemID.BR_DUAL_MACUAHUITL};
	private static final int[] SECATEURS = {ItemID.NZONE_FAIRYSECATEURS, ItemID.FAIRY_ENCHANTED_SECATEURS};

	private static final int[] POWERUPS = {
		ObjectID.NZONE_POWERUP_ZAPPER,
		ObjectID.NZONE_POWERUP_DAMAGEMULTIPLIER,
		ObjectID.NZONE_POWERUP_NUKE
	};

	private enum Step
	{
		DREAM,
		KEY_4,
		SPACE,
		KEY_1,
		VIAL,
		ACCEPT,
		ENTER,
		INSIDE
	}

	private Step step;
	private long stepStartedAt;
	private long lastClickAt;
	private String pendingKey;
	private long pendingAt;
	private int prayerThreshold;
	private boolean topUp;
	private int prayerBeforeDrink;
	private long drinkConfirmUntil;
	private long attackGraceUntil;
	private int dosesDrunk;
	private int mainWeapon = -1;
	private NPC lastAttacked;
	private NPC tanglefootTarget;
	private boolean tanglefootDone;
	private java.util.Set<Integer> tanglefootBaseline;
	private boolean tanglefootArmed;
	private long lastStateLogAt;
	private static final long STATE_LOG_NS = 5_000_000_000L;
	private long lastCameraTurnAt;
	private long cameraStillAt;
	private NPC stillNpc;
	private LocalPoint stillAt;
	private long stillUntil;
	private WorldPoint powerupClicked;
	private long powerupClickedAt;
	private boolean popupHandled;
	private boolean firstCheck;
	private int boostThreshold;
	private long boostConfirmUntil;
	private int boostDoses;
	private long overloadUntil;
	private int lastHp = -1;
	private volatile String boostInfo = "-";
	private int powerups;
	private int dreams;
	private long startedAt;
	private volatile String targetInfo = "-";
	private volatile String prayerInfo = "-";
	private volatile String insideInfo = "-";

	public NMZRoutine()
	{
		super("NMZ", "Nightmare Zone: buys and enters a dream, keeps prayer up, grabs power-ups, never fights Tanglefoot.");
	}

	@Override
	protected void onStart()
	{
		step = Step.DREAM;
		stepStartedAt = System.nanoTime();
		lastClickAt = 0;
		pendingKey = null;
		prayerThreshold = rollPrayer();
		topUp = false;
		drinkConfirmUntil = 0;
		attackGraceUntil = 0;
		dosesDrunk = 0;
		firstCheck = true;
		boostThreshold = roll(BOOST_MIN, BOOST_MAX);
		boostConfirmUntil = 0;
		boostDoses = 0;
		overloadUntil = 0;
		lastHp = -1;
		powerups = 0;
		dreams = 0;
		startedAt = System.currentTimeMillis();
	}

	@Override
	protected void onGameTick()
	{
		if (!getContext().isLoggedIn())
		{
			setStatus("Waiting for login");
		}
	}

	@Override
	protected void onClientTick()
	{
		RoutineContext context = getContext();
		if (!context.isLoggedIn())
		{
			return;
		}
		GameInput input = context.getInput();
		if (input.isBusy() || input.isCameraBusy())
		{
			return;
		}

		boolean inside = inDream(context);
		if (inside && step != Step.INSIDE)
		{
			setStep(Step.INSIDE, "dream started");
			++dreams;
			popupHandled = firstCheck;
			prayerThreshold = rollPrayer();
		}
		else if (!inside && step == Step.INSIDE)
		{
			setStep(Step.DREAM, "dream ended");
		}

		firstCheck = false;

		if (step == Step.INSIDE)
		{
			inside(context);
		}
		else if (!cameraMoving(context.getClient()))
		{
			lobby(context);
		}
	}

	private void lobby(RoutineContext context)
	{
		Client client = context.getClient();
		GameInput input = context.getInput();
		long now = System.nanoTime();
		boolean options = visible(client, InterfaceID.Chatmenu.OPTIONS);
		boolean chat = visible(client, InterfaceID.ChatLeft.UNIVERSE) || visible(client, InterfaceID.ChatRight.UNIVERSE)
			|| visible(client, InterfaceID.Messagebox.UNIVERSE);
		Widget accept = client.getWidget(InterfaceID.NzoneLists.YES);
		boolean acceptOpen = accept != null && !accept.isHidden();

		if (acceptOpen && step != Step.ACCEPT && step != Step.ENTER)
		{
			setStep(Step.ACCEPT, "accept interface open");
		}
		if (step != Step.DREAM && now - stepStartedAt > STEP_TIMEOUT_NS)
		{
			setStep(step == Step.ENTER ? Step.VIAL : Step.DREAM, "step timed out");
		}

		switch (step)
		{
			case DREAM:
				if (options)
				{
					setStep(Step.KEY_4, "dream options open");
					break;
				}
				if (dreamBought(context))
				{
					lastClickAt = 0;
					setStep(Step.VIAL, "dream already bought");
					break;
				}
				Optional<NPC> dominic = context.getTargets().findNpc(DOMINIC, SEARCH_DISTANCE);
				if (!dominic.isPresent())
				{
					setStatus("Dominic Onion not found");
					break;
				}
				if (now - lastClickAt < RECLICK_NS)
				{
					setStatus("Walking to Dominic Onion");
					break;
				}
				if (!input.isVisible(dominic.get()))
				{
					offscreen(context, dominic.get().getWorldLocation(), "Dominic Onion");
					break;
				}
				if (ready("dream") && input.click(dominic.get()))
				{
					lastClickAt = now;
					setStatus("Clicked Dominic Onion");
				}
				break;
			case KEY_4:
				if (options && ready("key4") && input.pressKey(KeyEvent.VK_4))
				{
					setStep(Step.SPACE, "pressed 4");
				}
				break;
			case SPACE:
				if (chat && ready("space") && input.pressSpace())
				{
					setStep(Step.KEY_1, "pressed space");
				}
				break;
			case KEY_1:
				if (options && ready("key1") && input.pressKey(KeyEvent.VK_1))
				{
					lastClickAt = 0;
					setStep(Step.VIAL, "pressed 1");
				}
				break;
			case VIAL:
				Optional<TileObject> vial = findVial(context);
				if (!vial.isPresent())
				{
					setStatus("Potion vial not found, objects near 2605,3117: " + context.getTargets().getTileObjects(VIAL_LOCATION, 1).stream()
						.map(object -> object.getId() + "@" + object.getWorldLocation().getX() + "," + object.getWorldLocation().getY())
						.collect(java.util.stream.Collectors.joining(" ")));
					break;
				}
				if (now - lastClickAt < RECLICK_NS)
				{
					setStatus("Walking to vial");
					break;
				}
				if (!input.isVisible(vial.get()))
				{
					setStatus("Turning camera to vial " + vial.get().getId());
					offscreen(context, vial.get().getWorldLocation(), "vial");
					break;
				}
				if (!ready("vial"))
				{
					setStatus("Vial " + vial.get().getId() + " found, clicking soon");
					break;
				}
				if (input.interact(vial.get(), "Drink") || input.click(vial.get()))
				{
					lastClickAt = now;
					setStatus("Drink Potion on vial " + vial.get().getId());
				}
				else
				{
					setStatus("Vial " + vial.get().getId() + " click refused");
				}
				break;
			case ACCEPT:
				if (acceptOpen && ready("accept") && input.click(accept))
				{
					setStep(Step.ENTER, "clicked Accept");
				}
				break;
			case ENTER:
				setStatus("Entering dream");
				break;
			default:
				break;
		}
	}

	private void inside(RoutineContext context)
	{
		long now = System.nanoTime();
		if (now - lastStateLogAt > STATE_LOG_NS)
		{
			lastStateLogAt = now;
			Client client = context.getClient();
			log.info("[NMZ] state: HP {}/{}, quick prayer {}, prayer [{}], boost [{}], target [{}], status [{}]",
				client.getBoostedSkillLevel(Skill.HITPOINTS), client.getRealSkillLevel(Skill.HITPOINTS),
				client.getVarbitValue(VarbitID.QUICKPRAYER_ACTIVE) == 1, prayerInfo, boostInfo, targetInfo, getStatus());
		}
		if (prayer(context))
		{
			return;
		}
		if (cameraMoving(context.getClient()))
		{
			setStatus("Waiting for the camera to settle");
			return;
		}
		if (levelUp(context) || popup(context) || quickPrayer(context) || powerup(context) || boost(context))
		{
			return;
		}
		fight(context);
	}

	/** One dose at a random prayer between 10 and 42 below max. */
	private int rollPrayer()
	{
		int max = getContext() == null ? 0 : getContext().getClient().getRealSkillLevel(Skill.PRAYER);
		return roll(PRAYER_MIN, Math.max(PRAYER_MIN, max - PRAYER_BELOW_MAX));
	}

	/** Prayer potions come first. @return true while a drink is being handled. */
	private boolean prayer(RoutineContext context)
	{
		Client client = context.getClient();
		int current = client.getBoostedSkillLevel(Skill.PRAYER);
		int max = client.getRealSkillLevel(Skill.PRAYER);
		int restore = 7 + max / 4;
		long now = System.nanoTime();

		if (drinkConfirmUntil != 0)
		{
			if (current > prayerBeforeDrink || now > drinkConfirmUntil)
			{
				drinkConfirmUntil = 0;
				topUp = false;
				prayerThreshold = rollPrayer();
				log.info("[NMZ] prayer {} after drink, top up: {}, next at <= {}", current, topUp, prayerThreshold);
			}
			else
			{
				prayerInfo = current + "/" + max + ", waiting for dose to apply";
				return false;
			}
		}

		boolean low = current <= prayerThreshold || (topUp && current <= PRAYER_TOP_UP);
		prayerInfo = current + "/" + max + ", drink at <= " + prayerThreshold + (topUp ? ", topping up to > " + PRAYER_TOP_UP : "")
			+ ", dose +" + restore;
		if (!low)
		{
			cancel("prayer");
			return false;
		}
		if (current + restore > max)
		{
			prayerInfo += " (dose would waste)";
			topUp = false;
			cancel("prayer");
			return false;
		}

		Inventory inventory = context.getInventory();
		int slot = findSlot(inventory, PRAYER_POTIONS);
		if (slot < 0)
		{
			prayerInfo += " (no prayer potions)";
			return false;
		}
		if (!inventory.isOpen())
		{
			if (ready("inventory"))
			{
				inventory.open();
			}
			return true;
		}
		if (!ready("prayer"))
		{
			return true;
		}
		Widget widget = inventory.getSlotWidget(slot);
		if (widget != null && context.getInput().click(widget))
		{
			prayerBeforeDrink = current;
			drinkConfirmUntil = now + DRINK_CONFIRM_NS;
			++dosesDrunk;
			log.info("[NMZ] drinking prayer potion at {}/{}", current, max);
			setStatus("Drinking prayer potion");
		}
		return true;
	}

	/** About 10 s into a dream a window opens; Esc closes it before quick prayers go on. */
	private boolean popup(RoutineContext context)
	{
		boolean open = modalOpen(context.getClient());
		if (!open && (popupHandled || System.nanoTime() - stepStartedAt > POPUP_WAIT_NS))
		{
			popupHandled = true;
			cancel("escape");
			return false;
		}
		if (!open)
		{
			setStatus("Waiting for the dream popup");
			return true;
		}
		if (ready("escape") && context.getInput().pressKey(KeyEvent.VK_ESCAPE))
		{
			popupHandled = true;
			log.info("[NMZ] closed popup with Esc");
			setStatus("Closing popup");
		}
		return true;
	}

	/** Same check as RuneLite's Nightmare Zone plugin: region 9033 on planes 1-3, or the dream overlay showing. */
	private boolean inDream(RoutineContext context)
	{
		Client client = context.getClient();
		WorldPoint location = context.getPlayerLocation();
		boolean region = location != null && location.getPlane() > 0 && Arrays.equals(client.getMapRegions(), NMZ_REGION);
		boolean overlay = visible(client, InterfaceID.NzoneGame.UNIVERSE);
		insideInfo = "region " + region + ", overlay " + overlay + ", plane " + (location == null ? "-" : location.getPlane());
		return region || overlay;
	}

	/** One camera turn at most every 6 s; between turns it walks toward the target on the minimap. */
	private void offscreen(RoutineContext context, WorldPoint where, String label)
	{
		offscreen(context, where, label, true);
	}

	private void offscreen(RoutineContext context, WorldPoint where, String label, boolean walk)
	{
		long now = System.nanoTime();
		if (now - lastCameraTurnAt > CAMERA_TURN_GAP_NS)
		{
			lastCameraTurnAt = now;
			setStatus("Turning camera to " + label);
			context.getInput().turnCameraToward(where);
			return;
		}
		if (!walk)
		{
			setStatus("Waiting for " + label + " to come on screen");
			return;
		}
		setStatus(label + " off-screen, walking toward it");
		LocalPoint local = where == null ? null : LocalPoint.fromWorld(context.getClient(), where);
		net.runelite.api.Point minimap = local == null ? null : Perspective.localToMinimap(context.getClient(), local);
		if (minimap != null && ready("minimap"))
		{
			context.getInput().click(new java.awt.Point(minimap.getX(), minimap.getY()));
		}
	}

	/** True while the camera is still easing toward its target yaw or just finished a turn. */
	private boolean cameraMoving(Client client)
	{
		int diff = Math.abs(client.getCameraYaw() - client.getCameraYawTarget()) & 16383;
		if (Math.min(diff, 16384 - diff) > CAMERA_SETTLED_UNITS)
		{
			cameraStillAt = 0;
			return true;
		}
		long now = System.nanoTime();
		if (cameraStillAt == 0)
		{
			cameraStillAt = now + rollNs(CAMERA_SETTLE_MIN_MS, CAMERA_SETTLE_MAX_MS);
		}
		return now < cameraStillAt || now - lastCameraTurnAt < CAMERA_SETTLE_MIN_MS * 1_000_000L;
	}

	private static long rollNs(long minMs, long maxMs)
	{
		return (minMs + (long) (Math.random() * (maxMs - minMs))) * 1_000_000L + (long) (Math.random() * 1_000_000L);
	}

	/** True once the NPC has stayed on the same spot, not walking, for a short random moment. */
	private boolean standingStill(NPC npc)
	{
		LocalPoint at = npc.getLocalLocation();
		int pose = npc.getPoseAnimation();
		boolean walking = pose != -1 && (pose == npc.getWalkAnimation() || pose == npc.getRunAnimation())
			&& pose != npc.getIdlePoseAnimation();
		long now = System.nanoTime();
		if (npc != stillNpc || at == null || !at.equals(stillAt) || walking)
		{
			stillNpc = npc;
			stillAt = at;
			stillUntil = now + rollNs(NPC_STILL_MIN_MS, NPC_STILL_MAX_MS);
			return false;
		}
		return now >= stillUntil;
	}

	private static boolean modalOpen(Client client)
	{
		for (int id : MAIN_MODALS)
		{
			Widget modal = client.getWidget(id);
			if (modal == null || modal.isHidden())
			{
				continue;
			}
			Widget[] nested = modal.getNestedChildren();
			if (nested != null && Arrays.stream(nested).anyMatch(child -> child != null && !child.isHidden()))
			{
				return true;
			}
		}
		return false;
	}

	private boolean levelUp(RoutineContext context)
	{
		if (!visible(context.getClient(), InterfaceID.LevelupDisplay.UNIVERSE))
		{
			cancel("levelup");
			return false;
		}
		if (ready("levelup") && context.getInput().pressSpace())
		{
			setStatus("Closing level-up message");
		}
		return true;
	}

	/**
	 * Overloads last 5 minutes and cost 50 HP; when one ends the HP comes back, so it re-drinks
	 * as soon as the effect is gone and HP is above 50. Without overloads it re-drinks a
	 * (super) combat potion once the Strength boost wears down.
	 */
	private boolean boost(RoutineContext context)
	{
		Client client = context.getClient();
		Inventory inventory = context.getInventory();
		int hp = client.getBoostedSkillLevel(Skill.HITPOINTS);
		int boost = client.getBoostedSkillLevel(Skill.STRENGTH) - client.getRealSkillLevel(Skill.STRENGTH);
		long now = System.nanoTime();
		boolean hpJumped = lastHp >= 0 && hp - lastHp >= OVERLOAD_HP_JUMP;
		lastHp = hp;
		boolean overloadEffect = client.getVarbitValue(VarbitID.NZONE_OVERLOAD_POTION_EFFECTS) != 0;
		if (hpJumped && !overloadEffect)
		{
			overloadUntil = 0;
		}

		int slot = findSlot(inventory, OVERLOADS);
		boolean want;
		if (slot >= 0)
		{
			boolean active = overloadEffect || now < overloadUntil;
			want = !active && hp >= OVERLOAD_MIN_HP;
			boostInfo = "overload " + (active ? "active" + (overloadUntil > now ? ", " + (overloadUntil - now) / 1_000_000_000L + " s left" : "")
				: hp >= OVERLOAD_MIN_HP ? "due" : "waiting for HP > 50") + ", HP " + hp;
		}
		else
		{
			if (boostConfirmUntil != 0 && (boost > boostThreshold || now > boostConfirmUntil))
			{
				boostConfirmUntil = 0;
				boostThreshold = roll(BOOST_MIN, BOOST_MAX);
			}
			slot = findSlot(inventory, COMBAT_POTIONS);
			want = slot >= 0 && boostConfirmUntil == 0 && boost <= boostThreshold;
			boostInfo = slot < 0 ? "no overloads or combat potions" : "+" + boost + ", drink at <= +" + boostThreshold;
		}
		if (!want)
		{
			cancel("boost");
			return false;
		}
		if (!inventory.isOpen())
		{
			if (ready("inventory"))
			{
				inventory.open();
			}
			return true;
		}
		if (!ready("boost"))
		{
			return true;
		}
		Widget widget = inventory.getSlotWidget(slot);
		boolean overload = findSlot(inventory, OVERLOADS) == slot;
		if (widget != null && context.getInput().click(widget))
		{
			if (overload)
			{
				overloadUntil = now + OVERLOAD_DURATION_NS;
			}
			else
			{
				boostConfirmUntil = now + BOOST_CONFIRM_NS;
			}
			++boostDoses;
			attackGraceUntil = now + ATTACK_GRACE_NS;
			log.info("[NMZ] drinking {} at HP {}, boost +{}", overload ? "overload" : "combat potion", hp, boost);
			setStatus(overload ? "Drinking overload" : "Drinking combat potion");
		}
		return true;
	}

	private boolean powerup(RoutineContext context)
	{
		for (int id : POWERUPS)
		{
			Optional<TileObject> object = context.getTargets().findTileObject(id, SEARCH_DISTANCE);
			if (!object.isPresent())
			{
				continue;
			}
			GameInput input = context.getInput();
			WorldPoint where = object.get().getWorldLocation();
			Player player = context.getPlayer();
			if (context.getClient().isMenuOpen())
			{
				// Our right-click menu is up: pick Activate now.
				if (input.interactViaMenu(object.get(), "Activate"))
				{
					log.info("[NMZ] activate from menu {}", id);
				}
				setStatus("Activating power-up");
				return true;
			}
			long sinceClick = System.nanoTime() - powerupClickedAt;
			boolean sameClicked = where != null && where.equals(powerupClicked);
			boolean moving = player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
			if (sameClicked && sinceClick < POWERUP_RECLICK_NS && (moving || sinceClick < POWERUP_SETTLE_NS))
			{
				setStatus("Walking to power-up");
				return true;
			}
			if (!input.isVisible(object.get()))
			{
				offscreen(context, where, "power-up");
				return true;
			}
			// Monsters crowd the power-up, so never left-click it; always right-click and pick Activate.
			if (ready("powerup", POWERUP_DELAY_MIN_MS, POWERUP_DELAY_MAX_MS)
				&& input.interactViaMenu(object.get(), "Activate"))
			{
				powerupClicked = where;
				powerupClickedAt = System.nanoTime();
				++powerups;
				attackGraceUntil = System.nanoTime() + ATTACK_GRACE_NS;
				log.info("[NMZ] clicking power-up {}", id);
				setStatus("Grabbing power-up");
			}
			return true;
		}
		cancel("powerup");
		return false;
	}

	private boolean quickPrayer(RoutineContext context)
	{
		Client client = context.getClient();
		if (client.getVarbitValue(VarbitID.QUICKPRAYER_ACTIVE) == 1 || client.getBoostedSkillLevel(Skill.PRAYER) == 0)
		{
			cancel("quickprayer");
			return false;
		}
		Widget orb = client.getWidget(InterfaceID.Orbs.PRAYERBUTTON);
		if (orb == null || orb.isHidden())
		{
			return false;
		}
		if (ready("quickprayer") && context.getInput().click(orb))
		{
			log.info("[NMZ] quick prayers on");
			setStatus("Quick prayers on");
		}
		return true;
	}

	private void fight(RoutineContext context)
	{
		Player player = context.getPlayer();
		WorldView worldView = context.getClient().getTopLevelWorldView();
		WorldPoint location = context.getPlayerLocation();
		if (player == null || worldView == null || location == null)
		{
			return;
		}

		NPC current = player.getInteracting() instanceof NPC ? (NPC) player.getInteracting() : null;
		boolean currentOk = current != null && attackable(current);
		Optional<NPC> best = worldView.npcs().stream()
			.map(npc -> (NPC) npc)
			.filter(npc -> npc != null && attackable(npc) && npc.getWorldLocation() != null
				&& npc.getWorldLocation().distanceTo(location) <= SEARCH_DISTANCE)
			.min(Comparator.comparingInt(NMZRoutine::rank)
				.thenComparingInt(npc -> npc.getWorldLocation().distanceTo(location)));

		if (tanglefootOnly(context, best.isPresent(), current, location))
		{
			return;
		}
		if (rewield(context))
		{
			return;
		}
		if (!best.isPresent())
		{
			targetInfo = current == null ? "none (waiting for NPCs)" : name(current) + " (not attackable)";
			setStatus("Waiting for NPCs");
			cancel("attack");
			return;
		}
		NPC target = best.get();
		if (currentOk && rank(current) <= rank(target))
		{
			targetInfo = name(current) + " (rank " + rank(current) + ")";
			setStatus("Fighting " + name(current));
			cancel("attack");
			return;
		}
		if (current == null && System.nanoTime() < attackGraceUntil)
		{
			return;
		}

		targetInfo = name(target) + " (rank " + rank(target) + ")"
			+ (current != null ? ", switching from " + name(current) : "");
		GameInput input = context.getInput();
		if (!input.isVisible(target))
		{
			offscreen(context, target.getWorldLocation(), name(target), false);
			return;
		}
		// A left click can land on another NPC standing in front; if the last click on this
		// target didn't take, pick Attack on it from the menu instead.
		if (!standingStill(target))
		{
			setStatus("Waiting for " + name(target) + " to stop moving");
			return;
		}
		if (ready("attack", ATTACK_DELAY_MIN_MS, ATTACK_DELAY_MAX_MS) && input.click(target))
		{
			lastAttacked = target;
			attackGraceUntil = System.nanoTime() + ATTACK_GRACE_NS;
			log.info("[NMZ] attacking {}{}{}", name(target), current != null ? " instead of " + name(current) : "", "");
			setStatus("Attacking " + name(target));
		}
	}

	/** True when the lobby vial shows full, i.e. a dream is already paid for. */
	private static boolean dreamBought(RoutineContext context)
	{
		if (context.getTargets().findTileObject(ObjectID.NZONE_LOBBY_VIAL_FULL, SEARCH_DISTANCE).isPresent())
		{
			return true;
		}
		Client client = context.getClient();
		return context.getTargets().getTileObjects(VIAL_LOCATION, 1).stream().anyMatch(object ->
		{
			net.runelite.api.ObjectComposition definition = client.getObjectDefinition(object.getId());
			if (definition == null || definition.getImpostorIds() == null)
			{
				return false;
			}
			net.runelite.api.ObjectComposition impostor = definition.getImpostor();
			return impostor != null && impostor.getId() == ObjectID.NZONE_LOBBY_VIAL_FULL;
		});
	}

	/** The vial is a multiloc: the scene holds the base id, which shows as the full vial once a dream is bought. */
	private static Optional<TileObject> findVial(RoutineContext context)
	{
		Optional<TileObject> vial = context.getTargets().findObject("Potion", "Drink", SEARCH_DISTANCE).map(object -> (TileObject) object);
		if (!vial.isPresent())
		{
			vial = context.getTargets().findTileObject(ObjectID.NZONE_LOBBY_VIAL_FULL, SEARCH_DISTANCE);
		}
		if (!vial.isPresent())
		{
			vial = context.getTargets().findTileObject(ObjectID.NZONE_LOBBY_VIAL, SEARCH_DISTANCE);
		}
		if (!vial.isPresent())
		{
			vial = context.getTargets().getTileObjects(VIAL_LOCATION, 1).stream()
				.filter(object -> object.getId() == ObjectID.NZONE_LOBBY_VIAL_EMPTY || object.getId() == ObjectID.NZONE_LOBBY_VIAL_FULL
					|| object.getId() == ObjectID.NZONE_LOBBY_VIAL)
				.findFirst();
		}
		if (!vial.isPresent())
		{
			vial = context.getTargets().getTileObjects(VIAL_LOCATION, 0).stream().findFirst();
		}
		return vial;
	}

	/**
	 * When only Tanglefoots are left: take Magic secateurs if none are held, wield them and
	 * kill a Tanglefoot. @return true while handling that.
	 */
	private boolean tanglefootOnly(RoutineContext context, boolean othersLeft, NPC current, WorldPoint location)
	{
		if (tanglefootTarget != null && (tanglefootTarget.isDead()
			|| context.getClient().getTopLevelWorldView().npcs().stream().noneMatch(npc -> npc == tanglefootTarget)))
		{
			log.info("[NMZ] Tanglefoot killed, switching back");
			tanglefootTarget = null;
			tanglefootDone = true;
		}
		if (othersLeft)
		{
			tanglefootDone = false;
			tanglefootBaseline = null;
			tanglefootArmed = false;
			return false;
		}
		if (tanglefootDone)
		{
			return false;
		}
		if (!tanglefootArmed)
		{
			// Only switch once the next spawn turns out to be a Tanglefoot too.
			java.util.Set<Integer> now = new java.util.HashSet<>();
			context.getClient().getTopLevelWorldView().npcs().stream()
				.filter(npc -> npc != null && !npc.isDead() && name(npc).contains(TANGLEFOOT))
				.forEach(npc -> now.add(npc.getIndex()));
			if (tanglefootBaseline == null)
			{
				tanglefootBaseline = now;
				log.info("[NMZ] only Tanglefoots left ({}), waiting for next spawn", now.size());
			}
			else if (!tanglefootBaseline.containsAll(now))
			{
				tanglefootArmed = true;
				log.info("[NMZ] new Tanglefoot spawned, switching to secateurs");
			}
			if (!tanglefootArmed)
			{
				targetInfo = "none (waiting for next spawn)";
				setStatus("Only Tanglefoots left, waiting for next spawn");
				return true;
			}
		}
		Optional<NPC> tanglefoot = context.getClient().getTopLevelWorldView().npcs().stream()
			.map(npc -> (NPC) npc)
			.filter(npc -> npc != null && !npc.isDead() && name(npc).contains(TANGLEFOOT) && npc.getWorldLocation() != null
				&& npc.getWorldLocation().distanceTo(location) <= SEARCH_DISTANCE)
			.min(Comparator.comparingInt(npc -> npc.getWorldLocation().distanceTo(location)));
		if (!tanglefoot.isPresent())
		{
			return false;
		}

		GameInput input = context.getInput();
		Inventory inventory = context.getInventory();
		int weapon = wornWeapon(context.getClient());
		if (!isSecateurs(weapon))
		{
			int slot = findSlot(inventory, SECATEURS);
			if (slot >= 0)
			{
				if (weapon > 0)
				{
					mainWeapon = weapon;
				}
				if (!inventory.isOpen())
				{
					if (ready("inventory"))
					{
						inventory.open();
					}
					return true;
				}
				Widget widget = inventory.getSlotWidget(slot);
				if (ready("wield secateurs") && widget != null && input.click(widget))
				{
					log.info("[NMZ] wielding Magic secateurs (main weapon {})", mainWeapon);
					setStatus("Wielding Magic secateurs");
				}
				return true;
			}
			Optional<TileObject> pickup = context.getTargets().findTileObject(ObjectID.NZONE_FAIRYSECATEURS, 40);
			if (!pickup.isPresent())
			{
				setStatus("Only Tanglefoots left, Magic secateurs not found");
				return false;
			}
			if (!input.isVisible(pickup.get()))
			{
				offscreen(context, pickup.get().getWorldLocation(), "Magic secateurs");
				return true;
			}
			if (System.nanoTime() - lastClickAt < POWERUP_RECLICK_NS)
			{
				setStatus("Walking to Magic secateurs");
				return true;
			}
			if (ready("take secateurs") && (input.interact(pickup.get(), "Pick-up", "Take") || input.click(pickup.get())))
			{
				lastClickAt = System.nanoTime();
				setStatus("Pick-up Magic secateurs");
			}
			return true;
		}

		lastClickAt = 0;
		NPC target = tanglefoot.get();
		if (current != null && name(current).contains(TANGLEFOOT))
		{
			tanglefootTarget = current;
			targetInfo = "tanglefoot (with secateurs)";
			setStatus("Fighting Tanglefoot with secateurs");
			cancel("attack");
			return true;
		}
		targetInfo = "tanglefoot (only Tanglefoots left)";
		if (!input.isVisible(target))
		{
			offscreen(context, target.getWorldLocation(), "Tanglefoot", false);
			return true;
		}
		if (!standingStill(target))
		{
			setStatus("Waiting for Tanglefoot to stop moving");
			return true;
		}
		if (System.nanoTime() >= attackGraceUntil && ready("attack", ATTACK_DELAY_MIN_MS, ATTACK_DELAY_MAX_MS) && input.click(target))
		{
			attackGraceUntil = System.nanoTime() + ATTACK_GRACE_NS;
			setStatus("Attacking Tanglefoot with secateurs");
		}
		return true;
	}

	/** Wields the Dual macuahuitl (or the weapon swapped out for secateurs) whenever it isn't on. */
	private boolean rewield(RoutineContext context)
	{
		int worn = wornWeapon(context.getClient());
		if (Arrays.stream(MAIN_WEAPONS).anyMatch(id -> id == worn))
		{
			return false;
		}
		Inventory inventory = context.getInventory();
		int slot = findSlot(inventory, MAIN_WEAPONS);
		if (slot < 0 && mainWeapon > 0 && isSecateurs(worn))
		{
			slot = inventory.findSlot(mainWeapon);
		}
		if (slot < 0)
		{
			return false;
		}
		if (!inventory.isOpen())
		{
			if (ready("inventory"))
			{
				inventory.open();
			}
			return true;
		}
		Widget widget = inventory.getSlotWidget(slot);
		if (ready("rewield") && widget != null && context.getInput().click(widget))
		{
			log.info("[NMZ] re-wielding main weapon {}", mainWeapon);
			setStatus("Re-wielding main weapon");
		}
		return true;
	}

	private static int wornWeapon(Client client)
	{
		ItemContainer worn = client.getItemContainer(InventoryID.WORN);
		Item item = worn == null ? null : worn.getItem(EquipmentInventorySlot.WEAPON.getSlotIdx());
		return item == null ? -1 : item.getId();
	}

	private static boolean isSecateurs(int itemId)
	{
		return Arrays.stream(SECATEURS).anyMatch(id -> id == itemId);
	}

	private static boolean attackable(NPC npc)
	{
		String name = name(npc);
		if (npc.isDead() || name.isEmpty() || name.contains(TANGLEFOOT))
		{
			return false;
		}
		NPCComposition composition = npc.getTransformedComposition();
		return composition != null && composition.getActions() != null
			&& Arrays.stream(composition.getActions()).anyMatch("Attack"::equalsIgnoreCase);
	}

	private static int rank(NPC npc)
	{
		String name = name(npc);
		for (int i = 0; i < PRIORITY.length; i++)
		{
			if (name.equals(PRIORITY[i]))
			{
				return i;
			}
		}
		return PRIORITY.length;
	}

	private static String name(Actor actor)
	{
		String name = actor.getName();
		return name == null ? "" : name.replace('\u00a0', ' ').toLowerCase(Locale.ROOT);
	}

	private static int findSlot(Inventory inventory, int[] ids)
	{
		for (int id : ids)
		{
			int slot = inventory.findSlot(id);
			if (slot >= 0)
			{
				return slot;
			}
		}
		return -1;
	}

	private static boolean visible(Client client, int componentId)
	{
		Widget widget = client.getWidget(componentId);
		return widget != null && !widget.isHidden();
	}

	private void setStep(Step next, String reason)
	{
		log.info("[NMZ] {} -> {} ({})", step, next, reason);
		step = next;
		stepStartedAt = System.nanoTime();
		pendingKey = null;
	}

	private boolean ready(String key)
	{
		return ready(key, DELAY_MIN_MS, DELAY_MAX_MS);
	}

	/** The first call for an action starts its random delay; true once it has passed. */
	private boolean ready(String key, long minMs, long maxMs)
	{
		long now = System.nanoTime();
		if (!key.equals(pendingKey))
		{
			pendingKey = key;
			pendingAt = now + randomDelayNanos(minMs + EXTRA_DELAY_MS, maxMs + EXTRA_DELAY_MS);
			return false;
		}
		if (now < pendingAt)
		{
			return false;
		}
		pendingKey = null;
		return true;
	}

	private void cancel(String key)
	{
		if (key.equals(pendingKey))
		{
			pendingKey = null;
		}
	}

	private static int roll(int min, int max)
	{
		return ThreadLocalRandom.current().nextInt(min, max + 1);
	}

	/** Random delay inside (minMs, maxMs) with sub-millisecond precision, never a round value. */
	private static long randomDelayNanos(long minMs, long maxMs)
	{
		ThreadLocalRandom random = ThreadLocalRandom.current();
		long nanos;
		do
		{
			nanos = random.nextLong(minMs * 1_000_000L + 1, maxMs * 1_000_000L);
		}
		while (nanos % 100_000_000L == 0 || (nanos / 1_000_000L) % 50 == 0);
		return nanos;
	}

	@Override
	public List<String> getOverlayLines()
	{
		long now = System.nanoTime();
		long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
		List<String> lines = new ArrayList<>();
		lines.add("Step: " + step + " | " + getStatus());
		String key = pendingKey;
		lines.add("Pending: " + (key == null ? "-" : key + " in " + Math.max(0, (pendingAt - now) / 1_000_000L) + " ms"));
		lines.add("Inside: " + insideInfo);
		lines.add("Prayer: " + prayerInfo);
		lines.add("Target: " + targetInfo);
		lines.add("Boost: " + boostInfo);
		lines.add("Dreams: " + dreams + ", prayer doses: " + dosesDrunk + ", boost doses: " + boostDoses + ", power-ups: " + powerups
			+ ", " + elapsed / 60_000L + " min");
		return lines;
	}
}
