/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
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
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.ObjectID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.RoutineContext;
import net.runelite.rsbuddy.routine.TaskRoutine;

/**
 * Smelts bars at the Edgeville furnace. Withdraws the ores for the selected
 * bar from the bank, walks to the furnace by minimap, chooses the bar in the
 * smelting menu, waits for the batch, then walks back and banks the bars.
 */
public final class EdgevilleSmelterScript extends TaskRoutine
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(EdgevilleSmelterScript.class);

	private static final WorldPoint FURNACE_TILE = new WorldPoint(3110, 3499, 0);
	private static final int BANK_SEARCH_DISTANCE = 20;
	private static final int FURNACE_SEARCH_DISTANCE = 25;
	private static final int FURNACE_REACH = 4;
	private static final int BANK_LOAD_TICKS = 4;
	private static final int DEPOSIT_ATTEMPTS = 4;
	private static final int WITHDRAW_FAIL_LIMIT = 8;
	private static final int MENU_WAIT_TICKS = 6;
	private static final int MENU_ATTEMPTS = 4;
	private static final int RESTART_LIMIT = 6;
	private static final int WALK_ATTEMPTS = 8;
	private static final int LEVEL_UP_CLICK_ATTEMPTS = 3;

	/** Skill make-menu option slots, in order (key 1 = A, 2 = B, ...). */
	private static final int[] MAKE_OPTIONS = {
		InterfaceID.Skillmulti.A, InterfaceID.Skillmulti.B, InterfaceID.Skillmulti.C, InterfaceID.Skillmulti.D,
		InterfaceID.Skillmulti.E, InterfaceID.Skillmulti.F, InterfaceID.Skillmulti.G, InterfaceID.Skillmulti.H,
		InterfaceID.Skillmulti.I
	};

	/** Bar choices. Names match the plugin's EdgevilleSmelterBarType (survives hot reload). */
	enum Bar
	{
		BRONZE(ItemID.BRONZE_BAR, new Ingredient(ItemID.COPPER_ORE, 1), new Ingredient(ItemID.TIN_ORE, 1)),
		IRON(ItemID.IRON_BAR, new Ingredient(ItemID.IRON_ORE, 1)),
		STEEL(ItemID.STEEL_BAR, new Ingredient(ItemID.IRON_ORE, 1), new Ingredient(ItemID.COAL, 2)),
		SILVER(ItemID.SILVER_BAR, new Ingredient(ItemID.SILVER_ORE, 1)),
		GOLD(ItemID.GOLD_BAR, new Ingredient(ItemID.GOLD_ORE, 1)),
		MITHRIL(ItemID.MITHRIL_BAR, new Ingredient(ItemID.MITHRIL_ORE, 1), new Ingredient(ItemID.COAL, 2)),
		ADAMANTITE(ItemID.ADAMANTITE_BAR, new Ingredient(ItemID.ADAMANTITE_ORE, 1), new Ingredient(ItemID.COAL, 4)),
		RUNITE(ItemID.RUNITE_BAR, new Ingredient(ItemID.RUNITE_ORE, 1), new Ingredient(ItemID.COAL, 6));

		private final int barId;
		private final Ingredient[] ingredients;

		Bar(int barId, Ingredient... ingredients)
		{
			this.barId = barId;
			this.ingredients = ingredients;
		}

		int slotsPerBar()
		{
			int slots = 0;
			for (Ingredient ingredient : ingredients)
			{
				slots += ingredient.amount;
			}
			return slots;
		}

		boolean isSmelterItem(int itemId)
		{
			if (itemId == barId)
			{
				return true;
			}
			for (Ingredient ingredient : ingredients)
			{
				if (ingredient.itemId == itemId)
				{
					return true;
				}
			}
			return false;
		}
	}

	private static final class Ingredient
	{
		private final int itemId;
		private final int amount;

		private Ingredient(int itemId, int amount)
		{
			this.itemId = itemId;
			this.amount = amount;
		}
	}

	private enum Phase
	{
		BANK,
		WALK_TO_FURNACE,
		CLICK_FURNACE,
		WAIT_MENU,
		CHOOSE_BAR,
		SMELTING
	}

	private enum BankStage
	{
		DEPOSIT,
		WITHDRAW,
		LEAVE
	}

	private final Bar bar;
	private final SkillInterruptWatcher interruptWatcher = new SkillInterruptWatcher(Skill.SMITHING);

	private Phase phase;
	private BankStage bankStage;
	private int[] targetCounts = new int[0];
	private int tripBars;

	private int bankTicks;
	private int depositAttempts;
	private int withdrawFailures;
	private int leaveAttempts;
	private int walkAttempts;
	private int menuWaitTicks;
	private int menuAttempts;
	private int allAttempts;
	private int levelUpAttempts;
	private int restarts;

	private boolean barKeyPressed;
	private boolean allClicked;
	private int lastOreCount = -1;

	public EdgevilleSmelterScript()
	{
		this(Bar.STEEL.name());
	}

	/**
	 * @param barType a {@link Bar} name (BRONZE, IRON, STEEL, ...)
	 */
	public EdgevilleSmelterScript(String barType)
	{
		super("Edgeville Smelter", "Smelts bars at the Edgeville furnace, banking the bars and re-stocking ores.");
		this.bar = parseBar(barType);
	}

	private static Bar parseBar(String name)
	{
		try
		{
			return name == null ? Bar.STEEL : Bar.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return Bar.STEEL;
		}
	}

	@Override
	public boolean useFastMouse()
	{
		return true;
	}

	@Override
	protected void setup()
	{
		phase = null;
		bankStage = BankStage.DEPOSIT;
		targetCounts = new int[bar.ingredients.length];
		tripBars = 0;
		bankTicks = 0;
		depositAttempts = 0;
		withdrawFailures = 0;
		leaveAttempts = 0;
		walkAttempts = 0;
		menuWaitTicks = 0;
		menuAttempts = 0;
		allAttempts = 0;
		levelUpAttempts = 0;
		restarts = 0;
		barKeyPressed = false;
		allClicked = false;
		lastOreCount = -1;
		setPhase(Phase.BANK, "started: " + bar.name());
		setStatus("Ready: " + bar.name());
		actions.allowWithBankOpen("furnace");

		task("dismiss level up", this::levelUpOpen, this::dismissLevelUp);
		task("bank", () -> phase == Phase.BANK, this::bankStep);
		task("walk to furnace", () -> phase == Phase.WALK_TO_FURNACE, this::walkToFurnace);
		task("click furnace", () -> phase == Phase.CLICK_FURNACE, this::clickFurnace);
		task("smelting menu", () -> phase == Phase.WAIT_MENU || phase == Phase.CHOOSE_BAR, this::menuStep);
		task("smelting", () -> phase == Phase.SMELTING, this::smeltingStep);
	}

	private void setPhase(Phase next, String reason)
	{
		if (next != phase)
		{
			log.info("[EdgevilleSmelter] {} -> {}: {}", phase, next, reason);
			phase = next;
			menuWaitTicks = 0;
			menuAttempts = 0;
			allAttempts = 0;
			walkAttempts = 0;
			leaveAttempts = 0;
		}
	}

	private void stop(String reason)
	{
		log.info("[EdgevilleSmelter] halting: {}", reason);
		super.halt("Stopped: " + reason);
	}

	// ----------------------------------------------------------------- bank

	private void bankStep()
	{
		RoutineContext context = getContext();
		Inventory inventory = context.getInventory();

		if (bankStage == BankStage.LEAVE)
		{
			setPhase(Phase.CLICK_FURNACE, "ores withdrawn, clicking the visible furnace");
			setStatus("Clicking the furnace");
			return;
		}

		if (!actions.bankOpen())
		{
			actions.openNearestBank(BANK_SEARCH_DISTANCE, true);
			return;
		}

		++bankTicks;

		switch (bankStage)
		{
			case DEPOSIT:
				deposit(context, inventory);
				break;
			case WITHDRAW:
				withdraw(inventory);
				break;
			default:
				break;
		}
	}

	private void deposit(RoutineContext context, Inventory inventory)
	{
		if (inventory.getEmptySlots() < Inventory.SIZE)
		{
			if (depositAttempts >= DEPOSIT_ATTEMPTS)
			{
				if (hasSmelterItems(inventory))
				{
					stop("Deposit inventory did not clear the ores or bars (are they in a locked slot?)");
					return;
				}
			}
			else
			{
				Widget deposit = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
				GameInput input = context.getInput();
				if (deposit != null && !input.isBusy() && input.click(deposit))
				{
					++depositAttempts;
					cooldown(1);
				}
				setStatus("Depositing");
				return;
			}
		}

		if (!planTrip(context, inventory))
		{
			return;
		}

		bankStage = BankStage.WITHDRAW;
		bankTicks = 0;
		depositAttempts = 0;
		withdrawFailures = 0;
		setStatus("Withdrawing ores");
	}

	private boolean planTrip(RoutineContext context, Inventory inventory)
	{
		ItemContainer bank = context.getClient().getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			setStatus("Waiting for the bank items");
			return false;
		}

		int maxBars = inventory.getEmptySlots() / bar.slotsPerBar();
		for (Ingredient ingredient : bar.ingredients)
		{
			int available = bank.count(ingredient.itemId);
			if (available < ingredient.amount)
			{
				if (bankTicks < BANK_LOAD_TICKS)
				{
					setStatus("Waiting for the bank items");
					return false;
				}
				stop("bank has " + available + " " + itemName(context, ingredient.itemId) + " but a "
					+ bar.name() + " needs " + ingredient.amount);
				return false;
			}
			maxBars = Math.min(maxBars, available / ingredient.amount);
		}

		if (maxBars <= 0)
		{
			stop("not enough free inventory slots for a " + bar.name());
			return false;
		}

		tripBars = maxBars;
		for (int i = 0; i < bar.ingredients.length; i++)
		{
			targetCounts[i] = maxBars * bar.ingredients[i].amount;
		}
		return true;
	}

	private void withdraw(Inventory inventory)
	{
		if (bar == Bar.STEEL)
		{
			withdrawSteel(inventory);
			return;
		}

		RoutineContext context = getContext();
		for (int i = 0; i < bar.ingredients.length; i++)
		{
			Ingredient ingredient = bar.ingredients[i];
			int have = inventory.countItem(ingredient.itemId);
			int missing = targetCounts[i] - have;
			if (missing <= 0)
			{
				continue;
			}

			int amount = missing >= 10 ? 10 : missing >= 5 ? 5 : 1;
			String option = "Withdraw-" + amount;
			GameInput input = context.getInput();
			if (input.withdrawBankItem(ingredient.itemId, option))
			{
				withdrawFailures = 0;
			}
			else
			{
				++withdrawFailures;
				if (withdrawFailures >= WITHDRAW_FAIL_LIMIT)
				{
					stop("could not withdraw " + itemName(context, ingredient.itemId)
						+ " (keep the ores in the first bank tab)");
					return;
				}
			}

			setStatus("Withdrawing " + itemName(context, ingredient.itemId)
				+ " (" + have + "/" + targetCounts[i] + ")");
			cooldown(1);
			return;
		}

		bankStage = BankStage.LEAVE;
		bankTicks = 0;
		leaveAttempts = 0;
		setStatus("Leaving the bank with " + tripBars + " bars worth of ore");
	}

	/**
	 * Steel uses the bank's left-click options: iron ore is set to Withdraw-9
	 * and coal to Withdraw-All, so two ordinary clicks fill the inventory.
	 */
	private void withdrawSteel(Inventory inventory)
	{
		RoutineContext context = getContext();
		int iron = inventory.countItem(ItemID.IRON_ORE);
		int coal = inventory.countItem(ItemID.COAL);

		if (iron < targetCounts[0] || coal < targetCounts[1])
		{
			int itemId = iron < targetCounts[0] ? ItemID.IRON_ORE : ItemID.COAL;
			int have = itemId == ItemID.IRON_ORE ? iron : coal;
			int target = itemId == ItemID.IRON_ORE ? targetCounts[0] : targetCounts[1];
			GameInput input = context.getInput();
			if (!input.isBusy() && input.clickBankItem(itemId))
			{
				withdrawFailures = 0;
				cooldown(1);
			}
			else if (++withdrawFailures >= WITHDRAW_FAIL_LIMIT)
			{
				stop("could not withdraw " + itemName(context, itemId)
					+ " (set left-click Iron ore to Withdraw-9 and Coal to Withdraw-All)");
				return;
			}
			setStatus("Withdrawing " + itemName(context, itemId) + " (" + have + "/" + target + ")");
			return;
		}

		tripBars = Math.min(iron, coal / 2);
		if (tripBars <= 0)
		{
			stop("withdrew no complete steel bars");
			return;
		}

		bankStage = BankStage.LEAVE;
		bankTicks = 0;
		setStatus("Leaving the bank with " + tripBars + " bars worth of ore");
	}

	// -------------------------------------------------------------- furnace

	private void walkToFurnace()
	{
		WorldPoint location = getContext().getPlayerLocation();
		if (location == null)
		{
			return;
		}

		if (location.distanceTo2D(FURNACE_TILE) <= FURNACE_REACH)
		{
			setPhase(Phase.CLICK_FURNACE, "at the furnace");
			setStatus("Smelting " + bar.name());
			return;
		}

		if (actions.isMoving())
		{
			setStatus("Walking to the furnace");
			return;
		}

		if (actions.walkTo(FURNACE_TILE, true, 2))
		{
			cooldown(2);
		}
		else if (++walkAttempts >= WALK_ATTEMPTS)
		{
			stop("could not walk to the Edgeville furnace");
			return;
		}
		setStatus("Walking to the furnace");
	}

	private void clickFurnace()
	{
		if (skillMenuOpen())
		{
			enterMenu();
			return;
		}

		RoutineContext context = getContext();
		TileObject furnace = findFurnace();
		if (furnace == null)
		{
			if (actions.bankOpen())
			{
				closeBankForFurnace();
				return;
			}
			walkToFurnace();
			return;
		}

		if (actions.bankOpen() && context.getInput().objectCenter(furnace) == null)
		{
			closeBankForFurnace();
			return;
		}

		WorldPoint location = context.getPlayerLocation();
		if (!actions.bankOpen() && !actions.isMoving() && location != null
			&& location.distanceTo2D(FURNACE_TILE) > FURNACE_REACH
			&& !actions.clickableOnScreen(furnace))
		{
			walkToFurnace();
			return;
		}

		if (actions.clickObject(furnace, "furnace", new String[]{"smelt"}, "furnace"))
		{
			setPhase(Phase.WAIT_MENU, "clicked the furnace");
			menuWaitTicks = 0;
			cooldown(1);
		}
		else
		{
			setStatus("Clicking the furnace");
		}
	}

	private void closeBankForFurnace()
	{
		RoutineContext context = getContext();
		if (++leaveAttempts > 4)
		{
			context.getInput().closeInterface(InterfaceID.Bankmain.UNIVERSE);
			cooldown(2);
		}
		else if (actions.pressKey(KeyEvent.VK_ESCAPE))
		{
			cooldown(2);
		}
		setStatus("Closing the bank to click the furnace");
	}

	private TileObject findFurnace()
	{
		return getContext().getTargets().findObject(ObjectID.VARROCK_DIARY_FURNACE, FURNACE_SEARCH_DISTANCE)
			.orElseGet(() -> getContext().getTargets()
				.findObject("Furnace", "Smelt", FURNACE_SEARCH_DISTANCE).orElse(null));
	}

	private void menuStep()
	{
		RoutineContext context = getContext();
		boolean menuOpen = skillMenuOpen();

		if (phase == Phase.WAIT_MENU)
		{
			if (menuOpen)
			{
				enterMenu();
				return;
			}
			if (++menuWaitTicks >= MENU_WAIT_TICKS)
			{
				setPhase(Phase.CLICK_FURNACE, "the smelting menu did not open");
			}
			return;
		}

		if (!menuOpen)
		{
			if (barKeyPressed || allClicked)
			{
				startSmelting();
			}
			else if (++menuWaitTicks >= MENU_WAIT_TICKS)
			{
				setPhase(Phase.CLICK_FURNACE, "the smelting menu closed before a choice");
			}
			return;
		}

		if (!barKeyPressed)
		{
			int option = bar == Bar.STEEL ? 1 : findBarOption(context);
			if (option < 0)
			{
				stop("the smelting menu has no " + bar.name() + " option (Smithing level too low?)");
				return;
			}
			if (actions.pressKey(KeyEvent.VK_1 + option))
			{
				barKeyPressed = true;
				cooldown(1);
			}
			setStatus("Choosing " + bar.name());
			return;
		}

		if (!allClicked)
		{
			Widget all = context.getTargets().findWidget(InterfaceID.Skillmulti.ALL).orElse(null);
			if (all != null && !context.getInput().isBusy() && context.getInput().click(all))
			{
				allClicked = true;
				menuAttempts = 0;
				cooldown(2);
			}
			else if (++allAttempts >= MENU_ATTEMPTS)
			{
				stop("the smelting menu has no All button");
				return;
			}
			setStatus("Choosing All");
			return;
		}

		if (++menuAttempts >= MENU_ATTEMPTS)
		{
			stop("the smelting menu did not start");
			return;
		}
		barKeyPressed = false;
		allClicked = false;
		cooldown(1);
	}

	private void enterMenu()
	{
		setPhase(Phase.CHOOSE_BAR, "smelting menu open");
		barKeyPressed = false;
		allClicked = false;
		menuAttempts = 0;
		allAttempts = 0;
		levelUpAttempts = 0;
		setStatus("Choosing " + bar.name());
	}

	private void startSmelting()
	{
		barKeyPressed = false;
		allClicked = false;
		menuAttempts = 0;
		allAttempts = 0;
		restarts = 0;
		lastOreCount = totalOres(getContext().getInventory());
		interruptWatcher.reset(getContext().getClient());
		setPhase(Phase.SMELTING, "bar chosen, smelting");
		setStatus("Smelting " + bar.name());
	}

	private void smeltingStep()
	{
		RoutineContext context = getContext();
		if (skillMenuOpen())
		{
			enterMenu();
			return;
		}

		Inventory inventory = context.getInventory();
		int primaryOre = inventory.countItem(bar.ingredients[0].itemId);
		if (primaryOre == 0)
		{
			bankStage = BankStage.DEPOSIT;
			bankTicks = 0;
			depositAttempts = 0;
			setPhase(Phase.BANK, "ores used up, banking");
			setStatus("Walking back to the bank");
			return;
		}

		int total = totalOres(inventory);
		Player player = context.getPlayer();
		boolean animating = player != null && player.getAnimation() != -1;
		boolean usedOre = lastOreCount >= 0 && total < lastOreCount;
		lastOreCount = total;

		String interrupt = interruptWatcher.update(context.getClient(), animating, usedOre);
		if (interrupt == null)
		{
			setStatus("Smelting " + bar.name() + " (" + primaryOre + " " + itemName(context,
				bar.ingredients[0].itemId) + " left)");
			return;
		}

		if (++restarts > RESTART_LIMIT)
		{
			stop("smelting keeps stopping with ores left");
			return;
		}

		setPhase(Phase.CLICK_FURNACE, "restart: " + interrupt);
		setStatus("Restarting the furnace");
	}

	// -------------------------------------------------------------- helpers

	private boolean skillMenuOpen()
	{
		return getContext().getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent();
	}

	private int findBarOption(RoutineContext context)
	{
		Client client = context.getClient();
		String name = itemName(context, bar.barId).toLowerCase(Locale.ROOT);
		for (int i = 0; i < MAKE_OPTIONS.length; i++)
		{
			Widget option = client.getWidget(MAKE_OPTIONS[i]);
			if (option != null && !option.isHidden() && matchesBar(option, name))
			{
				return i;
			}
		}
		return -1;
	}

	private boolean matchesBar(Widget widget, String name)
	{
		if (widget.getItemId() == bar.barId
			|| GameInput.plainText(widget.getName()).toLowerCase(Locale.ROOT).contains(name)
			|| GameInput.plainText(widget.getText()).toLowerCase(Locale.ROOT).contains(name))
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
				if (child != null && (child.getItemId() == bar.barId
					|| GameInput.plainText(child.getText()).toLowerCase(Locale.ROOT).contains(name)))
				{
					return true;
				}
			}
		}
		return false;
	}

	private boolean hasSmelterItems(Inventory inventory)
	{
		for (Item item : inventory.getItems())
		{
			if (item == null || item.getId() == -1)
			{
				continue;
			}
			for (Bar candidate : Bar.values())
			{
				if (candidate.isSmelterItem(item.getId()))
				{
					return true;
				}
			}
		}
		return false;
	}

	private int totalOres(Inventory inventory)
	{
		int total = 0;
		for (Ingredient ingredient : bar.ingredients)
		{
			total += inventory.countItem(ingredient.itemId);
		}
		return total;
	}

	private boolean levelUpOpen()
	{
		Widget dialog = getContext().getClient().getWidget(InterfaceID.LevelupDisplay.UNIVERSE);
		return dialog != null && !dialog.isHidden();
	}

	private void dismissLevelUp()
	{
		RoutineContext context = getContext();
		GameInput input = context.getInput();
		if (!input.isBusy())
		{
			Widget cont = context.getClient().getWidget(InterfaceID.LevelupDisplay.CONTINUE);
			boolean clicked = ++levelUpAttempts <= LEVEL_UP_CLICK_ATTEMPTS
				&& cont != null && !cont.isHidden() && input.click(cont);
			if (!clicked)
			{
				input.pressKey(KeyEvent.VK_SPACE);
			}
			cooldown(1);
		}
		setStatus("Dismissing level up");
	}

	private static String itemName(RoutineContext context, int itemId)
	{
		ItemComposition composition = context.getClient().getItemDefinition(itemId);
		String name = composition == null ? null : composition.getName();
		return name == null ? String.valueOf(itemId) : name;
	}

	@Override
	public List<String> getOverlayLines()
	{
		return Arrays.asList(
			"Status: " + getStatus(),
			"Bar: " + bar.name() + " | State: " + (phase == null ? "-" : phase.name()),
			"Trip: " + tripBars + " bars | Ore left: " + (lastOreCount < 0 ? "-" : lastOreCount));
	}
}
