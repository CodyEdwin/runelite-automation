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
package net.runelite.rsbuddy.scripts;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;

/**
 * Fletches unstrung bows at a bank: deposit inventory (the knife stays in
 * the locked first slot), left-click the logs (left click set to
 * Withdraw-All), Escape, knife on logs, choose the bow in the make menu, wait
 * for the inventory to be fletched, repeat. Re-starts after a level-up.
 */
public final class BowFletcherScript extends Script
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BowFletcherScript.class);

	private static final int KNIFE_ID = ItemID.KNIFE; // 946
	private static final int BANK_SEARCH_DISTANCE = 15;

	private static final int HOVER_ATTEMPTS = 3;
	private static final int BANK_OPEN_WAIT_TICKS = 8;
	private static final int BANK_OPEN_WALK_TICKS = 20;
	private static final int BANK_OPEN_ATTEMPTS = 4;
	private static final int BANK_LOAD_TICKS = 3;
	private static final int ACTION_WAIT_TICKS = 5;
	private static final int ACTION_ATTEMPTS = 3;
	private static final int MAKE_MENU_WAIT_TICKS = 5;
	/** No animation and no log used for this long with logs left: restart (level-up etc.). */
	private static final int IDLE_RESTART_TICKS = 4;
	private static final int RESTART_LIMIT = 5;
	private static final int BUSY_STUCK_TICKS = 15;
	private static final int STALL_LOG_TICKS = 5;

	/** Bow choices. Names match the plugin's BowFletcherBowType (survives hot reload). */
	enum Bow
	{
		REGULAR_SHORTBOW(ItemID.LOGS, ItemID.UNSTRUNG_SHORTBOW),
		REGULAR_LONGBOW(ItemID.LOGS, ItemID.UNSTRUNG_LONGBOW),
		OAK_SHORTBOW(ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_SHORTBOW),
		OAK_LONGBOW(ItemID.OAK_LOGS, ItemID.UNSTRUNG_OAK_LONGBOW),
		WILLOW_SHORTBOW(ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_SHORTBOW),
		WILLOW_LONGBOW(ItemID.WILLOW_LOGS, ItemID.UNSTRUNG_WILLOW_LONGBOW),
		MAPLE_SHORTBOW(ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_SHORTBOW),
		MAPLE_LONGBOW(ItemID.MAPLE_LOGS, ItemID.UNSTRUNG_MAPLE_LONGBOW),
		YEW_SHORTBOW(ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_SHORTBOW),
		YEW_LONGBOW(ItemID.YEW_LOGS, ItemID.UNSTRUNG_YEW_LONGBOW),
		MAGIC_SHORTBOW(ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_SHORTBOW),
		MAGIC_LONGBOW(ItemID.MAGIC_LOGS, ItemID.UNSTRUNG_MAGIC_LONGBOW);

		private final int logId;
		private final int productId;

		Bow(int logId, int productId)
		{
			this.logId = logId;
			this.productId = productId;
		}
	}

	private enum State
	{
		OPEN_BANK,
		WAIT_BANK,
		DEPOSIT,
		WITHDRAW,
		CLOSE_BANK,
		USE_KNIFE,
		USE_ON_LOGS,
		WAIT_MAKE_MENU,
		CHOOSE_BOW,
		FLETCHING,
		HALTED
	}

	private enum HoverResult
	{
		PENDING,
		CLICKED,
		GAVE_UP
	}

	/** Skill make-menu option slots, in order (key 1 = A, 2 = B, ...). */
	private static final int[] MAKE_OPTIONS = {
		InterfaceID.Skillmulti.A, InterfaceID.Skillmulti.B, InterfaceID.Skillmulti.C, InterfaceID.Skillmulti.D,
		InterfaceID.Skillmulti.E, InterfaceID.Skillmulti.F, InterfaceID.Skillmulti.G, InterfaceID.Skillmulti.H,
		InterfaceID.Skillmulti.I
	};

	private final Bow bow;
	private final int amount;
	private final boolean closestBooth;

	private State state;
	private int stateTicks;
	private int waitTicks;
	private int attempts;
	private int busyTicks;
	private int exceptionCount;

	private String hoverKey;
	private boolean hovered;
	private int hoverAttempts;

	private int bowsMade;
	private int lastProductCount = -1;
	private int lastLogCount = -1;
	private int idleTicks;
	private int restarts;
	private int batches;
	/** Make-menu slot confirmed to make our bow last time; Space repeats it. */
	private int confirmedOption = -1;
	private int pressedOption = -1;
	private boolean startedFletching;

	public BowFletcherScript()
	{
		this(Bow.REGULAR_LONGBOW.name(), 0, true);
	}

	/**
	 * @param bow a {@link Bow} name (REGULAR_LONGBOW, OAK_SHORTBOW, ...)
	 * @param amount bows to make, 0 = unlimited
	 * @param closestBooth open the closest bank booth/chest instead of the closest banker, chest or booth
	 */
	public BowFletcherScript(String bow, int amount, boolean closestBooth)
	{
		super("Bow Fletcher", "Fletches unstrung bows from banked logs with the knife in the locked first slot.");
		this.bow = parseBow(bow);
		this.amount = Math.max(0, amount);
		this.closestBooth = closestBooth;
	}

	private static Bow parseBow(String name)
	{
		try
		{
			return name == null ? Bow.REGULAR_LONGBOW : Bow.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return Bow.REGULAR_LONGBOW;
		}
	}

	@Override
	public boolean useFastMouse()
	{
		return true;
	}

	@Override
	protected void onStart()
	{
		// Swing thread: no client reads here.
		state = null;
		setState(State.OPEN_BANK, "started: " + bow + ", amount=" + (amount == 0 ? "unlimited" : amount)
			+ ", closest booth=" + closestBooth);
		waitTicks = 0;
		attempts = 0;
		busyTicks = 0;
		exceptionCount = 0;
		hoverKey = null;
		bowsMade = 0;
		lastProductCount = -1;
		lastLogCount = -1;
		idleTicks = 0;
		restarts = 0;
		batches = 0;
		confirmedOption = -1;
		pressedOption = -1;
		startedFletching = false;
		setStatus("Ready");
	}

	@Override
	protected void onGameTick()
	{
		try
		{
			tickSafely();
			exceptionCount = 0;
		}
		catch (RuntimeException | AssertionError ex)
		{
			++exceptionCount;
			log.error("[BowFletcher] exception #{} in state={} status={}", exceptionCount, state, getStatus(), ex);
			getContext().getInput().reset();
			hoverKey = null;
			if (exceptionCount >= 20)
			{
				throw ex;
			}
		}
	}

	private void tickSafely()
	{
		ScriptContext context = getContext();
		if (state == State.HALTED)
		{
			return;
		}
		if (!context.isLoggedIn())
		{
			setStatus("Waiting for login");
			return;
		}

		++stateTicks;
		if (state != State.FLETCHING
			&& (stateTicks == STALL_LOG_TICKS + 1 || stateTicks > STALL_LOG_TICKS && stateTicks % 20 == 0))
		{
			log.info("[BowFletcher] stall? {} ticks in state={} status={}", stateTicks, state, getStatus());
		}

		GameInput input = context.getInput();
		busyTicks = input.isBusy() ? busyTicks + 1 : 0;
		if (busyTicks > BUSY_STUCK_TICKS)
		{
			log.info("[BowFletcher] input busy for {} ticks in state={}, resetting input", busyTicks, state);
			input.reset();
			busyTicks = 0;
		}

		countBows(context.getInventory());
		if (amount > 0 && bowsMade >= amount)
		{
			halt("made " + bowsMade + " " + bowName(context) + ", amount reached");
			return;
		}
		if (input.isBusy())
		{
			return;
		}

		switch (state)
		{
			case OPEN_BANK:
				openBank(context);
				break;
			case WAIT_BANK:
				waitBank(context);
				break;
			case DEPOSIT:
				deposit(context);
				break;
			case WITHDRAW:
				withdraw(context);
				break;
			case CLOSE_BANK:
				closeBank(context);
				break;
			case USE_KNIFE:
				useKnife(context);
				break;
			case USE_ON_LOGS:
				useOnLogs(context);
				break;
			case WAIT_MAKE_MENU:
				waitMakeMenu(context);
				break;
			case CHOOSE_BOW:
				chooseBow(context);
				break;
			case FLETCHING:
				fletching(context);
				break;
			default:
				break;
		}
	}

	private void setState(State next, String reason)
	{
		if (next != state)
		{
			log.info("[BowFletcher] {} -> {}: {}", state, next, reason);
			state = next;
			stateTicks = 0;
			waitTicks = 0;
			hoverKey = null;
		}
	}

	private void halt(String reason)
	{
		log.info("[BowFletcher] halting: {}", reason);
		setState(State.HALTED, reason);
		setStatus("Stopped: " + reason);
	}

	// ---------------------------------------------------------------- states

	private void openBank(ScriptContext context)
	{
		if (isBankOpen(context))
		{
			attempts = 0;
			setState(State.DEPOSIT, "bank is open");
			return;
		}
		if (!hasKnife(context))
		{
			return;
		}
		if (attempts >= BANK_OPEN_ATTEMPTS)
		{
			halt("could not open the bank after " + attempts + " tries");
			return;
		}

		GameInput input = context.getInput();
		Object target = findBankTarget(context);
		if (target == null)
		{
			halt("no bank booth, chest or banker within " + BANK_SEARCH_DISTANCE + " tiles");
			return;
		}

		if (target instanceof TileObject)
		{
			TileObject object = (TileObject) target;
			if (!input.isVisible(object))
			{
				input.turnCameraToward(object.getWorldLocation());
				setStatus("Turning camera to bank");
				return;
			}
			String action = bankAction(context, object);
			HoverResult result = hoverClick(context, "bank@" + object.getWorldLocation(), () -> input.hover(object),
				entry -> optionIs(entry, action), action);
			if (result == HoverResult.GAVE_UP)
			{
				// Left click kept showing something else: run the exact
				// object action instead.
				log.info("[BowFletcher bank] top option never '{}', using the exact {} action", action, action);
				input.interact(object, action);
				result = HoverResult.CLICKED;
			}
			if (result == HoverResult.CLICKED)
			{
				++attempts;
				setState(State.WAIT_BANK, "clicked " + action + " on " + objectName(context, object) + " at "
					+ object.getWorldLocation());
			}
			setStatus("Opening bank");
			return;
		}

		NPC banker = (NPC) target;
		if (!input.isVisible(banker))
		{
			input.turnCameraToward(banker.getWorldLocation());
			return;
		}
		HoverResult result = hoverClick(context, "banker@" + banker.getIndex(), () -> input.hover(banker),
			entry -> optionIs(entry, "Bank"), "Bank");
		if (result == HoverResult.GAVE_UP)
		{
			input.interact(banker, "Bank");
			result = HoverResult.CLICKED;
		}
		if (result == HoverResult.CLICKED)
		{
			++attempts;
			setState(State.WAIT_BANK, "clicked Bank on banker at " + banker.getWorldLocation());
		}
		setStatus("Opening bank");
	}

	private void waitBank(ScriptContext context)
	{
		if (isBankOpen(context))
		{
			attempts = 0;
			setState(State.DEPOSIT, "bank opened");
			return;
		}
		Player player = context.getPlayer();
		++waitTicks;
		if (waitTicks < BANK_OPEN_WAIT_TICKS || isMoving(player) && waitTicks < BANK_OPEN_WALK_TICKS)
		{
			setStatus("Waiting for bank");
			return;
		}
		setState(State.OPEN_BANK, "bank did not open within " + waitTicks + " ticks");
	}

	private void deposit(ScriptContext context)
	{
		if (!isBankOpen(context))
		{
			setState(State.OPEN_BANK, "bank closed during deposit");
			return;
		}
		Inventory inventory = context.getInventory();
		if (!hasKnife(context))
		{
			return;
		}
		if (onlyKnifeLeft(inventory))
		{
			attempts = 0;
			setState(State.WITHDRAW, "inventory holds only the knife");
			return;
		}

		if (waitTicks > 0)
		{
			// Deposit clicked; give it a moment to land.
			if (++waitTicks <= ACTION_WAIT_TICKS)
			{
				return;
			}
			waitTicks = 0;
			if (++attempts >= ACTION_ATTEMPTS)
			{
				halt("Deposit inventory did not empty the inventory (is the knife slot locked?)");
				return;
			}
		}

		Widget button = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
		if (button == null)
		{
			return;
		}
		GameInput input = context.getInput();
		HoverResult result = hoverClick(context, "deposit", () -> input.hover(button),
			entry -> optionIs(entry, "Deposit inventory"), "Deposit inventory");
		if (result == HoverResult.GAVE_UP)
		{
			halt("Deposit inventory button did not show 'Deposit inventory' as its left click");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			waitTicks = 1;
			log.info("[BowFletcher bank] clicked Deposit inventory");
			setStatus("Depositing");
		}
	}

	private void withdraw(ScriptContext context)
	{
		if (!isBankOpen(context))
		{
			setState(State.OPEN_BANK, "bank closed during withdraw");
			return;
		}
		Inventory inventory = context.getInventory();
		if (inventory.countItem(bow.logId) > 0)
		{
			attempts = 0;
			lastLogCount = inventory.countItem(bow.logId);
			setState(State.CLOSE_BANK, "withdrew " + lastLogCount + " " + itemName(context, bow.logId));
			return;
		}

		if (waitTicks > 0)
		{
			if (++waitTicks <= ACTION_WAIT_TICKS)
			{
				return;
			}
			waitTicks = 0;
			if (++attempts >= ACTION_ATTEMPTS)
			{
				halt("withdrawing " + itemName(context, bow.logId) + " did not work");
				return;
			}
		}

		GameInput input = context.getInput();
		Widget logs = input.findBankItemWidget(bow.logId);
		if (logs == null || logs.getItemQuantity() <= 0)
		{
			// The bank's item list can take a tick to fill after opening.
			if (stateTicks <= BANK_LOAD_TICKS)
			{
				return;
			}
			halt("bank has no " + itemName(context, bow.logId) + " left (made " + bowsMade + ")");
			return;
		}

		HoverResult result = hoverClick(context, "withdraw", () -> input.hover(logs),
			entry -> optionStartsWith(entry, "Withdraw-All"), "Withdraw-All");
		if (result == HoverResult.GAVE_UP)
		{
			halt("left click on " + itemName(context, bow.logId) + " is not Withdraw-All; set it in the bank");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			waitTicks = 1;
			log.info("[BowFletcher bank] clicked Withdraw-All on {} (bank has {})", itemName(context, bow.logId),
				logs.getItemQuantity());
			setStatus("Withdrawing logs");
		}
	}

	private void closeBank(ScriptContext context)
	{
		if (!isBankOpen(context))
		{
			attempts = 0;
			restarts = 0;
			setState(State.USE_KNIFE, "bank closed");
			return;
		}
		if (waitTicks > 0 && ++waitTicks <= 2)
		{
			return;
		}
		if (++attempts > ACTION_ATTEMPTS)
		{
			// Escape did not close it; use the bank's own close button.
			log.info("[BowFletcher bank] Escape did not close the bank, clicking its close button");
			context.getInput().closeInterface(InterfaceID.Bankmain.UNIVERSE);
			attempts = 0;
			waitTicks = 1;
			return;
		}
		context.getInput().pressKey(KeyEvent.VK_ESCAPE);
		waitTicks = 1;
		setStatus("Closing bank");
	}

	private void useKnife(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		if (!hasKnife(context))
		{
			return;
		}
		if (inventory.countItem(bow.logId) == 0)
		{
			setState(State.OPEN_BANK, "no logs in inventory");
			return;
		}
		if (context.getClient().isMenuOpen())
		{
			context.getInput().closeMenu();
			return;
		}
		if (!inventory.isOpen())
		{
			inventory.open();
			return;
		}

		int slot = inventory.findSlot(KNIFE_ID);
		Widget knife = inventory.getSlotWidget(slot);
		if (knife == null)
		{
			return;
		}
		GameInput input = context.getInput();
		HoverResult result = hoverClick(context, "knife", () -> input.hover(knife),
			entry -> optionIs(entry, "Use"), "Use");
		if (result == HoverResult.GAVE_UP)
		{
			halt("left click on the knife is not 'Use'");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			setState(State.USE_ON_LOGS, "clicked Use on knife (slot " + (slot + 1) + ")");
		}
	}

	private void useOnLogs(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		int slot = firstLogSlot(inventory);
		if (slot < 0)
		{
			setState(State.OPEN_BANK, "no logs in inventory");
			return;
		}
		Widget logs = inventory.getSlotWidget(slot);
		if (logs == null)
		{
			return;
		}
		GameInput input = context.getInput();
		// With the knife selected the left click reads "Use Knife -> Logs".
		HoverResult result = hoverClick(context, "logs", () -> input.hover(logs),
			entry -> optionIs(entry, "Use") && entry.getTarget() != null && entry.getTarget().contains("->"),
			"Use Knife -> logs");
		if (result == HoverResult.GAVE_UP)
		{
			if (++attempts >= ACTION_ATTEMPTS)
			{
				halt("could not use the knife on the logs");
				return;
			}
			setState(State.USE_KNIFE, "knife was not selected, selecting it again");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			setState(State.WAIT_MAKE_MENU, "used knife on logs (slot " + (slot + 1) + ")");
		}
	}

	private void waitMakeMenu(ScriptContext context)
	{
		if (context.getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent())
		{
			setState(State.CHOOSE_BOW, "make menu open");
			chooseBow(context);
			return;
		}
		if (++waitTicks >= MAKE_MENU_WAIT_TICKS)
		{
			if (++attempts >= ACTION_ATTEMPTS)
			{
				halt("the fletching menu did not open");
				return;
			}
			setState(State.USE_KNIFE, "make menu did not open within " + waitTicks + " ticks");
		}
	}

	private void chooseBow(ScriptContext context)
	{
		GameInput input = context.getInput();
		if (!context.getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent())
		{
			if (pressedOption >= 0)
			{
				// The menu closed after the key press: fletching started.
				attempts = 0;
				startedFletching = false;
				idleTicks = 0;
				lastLogCount = context.getInventory().countItem(bow.logId);
				++batches;
				setState(State.FLETCHING, "make menu closed after choosing option " + (pressedOption + 1));
				return;
			}
			setState(State.USE_KNIFE, "make menu closed before a choice");
			return;
		}

		if (pressedOption >= 0 && ++waitTicks <= 2)
		{
			return;
		}
		if (pressedOption >= 0 && ++attempts >= ACTION_ATTEMPTS)
		{
			halt("the fletching menu ignored the key press");
			return;
		}

		int option = findMakeOption(context);
		if (option < 0)
		{
			halt("the fletching menu has no " + bowName(context) + " option (level too low?)");
			return;
		}

		// Space repeats the option made last time; until this script has
		// seen that work for our bow, press the option's number key.
		boolean space = option == confirmedOption;
		input.pressKey(space ? KeyEvent.VK_SPACE : KeyEvent.VK_1 + option);
		pressedOption = option;
		waitTicks = 1;
		log.info("[BowFletcher make] {} option {} ({}) via {}", bowName(context), option + 1,
			widgetLabel(context, MAKE_OPTIONS[option]), space ? "Space" : "key " + (option + 1));
		setStatus("Making " + bowName(context));
	}

	private void fletching(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		int logs = inventory.countItem(bow.logId);
		Player player = context.getPlayer();
		boolean animating = player != null && player.getAnimation() != -1;

		if (logs < lastLogCount)
		{
			if (pressedOption >= 0 && !startedFletching)
			{
				// Our bow is being made: Space is safe for this slot now.
				confirmedOption = pressedOption;
			}
			startedFletching = true;
			idleTicks = 0;
			restarts = 0;
		}
		lastLogCount = logs;

		if (logs == 0)
		{
			pressedOption = -1;
			setState(State.OPEN_BANK, "inventory fletched (" + bowsMade + " made)");
			return;
		}

		if (animating)
		{
			idleTicks = 0;
			setStatus("Fletching (" + logs + " logs left)");
			return;
		}

		if (++idleTicks < IDLE_RESTART_TICKS)
		{
			return;
		}

		// Stopped with logs left: a level-up dialog or an interruption.
		if (++restarts > RESTART_LIMIT)
		{
			halt("fletching keeps stopping with logs left");
			return;
		}
		pressedOption = -1;
		attempts = 0;
		log.info("[BowFletcher] idle {} ticks with {} logs left (level-up?), using knife on logs again", idleTicks,
			logs);
		setState(State.USE_KNIFE, "restart after idle");
	}

	// --------------------------------------------------------------- helpers

	/**
	 * Hover, then (next tick, once the mouse has arrived) read the real
	 * left-click option and click only if it is the expected one.
	 */
	private HoverResult hoverClick(ScriptContext context, String key, BooleanSupplier hover,
		Predicate<MenuEntry> expected, String expectedLabel)
	{
		GameInput input = context.getInput();
		if (!key.equals(hoverKey))
		{
			hoverKey = key;
			hovered = false;
			hoverAttempts = 0;
		}
		if (input.isBusy())
		{
			return HoverResult.PENDING;
		}
		if (!hovered)
		{
			if (hoverAttempts >= HOVER_ATTEMPTS)
			{
				hoverKey = null;
				return HoverResult.GAVE_UP;
			}
			if (hover.getAsBoolean())
			{
				hovered = true;
			}
			else
			{
				++hoverAttempts;
			}
			return HoverResult.PENDING;
		}

		MenuEntry entry = input.getLeftClickEntry();
		String top = entry == null ? "none" : GameInput.plainText(entry.getOption() + " " + entry.getTarget());
		if (entry != null && expected.test(entry))
		{
			java.awt.Point mouse = input.getMouseLocation();
			if (mouse != null && input.click(mouse))
			{
				log.info("[BowFletcher click] {} top='{}' at {},{}", key, top, mouse.x, mouse.y);
				hoverKey = null;
				return HoverResult.CLICKED;
			}
			return HoverResult.PENDING;
		}

		++hoverAttempts;
		hovered = false;
		log.info("[BowFletcher click] {} top='{}' is not '{}', re-aiming (try {}/{})", key, top, expectedLabel,
			hoverAttempts, HOVER_ATTEMPTS);
		return HoverResult.PENDING;
	}

	private static boolean optionIs(MenuEntry entry, String option)
	{
		return entry != null && GameInput.plainText(entry.getOption()).equalsIgnoreCase(option);
	}

	private static boolean optionStartsWith(MenuEntry entry, String prefix)
	{
		return entry != null && GameInput.plainText(entry.getOption()).toLowerCase().startsWith(prefix.toLowerCase());
	}

	private boolean hasKnife(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		int slot = inventory.findSlot(KNIFE_ID);
		if (slot < 0)
		{
			halt("no knife in the inventory (keep it in the locked first slot)");
			return false;
		}
		return true;
	}

	private static boolean onlyKnifeLeft(Inventory inventory)
	{
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1 && item.getId() != KNIFE_ID)
			{
				return false;
			}
		}
		return true;
	}

	private int firstLogSlot(Inventory inventory)
	{
		return inventory.findSlot(bow.logId);
	}

	private void countBows(Inventory inventory)
	{
		int now = inventory.countItem(bow.productId);
		if (lastProductCount >= 0 && now > lastProductCount)
		{
			bowsMade += now - lastProductCount;
		}
		lastProductCount = now;
	}

	private int findMakeOption(ScriptContext context)
	{
		Client client = context.getClient();
		String product = itemName(context, bow.productId).toLowerCase();
		for (int i = 0; i < MAKE_OPTIONS.length; i++)
		{
			Widget option = client.getWidget(MAKE_OPTIONS[i]);
			if (option == null || option.isHidden())
			{
				continue;
			}
			if (matchesProduct(option, product))
			{
				return i;
			}
		}
		return -1;
	}

	private boolean matchesProduct(Widget widget, String product)
	{
		if (widget.getItemId() == bow.productId
			|| GameInput.plainText(widget.getName()).toLowerCase().contains(product)
			|| GameInput.plainText(widget.getText()).toLowerCase().contains(product))
		{
			return true;
		}
		for (Widget[] children : Arrays.asList(widget.getDynamicChildren(), widget.getStaticChildren()))
		{
			if (children == null)
			{
				continue;
			}
			for (Widget child : children)
			{
				if (child != null && (child.getItemId() == bow.productId
					|| GameInput.plainText(child.getText()).toLowerCase().contains(product)))
				{
					return true;
				}
			}
		}
		return false;
	}

	private static String widgetLabel(ScriptContext context, int componentId)
	{
		Widget widget = context.getClient().getWidget(componentId);
		return widget == null ? "?" : GameInput.plainText(widget.getName());
	}

	private static boolean isBankOpen(ScriptContext context)
	{
		return context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent();
	}

	/**
	 * Closest-booth mode: the nearest bank booth or bank chest object.
	 * Otherwise the nearest of a banker NPC, chest or booth.
	 */
	private Object findBankTarget(ScriptContext context)
	{
		WorldPoint location = context.getPlayerLocation();
		if (location == null)
		{
			return null;
		}
		List<TileObject> objects = context.getTargets().getTileObjects(location, BANK_SEARCH_DISTANCE);
		Optional<TileObject> booth = objects.stream()
			.filter(object -> bankAction(context, object) != null)
			.min(Comparator.comparingInt(object -> object.getWorldLocation().distanceTo(location)));
		if (closestBooth)
		{
			return booth.orElse(null);
		}

		Optional<NPC> banker = findBanker(context, location);
		if (banker.isPresent() && (booth.isEmpty()
			|| banker.get().getWorldLocation().distanceTo(location) < booth.get().getWorldLocation().distanceTo(location)))
		{
			return banker.get();
		}
		return booth.orElse(null);
	}

	private static Optional<NPC> findBanker(ScriptContext context, WorldPoint location)
	{
		WorldView worldView = context.getClient().getTopLevelWorldView();
		if (worldView == null)
		{
			return Optional.empty();
		}
		NPC best = null;
		for (NPC npc : worldView.npcs())
		{
			NPCComposition composition = npc == null ? null : npc.getTransformedComposition();
			if (composition == null || composition.getActions() == null || npc.getWorldLocation() == null
				|| npc.getWorldLocation().distanceTo(location) > BANK_SEARCH_DISTANCE)
			{
				continue;
			}
			boolean bank = Arrays.stream(composition.getActions()).anyMatch(a -> "Bank".equalsIgnoreCase(a));
			if (bank && (best == null
				|| npc.getWorldLocation().distanceTo(location) < best.getWorldLocation().distanceTo(location)))
			{
				best = npc;
			}
		}
		return Optional.ofNullable(best);
	}

	/** @return "Bank" or "Use" for a bank booth/chest object, else null */
	private static String bankAction(ScriptContext context, TileObject object)
	{
		ObjectComposition composition = composition(context, object);
		if (composition == null || composition.getActions() == null || object.getWorldLocation() == null)
		{
			return null;
		}
		String name = composition.getName() == null ? "" : composition.getName().toLowerCase();
		String[] actions = composition.getActions();
		for (String action : actions)
		{
			if ("Bank".equalsIgnoreCase(action) && (name.contains("bank") || name.contains("chest")))
			{
				return "Bank";
			}
		}
		if (name.contains("bank chest"))
		{
			for (String action : actions)
			{
				if ("Use".equalsIgnoreCase(action))
				{
					return "Use";
				}
			}
		}
		return null;
	}

	private static ObjectComposition composition(ScriptContext context, TileObject object)
	{
		ObjectComposition composition = context.getClient().getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			ObjectComposition impostor = composition.getImpostor();
			if (impostor != null)
			{
				composition = impostor;
			}
		}
		return composition;
	}

	private static String objectName(ScriptContext context, TileObject object)
	{
		ObjectComposition composition = composition(context, object);
		return composition == null ? "object " + object.getId() : composition.getName();
	}

	private static boolean isMoving(Player player)
	{
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	private static String itemName(ScriptContext context, int itemId)
	{
		ItemComposition composition = context.getClient().getItemDefinition(itemId);
		String name = composition == null ? null : composition.getName();
		return name == null ? String.valueOf(itemId) : name;
	}

	private String bowName(ScriptContext context)
	{
		return itemName(context, bow.productId);
	}

	@Override
	public List<String> getOverlayLines()
	{
		return Arrays.asList(
			"Status: " + getStatus(),
			"Bow: " + bow + " | State: " + (state == null ? "-" : state.name()),
			"Made: " + bowsMade + (amount > 0 ? " / " + amount : " (unlimited)") + " | Inventories: " + batches,
			"Bank: " + (closestBooth ? "closest booth/chest" : "closest banker/chest/booth"));
	}
}
