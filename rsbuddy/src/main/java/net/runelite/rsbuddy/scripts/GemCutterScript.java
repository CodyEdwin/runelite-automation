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
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;
import net.runelite.rsbuddy.target.BankFinder;

/**
 * Cuts gems at a bank (built for the Grand Exchange): deposit inventory (the
 * chisel stays in the locked first slot), left-click the uncut gems (left
 * click set to Withdraw-All), Escape, chisel on gem, choose the cut gem in the
 * make menu (or let a single gem cut directly), wait for the inventory to be
 * cut, repeat. Re-starts after a level-up.
 */
public final class GemCutterScript extends Script
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GemCutterScript.class);

	private static final int CHISEL_ID = ItemID.CHISEL; // 1755
	private static final int BANK_SEARCH_DISTANCE = 20;

	private static final int HOVER_ATTEMPTS = 3;
	private static final int BANK_OPEN_WAIT_TICKS = 8;
	private static final int BANK_OPEN_WALK_TICKS = 20;
	private static final int BANK_OPEN_ATTEMPTS = 4;
	private static final int BANK_LOAD_TICKS = 3;
	private static final int ACTION_WAIT_TICKS = 5;
	private static final int ACTION_ATTEMPTS = 3;
	private static final int MAKE_MENU_WAIT_TICKS = 5;
	/** No animation and no log used for this long with uncut left: restart (level-up etc.). */
	private static final int RESTART_LIMIT = 5;
	/** Pause before each client-tick restart step (ms). */
	private static final long FAST_STEP_MIN_MS = 60;
	private static final long FAST_STEP_MAX_MS = 140;
	private static final int BUSY_STUCK_TICKS = 15;
	private static final int STALL_LOG_TICKS = 5;

	/** Gem choices. Names match the plugin's GemCutterGemType (survives hot reload). */
	enum Gem
	{
		OPAL(ItemID.UNCUT_OPAL, ItemID.OPAL),
		JADE(ItemID.UNCUT_JADE, ItemID.JADE),
		RED_TOPAZ(ItemID.UNCUT_RED_TOPAZ, ItemID.RED_TOPAZ),
		SAPPHIRE(ItemID.UNCUT_SAPPHIRE, ItemID.SAPPHIRE),
		EMERALD(ItemID.UNCUT_EMERALD, ItemID.EMERALD),
		RUBY(ItemID.UNCUT_RUBY, ItemID.RUBY),
		DIAMOND(ItemID.UNCUT_DIAMOND, ItemID.DIAMOND),
		DRAGONSTONE(ItemID.UNCUT_DRAGONSTONE, ItemID.DRAGONSTONE),
		ONYX(ItemID.UNCUT_ONYX, ItemID.ONYX),
		ZENYTE(ItemID.UNCUT_ZENYTE, ItemID.ZENYTE);

		private final int uncutId;
		private final int productId;

		Gem(int uncutId, int cutId)
		{
			this.uncutId = uncutId;
			this.productId = cutId;
		}
	}

	private enum State
	{
		OPEN_BANK,
		WAIT_BANK,
		DEPOSIT,
		WITHDRAW,
		CLOSE_BANK,
		USE_CHISEL,
		USE_ON_GEM,
		WAIT_MAKE_MENU,
		CHOOSE_GEM,
		CUTTING,
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

	private final Gem gem;
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

	private int gemsCut;
	private int lastProductCount = -1;
	private int lastUncutCount = -1;
	private int idleTicks;
	private final SkillInterruptWatcher interruptWatcher = new SkillInterruptWatcher(Skill.CRAFTING);
	/** Client-tick fast path: earliest time (ms) for the next restart step, 0 = not armed. */
	private long fastStepAtMs;
	private int restarts;
	private int batches;
	/** Make-menu slot confirmed to make our gem last time; Space repeats it. */
	private int confirmedOption = -1;
	private int pressedOption = -1;
	private boolean startedCutting;

	public GemCutterScript()
	{
		this(Gem.SAPPHIRE.name(), 0, true);
	}

	/**
	 * @param gem a {@link Gem} name (SAPPHIRE, EMERALD, ...)
	 * @param amount gems to cut, 0 = unlimited
	 * @param closestBooth open the closest bank booth/chest instead of the closest banker, chest or booth
	 */
	public GemCutterScript(String gem, int amount, boolean closestBooth)
	{
		super("Gem Cutter", "Cuts banked uncut gems with the chisel in the locked first slot, banks them, repeats.");
		this.gem = parseGem(gem);
		this.amount = Math.max(0, amount);
		this.closestBooth = closestBooth;
	}

	private static Gem parseGem(String name)
	{
		try
		{
			return name == null ? Gem.SAPPHIRE : Gem.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return Gem.SAPPHIRE;
		}
	}

	@Override
	public boolean physicalInputOnly()
	{
		return true;
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
		setState(State.OPEN_BANK, "started: " + gem + ", amount=" + (amount == 0 ? "unlimited" : amount)
			+ ", closest booth=" + closestBooth);
		waitTicks = 0;
		attempts = 0;
		busyTicks = 0;
		exceptionCount = 0;
		hoverKey = null;
		gemsCut = 0;
		lastProductCount = -1;
		lastUncutCount = -1;
		idleTicks = 0;
		restarts = 0;
		batches = 0;
		confirmedOption = -1;
		pressedOption = -1;
		startedCutting = false;
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
			log.error("[GemCutter] exception #{} in state={} status={}", exceptionCount, state, getStatus(), ex);
			getContext().getInput().reset();
			hoverKey = null;
			if (exceptionCount >= 20)
			{
				throw ex;
			}
		}
	}

	@Override
	protected void onClientTick()
	{
		// Client thread, every frame. Walks the restart chain (chisel ->
		// gems -> make menu) as soon as the mouse is free instead of
		// waiting for the next game tick, after a short human pause.
		try
		{
			fastStep();
		}
		catch (RuntimeException ex)
		{
			log.error("[GemCutter] fast step failed in state={}", state, ex);
			getContext().getInput().reset();
			hoverKey = null;
			fastStepAtMs = 0;
		}
	}

	private void fastStep()
	{
		State current = state;
		if (current != State.USE_CHISEL && current != State.USE_ON_GEM && current != State.WAIT_MAKE_MENU)
		{
			fastStepAtMs = 0;
			return;
		}
		ScriptContext context = getContext();
		if (context == null || !context.isLoggedIn() || context.getInput().isBusy())
		{
			fastStepAtMs = 0;
			return;
		}
		long now = System.currentTimeMillis();
		if (fastStepAtMs == 0)
		{
			// Let the hovered menu entry refresh, like a player glancing
			// before the next click (the move itself adds its own reaction).
			fastStepAtMs = now + ThreadLocalRandom.current().nextLong(FAST_STEP_MIN_MS, FAST_STEP_MAX_MS + 1);
			return;
		}
		if (now < fastStepAtMs)
		{
			return;
		}
		fastStepAtMs = 0;
		switch (current)
		{
			case USE_CHISEL:
				useChisel(context);
				break;
			case USE_ON_GEM:
				useOnGem(context);
				break;
			default:
				if (pressedOption < 0 && context.getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent())
				{
					setState(State.CHOOSE_GEM, "make menu open");
					chooseGem(context);
				}
				break;
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
		if (state != State.CUTTING
			&& (stateTicks == STALL_LOG_TICKS + 1 || stateTicks > STALL_LOG_TICKS && stateTicks % 20 == 0))
		{
			log.info("[GemCutter] stall? {} ticks in state={} status={}", stateTicks, state, getStatus());
		}

		GameInput input = context.getInput();
		busyTicks = input.isBusy() ? busyTicks + 1 : 0;
		if (busyTicks > BUSY_STUCK_TICKS)
		{
			log.info("[GemCutter] input busy for {} ticks in state={}, resetting input", busyTicks, state);
			input.reset();
			busyTicks = 0;
		}

		countGems(context.getInventory());
		if (amount > 0 && gemsCut >= amount)
		{
			halt("cut " + gemsCut + " " + gemName(context) + ", amount reached");
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
			case USE_CHISEL:
				useChisel(context);
				break;
			case USE_ON_GEM:
				useOnGem(context);
				break;
			case WAIT_MAKE_MENU:
				waitMakeMenu(context);
				break;
			case CHOOSE_GEM:
				chooseGem(context);
				break;
			case CUTTING:
				cutting(context);
				break;
			default:
				break;
		}
	}

	private void setState(State next, String reason)
	{
		if (next != state)
		{
			log.info("[GemCutter] {} -> {}: {}", state, next, reason);
			state = next;
			stateTicks = 0;
			waitTicks = 0;
			hoverKey = null;
		}
	}

	private void halt(String reason)
	{
		log.info("[GemCutter] halting: {}", reason);
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
		if (!hasChisel(context))
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
			halt("no bank booth, chest or banker within " + BANK_SEARCH_DISTANCE + " tiles (nearby objects logged)");
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
			HoverResult result = hoverClick(context, "bank@" + object.getWorldLocation(), () -> input.hoverCenter(object),
				entry -> optionIs(entry, action), action);
			if (result == HoverResult.GAVE_UP)
			{
				// Left click kept showing something else: never act
				// invisibly. Turn the camera toward it and re-aim.
				++attempts;
				log.info("[GemCutter bank] top option never '{}' on the booth (try {}/{}), turning camera and re-aiming",
					action, attempts, BANK_OPEN_ATTEMPTS);
				input.turnCameraToward(object.getWorldLocation());
				return;
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
			++attempts;
			log.info("[GemCutter bank] top option never 'Bank' on the banker (try {}/{}), turning camera and re-aiming",
				attempts, BANK_OPEN_ATTEMPTS);
			input.turnCameraToward(banker.getWorldLocation());
			return;
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
		if (!hasChisel(context))
		{
			return;
		}
		if (onlyChiselLeft(inventory))
		{
			attempts = 0;
			setState(State.WITHDRAW, "inventory holds only the chisel");
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
				halt("Deposit inventory did not empty the inventory (is the chisel slot locked?)");
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
			log.info("[GemCutter bank] clicked Deposit inventory");
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
		if (inventory.countItem(gem.uncutId) > 0)
		{
			attempts = 0;
			lastUncutCount = inventory.countItem(gem.uncutId);
			setState(State.CLOSE_BANK, "withdrew " + lastUncutCount + " " + itemName(context, gem.uncutId));
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
				halt("withdrawing " + itemName(context, gem.uncutId) + " did not work");
				return;
			}
		}

		GameInput input = context.getInput();
		Widget uncut = input.findBankItemWidget(gem.uncutId);
		if (uncut == null || uncut.getItemQuantity() <= 0)
		{
			// The bank's item list can take a tick to fill after opening.
			if (stateTicks <= BANK_LOAD_TICKS)
			{
				return;
			}
			halt("bank has no " + itemName(context, gem.uncutId) + " left (cut " + gemsCut + ")");
			return;
		}

		HoverResult result = hoverClick(context, "withdraw", () -> input.hover(uncut),
			entry -> optionStartsWith(entry, "Withdraw-All"), "Withdraw-All");
		if (result == HoverResult.GAVE_UP)
		{
			halt("left click on " + itemName(context, gem.uncutId) + " is not Withdraw-All; set it in the bank");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			waitTicks = 1;
			log.info("[GemCutter bank] clicked Withdraw-All on {} (bank has {})", itemName(context, gem.uncutId),
				uncut.getItemQuantity());
			setStatus("Withdrawing uncut gems");
		}
	}

	private void closeBank(ScriptContext context)
	{
		if (!isBankOpen(context))
		{
			attempts = 0;
			restarts = 0;
			setState(State.USE_CHISEL, "bank closed");
			return;
		}
		if (waitTicks > 0 && ++waitTicks <= 2)
		{
			return;
		}
		if (++attempts > ACTION_ATTEMPTS)
		{
			// Escape did not close it; use the bank's own close button.
			log.info("[GemCutter bank] Escape did not close the bank, clicking its close button");
			context.getInput().closeInterface(InterfaceID.Bankmain.UNIVERSE);
			attempts = 0;
			waitTicks = 1;
			return;
		}
		context.getInput().pressKey(KeyEvent.VK_ESCAPE);
		waitTicks = 1;
		setStatus("Closing bank");
	}

	private void useChisel(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		if (!hasChisel(context))
		{
			return;
		}
		if (inventory.countItem(gem.uncutId) == 0)
		{
			setState(State.OPEN_BANK, "no uncut gems in inventory");
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

		int slot = inventory.findSlot(CHISEL_ID);
		Widget chisel = inventory.getSlotWidget(slot);
		if (chisel == null)
		{
			return;
		}
		GameInput input = context.getInput();
		HoverResult result = hoverClick(context, "chisel", () -> input.hover(chisel),
			entry -> optionIs(entry, "Use"), "Use");
		if (result == HoverResult.GAVE_UP)
		{
			halt("left click on the chisel is not 'Use'");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			setState(State.USE_ON_GEM, "clicked Use on chisel (slot " + (slot + 1) + ")");
		}
	}

	private void useOnGem(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		int slot = firstUncutSlot(inventory);
		if (slot < 0)
		{
			setState(State.OPEN_BANK, "no uncut gems in inventory");
			return;
		}
		Widget uncut = inventory.getSlotWidget(slot);
		if (uncut == null)
		{
			return;
		}
		GameInput input = context.getInput();
		// With the chisel selected the left click reads "Use Chisel -> Uncut gem".
		HoverResult result = hoverClick(context, "uncut", () -> input.hover(uncut),
			entry -> optionIs(entry, "Use") && entry.getTarget() != null && entry.getTarget().contains("->"),
			"Use Chisel -> uncut gem");
		if (result == HoverResult.GAVE_UP)
		{
			if (++attempts >= ACTION_ATTEMPTS)
			{
				halt("could not use the chisel on the uncut gems");
				return;
			}
			setState(State.USE_CHISEL, "chisel was not selected, selecting it again");
			return;
		}
		if (result == HoverResult.CLICKED)
		{
			setState(State.WAIT_MAKE_MENU, "used chisel on uncut gem (slot " + (slot + 1) + ")");
		}
	}

	private void waitMakeMenu(ScriptContext context)
	{
		if (context.getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent())
		{
			setState(State.CHOOSE_GEM, "make menu open");
			chooseGem(context);
			return;
		}
		// A single uncut gem is cut straight away, with no make menu.
		Player player = context.getPlayer();
		int uncut = context.getInventory().countItem(gem.uncutId);
		if (player != null && player.getAnimation() != -1 || uncut < lastUncutCount)
		{
			attempts = 0;
			startedCutting = true;
			idleTicks = 0;
			pressedOption = -1;
			++batches;
			interruptWatcher.reset(context.getClient());
			setState(State.CUTTING, "cutting started without the make menu");
			return;
		}
		if (++waitTicks >= MAKE_MENU_WAIT_TICKS)
		{
			if (++attempts >= ACTION_ATTEMPTS)
			{
				halt("the gem cutting menu did not open and cutting did not start");
				return;
			}
			setState(State.USE_CHISEL, "make menu did not open within " + waitTicks + " ticks");
		}
	}

	private void chooseGem(ScriptContext context)
	{
		GameInput input = context.getInput();
		if (!context.getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent())
		{
			if (pressedOption >= 0)
			{
				// The menu closed after the key press: cutting started.
				attempts = 0;
				startedCutting = false;
				idleTicks = 0;
				lastUncutCount = context.getInventory().countItem(gem.uncutId);
				interruptWatcher.reset(context.getClient());
				++batches;
				setState(State.CUTTING, "make menu closed after choosing option " + (pressedOption + 1));
				return;
			}
			setState(State.USE_CHISEL, "make menu closed before a choice");
			return;
		}

		if (pressedOption >= 0 && ++waitTicks <= 2)
		{
			return;
		}
		if (pressedOption >= 0 && ++attempts >= ACTION_ATTEMPTS)
		{
			halt("the gem cutting menu ignored the key press");
			return;
		}

		int option = findMakeOption(context);
		if (option < 0)
		{
			halt("the cutting menu has no " + gemName(context) + " option (Crafting level too low?)");
			return;
		}

		// Space repeats the option made last time; until this script has
		// seen that work for our gem, press the option's number key.
		boolean space = option == confirmedOption;
		input.pressKey(space ? KeyEvent.VK_SPACE : KeyEvent.VK_1 + option);
		pressedOption = option;
		waitTicks = 1;
		log.info("[GemCutter make] {} option {} ({}) via {}", gemName(context), option + 1,
			widgetLabel(context, MAKE_OPTIONS[option]), space ? "Space" : "key " + (option + 1));
		setStatus("Cutting " + gemName(context));
	}

	private void cutting(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		int uncut = inventory.countItem(gem.uncutId);
		Player player = context.getPlayer();
		boolean animating = player != null && player.getAnimation() != -1;

		boolean usedItem = lastUncutCount >= 0 && uncut < lastUncutCount;
		if (usedItem)
		{
			if (pressedOption >= 0 && !startedCutting)
			{
				// Our gem is being made: Space is safe for this slot now.
				confirmedOption = pressedOption;
			}
			startedCutting = true;
			idleTicks = 0;
			restarts = 0;
		}
		lastUncutCount = uncut;

		if (uncut == 0)
		{
			pressedOption = -1;
			setState(State.OPEN_BANK, "inventory cut (" + gemsCut + " cut so far)");
			return;
		}

		// Level-up (real level up or the dialog opening) stops the batch at
		// once; otherwise 2 ticks with no animation and no item used. One
		// tick without animation between two items is normal.
		String interrupt = interruptWatcher.update(context.getClient(), animating, usedItem);
		if (interrupt == null)
		{
			idleTicks = animating ? 0 : idleTicks + 1;
			setStatus("Cutting (" + uncut + " uncut left)");
			return;
		}

		if (++restarts > RESTART_LIMIT)
		{
			halt("cutting keeps stopping with uncut gems left");
			return;
		}
		pressedOption = -1;
		attempts = 0;
		fastStepAtMs = 0;
		log.info("[GemCutter] {} with {} uncut gems left, using chisel on gems again right away", interrupt, uncut);
		setState(State.USE_CHISEL, "restart: " + interrupt);
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
				log.info("[GemCutter click] {} top='{}' at {},{}", key, top, mouse.x, mouse.y);
				hoverKey = null;
				return HoverResult.CLICKED;
			}
			return HoverResult.PENDING;
		}

		++hoverAttempts;
		hovered = false;
		log.info("[GemCutter click] {} top='{}' is not '{}', re-aiming (try {}/{})", key, top, expectedLabel,
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

	private boolean hasChisel(ScriptContext context)
	{
		Inventory inventory = context.getInventory();
		int slot = inventory.findSlot(CHISEL_ID);
		if (slot < 0)
		{
			halt("no chisel in the inventory (keep it in the locked first slot)");
			return false;
		}
		return true;
	}

	private static boolean onlyChiselLeft(Inventory inventory)
	{
		for (Item item : inventory.getItems())
		{
			if (item != null && item.getId() != -1 && item.getId() != CHISEL_ID)
			{
				return false;
			}
		}
		return true;
	}

	private int firstUncutSlot(Inventory inventory)
	{
		return inventory.findSlot(gem.uncutId);
	}

	private void countGems(Inventory inventory)
	{
		int now = inventory.countItem(gem.productId);
		if (lastProductCount >= 0 && now > lastProductCount)
		{
			gemsCut += now - lastProductCount;
		}
		lastProductCount = now;
	}

	private int findMakeOption(ScriptContext context)
	{
		Client client = context.getClient();
		String product = itemName(context, gem.productId).toLowerCase();
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
		if (widget.getItemId() == gem.productId
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
				if (child != null && (child.getItemId() == gem.productId
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
	 * The bank to open, via the shared {@link BankFinder}: closest booth or
	 * chest (Grand Exchange booths included) when that option is on, falling
	 * back to a banker; otherwise the closest of banker, chest or booth.
	 *
	 * @return a TileObject or an NPC, or null
	 */
	private Object findBankTarget(ScriptContext context)
	{
		BankFinder.BankTarget target = BankFinder.find(context.getClient(), context.getTargets(),
			context.getPlayerLocation(), BANK_SEARCH_DISTANCE, closestBooth, "[GemCutter bank]");
		if (target == null)
		{
			return null;
		}
		return target.getObject() != null ? target.getObject() : target.getNpc();
	}

	private static String bankAction(ScriptContext context, TileObject object)
	{
		return BankFinder.bankAction(BankFinder.composition(context.getClient(), object));
	}

	private static ObjectComposition composition(ScriptContext context, TileObject object)
	{
		return BankFinder.composition(context.getClient(), object);
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

	private String gemName(ScriptContext context)
	{
		return itemName(context, gem.productId);
	}

	@Override
	public List<String> getOverlayLines()
	{
		return Arrays.asList(
			"Status: " + getStatus(),
			"Gem: " + gem + " | State: " + (state == null ? "-" : state.name()),
			"Cut: " + gemsCut + (amount > 0 ? " / " + amount : " (unlimited)") + " | Inventories: " + batches,
			"Bank: " + (closestBooth ? "closest booth/chest/banker" : "banker first, then booth/chest"));
	}
}
