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
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rsbuddy.routines;

import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import net.runelite.api.Client;
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
 * Smiths bars into the selected item at the anvil across from the Varrock west
 * bank. Withdraws the bars, walks to the anvil, chooses the item and quantity,
 * waits for the batch, then banks the products and repeats.
 */
public final class WestVarrockSmitherRoutine extends TaskRoutine
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WestVarrockSmitherRoutine.class);

	private static final WorldPoint ANVIL_TILE = new WorldPoint(3188, 3426, 0);
	private static final int BANK_SEARCH_DISTANCE = 25;
	private static final int ANVIL_SEARCH_DISTANCE = 20;
	private static final int ANVIL_REACH = 4;
	private static final int BANK_LOAD_TICKS = 4;
	private static final int DEPOSIT_ATTEMPTS = 6;
	private static final int WITHDRAW_FAIL_LIMIT = 8;
	private static final int MENU_WAIT_TICKS = 12;
	private static final int MENU_ATTEMPTS = 4;
	private static final int RESTART_LIMIT = 6;
	private static final int LEVEL_UP_CLICK_ATTEMPTS = 3;

	/** Anvil make-menu option slots, in order (A is the first item). */
	private static final int[] MAKE_OPTIONS = {
		InterfaceID.Skillmulti.A, InterfaceID.Skillmulti.B, InterfaceID.Skillmulti.C,
		InterfaceID.Skillmulti.D, InterfaceID.Skillmulti.E, InterfaceID.Skillmulti.F,
		InterfaceID.Skillmulti.G, InterfaceID.Skillmulti.H, InterfaceID.Skillmulti.I,
		InterfaceID.Skillmulti.J, InterfaceID.Skillmulti.K, InterfaceID.Skillmulti.L,
		InterfaceID.Skillmulti.M, InterfaceID.Skillmulti.N, InterfaceID.Skillmulti.O,
		InterfaceID.Skillmulti.P, InterfaceID.Skillmulti.Q, InterfaceID.Skillmulti.R
	};

	/** Metal choices. Names match the plugin's WestVarrockSmithMetal. */
	enum Metal
	{
		BRONZE(ItemID.BRONZE_BAR),
		IRON(ItemID.IRON_BAR),
		STEEL(ItemID.STEEL_BAR),
		MITHRIL(ItemID.MITHRIL_BAR),
		ADAMANT(ItemID.ADAMANTITE_BAR),
		RUNE(ItemID.RUNITE_BAR);

		private final int barId;

		Metal(int barId)
		{
			this.barId = barId;
		}
	}

	/** Item choices. Product ids follow the metal order above. */
	enum Item
	{
		DAGGER("Dagger", 1, 1205, 1203, 1207, 1209, 1211, 1213),
		SWORD("Sword", 1, 1277, 1279, 1281, 1285, 1287, 1289),
		LONGSWORD("Longsword", 2, 1291, 1293, 1295, 1299, 1301, 1303),
		TWO_HANDED_SWORD("2h sword", 3, 1307, 1309, 1311, 1315, 1317, 1319),
		SCIMITAR("Scimitar", 2, 1321, 1323, 1325, 1329, 1331, 1333),
		MACE("Mace", 1, 1422, 1420, 1424, 1428, 1430, 1432),
		WARHAMMER("Warhammer", 3, 1337, 1335, 1339, 1343, 1345, 1347),
		AXE("Axe", 1, 1351, 1349, 1353, 1355, 1357, 1359),
		BATTLEAXE("Battleaxe", 3, 1375, 1363, 1365, 1369, 1371, 1373),
		CLAWS("Claws", 2, 3095, 3096, 3097, 3099, 3100, 3101),
		CHAINBODY("Chainbody", 3, 1103, 1101, 1105, 1109, 1111, 1113),
		PLATELEGS("Platelegs", 3, 1075, 1067, 1069, 1071, 1073, 1079),
		PLATESKIRT("Plateskirt", 3, 1087, 1081, 1083, 1085, 1091, 1093),
		PLATEBODY("Platebody", 5, 1117, 1115, 1119, 1121, 1123, 1127),
		MED_HELM("Med helm", 1, 1139, 1137, 1141, 1143, 1145, 1147),
		FULL_HELM("Full helm", 2, 1155, 1153, 1157, 1159, 1161, 1163),
		SQ_SHIELD("Sq shield", 2, 1173, 1175, 1177, 1181, 1183, 1185),
		KITESHIELD("Kiteshield", 3, 1189, 1191, 1193, 1197, 1199, 1201),
		KEEL_PARTS("Keel parts", 5, 31999, 32002, 32005, 32008, 32011, 32014),
		LARGE_KEEL_PARTS("Large keel parts", 5, true, 32020, 32023, 32026, 32029, 32032, 32035);

		private final String label;
		private final int bars;
		private final boolean keelPartsInput;
		private final int[] productIds;

		Item(String label, int bars, int... productIds)
		{
			this(label, bars, false, productIds);
		}

		Item(String label, int bars, boolean keelPartsInput, int... productIds)
		{
			this.label = label;
			this.bars = bars;
			this.keelPartsInput = keelPartsInput;
			this.productIds = productIds;
		}

		int productId(Metal metal)
		{
			return productIds[metal.ordinal()];
		}

		int ingredientId(Metal metal)
		{
			return keelPartsInput ? KEEL_PARTS.productId(metal) : metal.barId;
		}

		boolean usesKeelParts()
		{
			return keelPartsInput;
		}
	}

	/** Quantity choices. Names match the plugin's WestVarrockSmithAmount. */
	enum Amount
	{
		ONE("1", 1, InterfaceID.Skillmulti._1),
		FIVE("5", 5, InterfaceID.Skillmulti._5),
		TEN("10", 10, InterfaceID.Skillmulti._10),
		ALL("All", -1, InterfaceID.Skillmulti.ALL);

		private final String label;
		private final int value;
		private final int widgetId;

		Amount(String label, int value, int widgetId)
		{
			this.label = label;
			this.value = value;
			this.widgetId = widgetId;
		}

		boolean isAll()
		{
			return value < 0;
		}
	}

	private enum Phase
	{
		BANK,
		ANVIL,
		WAIT_MENU,
		CHOOSE_ITEM,
		SMITHING
	}

	private enum BankStage
	{
		DEPOSIT,
		WITHDRAW,
		LEAVE
	}

	private final Metal metal;
	private final Item item;
	private final Amount amount;
	private final SkillInterruptWatcher interruptWatcher = new SkillInterruptWatcher(Skill.SMITHING);

	private Phase phase;
	private BankStage bankStage;
	private int itemsToMake;
	private int inputToWithdraw;

	private int bankTicks;
	private int depositAttempts;
	private int withdrawFailures;
	private int leaveAttempts;
	private int menuWaitTicks;
	private int menuAttempts;
	private int quantityAttempts;
	private int levelUpAttempts;
	private int restarts;

	private boolean itemClicked;
	private boolean quantityClicked;
	private int lastInputCount = -1;

	public WestVarrockSmitherRoutine()
	{
		this(Metal.STEEL.name(), Item.KEEL_PARTS.name(), Amount.ALL.name());
	}

	/**
	 * @param metalName a {@link Metal} name
	 * @param itemName an {@link Item} name
	 * @param amountName an {@link Amount} name
	 */
	public WestVarrockSmitherRoutine(String metalName, String itemName, String amountName)
	{
		super("West Varrock Smither",
			"Smiths bars at the anvil across from the Varrock west bank, banking the products.");
		this.metal = parseMetal(metalName);
		this.item = parseItem(itemName);
		this.amount = parseAmount(amountName);
	}

	private static Metal parseMetal(String name)
	{
		try
		{
			return name == null ? Metal.STEEL : Metal.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return Metal.STEEL;
		}
	}

	private static Item parseItem(String name)
	{
		try
		{
			return name == null ? Item.KEEL_PARTS : Item.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return Item.KEEL_PARTS;
		}
	}

	private static Amount parseAmount(String name)
	{
		try
		{
			return name == null ? Amount.ALL : Amount.valueOf(name);
		}
		catch (IllegalArgumentException ex)
		{
			return Amount.ALL;
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
		itemsToMake = 0;
		inputToWithdraw = 0;
		bankTicks = 0;
		depositAttempts = 0;
		withdrawFailures = 0;
		leaveAttempts = 0;
		menuWaitTicks = 0;
		menuAttempts = 0;
		quantityAttempts = 0;
		levelUpAttempts = 0;
		restarts = 0;
		itemClicked = false;
		quantityClicked = false;
		lastInputCount = -1;
		setPhase(Phase.BANK, "started: " + metal.name() + " " + item.name() + " " + amount.name());
		setStatus("Ready: " + metal.name() + " " + item.name());

		task("dismiss level up", this::levelUpOpen, this::dismissLevelUp);
		task("bank", () -> phase == Phase.BANK, this::bankStep);
		task("anvil", () -> phase == Phase.ANVIL, this::goToAnvil);
		task("anvil menu", () -> phase == Phase.WAIT_MENU || phase == Phase.CHOOSE_ITEM, this::menuStep);
		task("smithing", () -> phase == Phase.SMITHING, this::smithingStep);
	}

	private void setPhase(Phase next, String reason)
	{
		if (next != phase)
		{
			log.info("[WestVarrockSmither] {} -> {}: {}", phase, next, reason);
			phase = next;
			menuWaitTicks = 0;
			menuAttempts = 0;
			quantityAttempts = 0;
			itemClicked = false;
			quantityClicked = false;
			leaveAttempts = 0;
		}
	}

	private void stop(String reason)
	{
		log.info("[WestVarrockSmither] halting: {}", reason);
		super.halt("Stopped: " + reason);
	}

	// ----------------------------------------------------------------- bank

	private void bankStep()
	{
		RoutineContext context = getContext();
		Inventory inventory = context.getInventory();

		if (bankStage == BankStage.LEAVE)
		{
			if (!actions.bankOpen())
			{
				setPhase(Phase.ANVIL, "bank closed, going to the anvil");
				setStatus("Walking to the anvil");
				return;
			}

			if (++leaveAttempts > 4)
			{
				context.getInput().closeInterface(InterfaceID.Bankmain.UNIVERSE);
				cooldown(2);
			}
			else if (actions.pressKey(KeyEvent.VK_ESCAPE))
			{
				cooldown(2);
			}
			setStatus("Closing the bank");
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
				withdraw(context, inventory);
				break;
			default:
				break;
		}
	}

	private void deposit(RoutineContext context, Inventory inventory)
	{
		// A locked hammer keeps slot 1 filled, so the inventory is never completely
		// empty. Treat the deposit as done once no inputs or products are left.
		if (hasSmitherItems(inventory))
		{
			if (depositAttempts >= DEPOSIT_ATTEMPTS)
			{
				stop("Deposit inventory did not clear the inputs or products (are they in a locked slot?)");
				return;
			}

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

		if (!planTrip(context, inventory))
		{
			return;
		}

		bankStage = BankStage.WITHDRAW;
		bankTicks = 0;
		depositAttempts = 0;
		withdrawFailures = 0;
		setStatus("Withdrawing " + ingredientName());
	}

	private boolean planTrip(RoutineContext context, Inventory inventory)
	{
		ItemContainer bank = context.getClient().getItemContainer(InventoryID.BANK);
		if (bank == null)
		{
			setStatus("Waiting for the bank items");
			return false;
		}

		int available = bank.count(item.ingredientId(metal));
		int maxBySlots = inventory.getEmptySlots() / item.bars;
		int requested = amount.isAll() ? maxBySlots : Math.min(amount.value, maxBySlots);
		int make = Math.min(requested, available / item.bars);

		if (make <= 0)
		{
			if (bankTicks < BANK_LOAD_TICKS)
			{
				setStatus("Waiting for the bank items");
				return false;
			}
			if (available < item.bars)
			{
				stop("bank has no " + ingredientName() + " for a "
					+ item.label.toLowerCase(Locale.ROOT));
			}
			else
			{
				stop("not enough free inventory slots for a " + item.label.toLowerCase(Locale.ROOT));
			}
			return false;
		}

		if (item.usesKeelParts() && amount.isAll())
		{
			// Withdraw a full inventory of small keel parts; the anvil's All
			// option makes as many large parts as those parts allow.
			inputToWithdraw = Math.min(available, inventory.getEmptySlots());
			itemsToMake = inputToWithdraw / item.bars;
		}
		else
		{
			itemsToMake = make;
			inputToWithdraw = make * item.bars;
		}
		return true;
	}

	private void withdraw(RoutineContext context, Inventory inventory)
	{
		int ingredientId = item.ingredientId(metal);
		int held = inventory.countItem(ingredientId);
		if (held < inputToWithdraw)
		{
			int missing = inputToWithdraw - held;
			int amountToWithdraw = missing >= 10 ? 10 : missing >= 5 ? 5 : 1;
			GameInput input = context.getInput();
			if (input.withdrawBankItem(ingredientId, "Withdraw-" + amountToWithdraw))
			{
				withdrawFailures = 0;
			}
			else if (++withdrawFailures >= WITHDRAW_FAIL_LIMIT)
			{
				stop("could not withdraw " + ingredientName() + " (keep them in the first bank tab)");
				return;
			}

			setStatus("Withdrawing " + ingredientName() + " (" + held + "/" + inputToWithdraw + ")");
			cooldown(1);
			return;
		}

		bankStage = BankStage.LEAVE;
		bankTicks = 0;
		leaveAttempts = 0;
		setStatus("Leaving the bank with enough input for " + itemsToMake + " "
			+ item.label.toLowerCase(Locale.ROOT));
	}

	// ---------------------------------------------------------------- anvil

	private void goToAnvil()
	{
		if (skillMenuOpen())
		{
			enterMenu();
			return;
		}

		if (actions.goAndClick(this::findAnvil, ANVIL_TILE, "anvil",
			new String[]{"smith"}, "anvil", ANVIL_REACH))
		{
			setPhase(Phase.WAIT_MENU, "clicked the anvil");
			menuWaitTicks = 0;
			cooldown(1);
		}
		else
		{
			setStatus("Walking to the anvil");
		}
	}

	private TileObject findAnvil()
	{
		return getContext().getTargets().findObject(ObjectID.ANVIL, ANVIL_SEARCH_DISTANCE)
			.orElseGet(() -> getContext().getTargets()
				.findObject("Anvil", "Smith", ANVIL_SEARCH_DISTANCE).orElse(null));
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
				setPhase(Phase.ANVIL, "the anvil menu did not open");
			}
			return;
		}

		if (!menuOpen)
		{
			if (itemClicked)
			{
				startSmithing();
			}
			else if (++menuWaitTicks >= MENU_WAIT_TICKS)
			{
				setPhase(Phase.ANVIL, "the anvil menu closed before an item was chosen");
			}
			return;
		}

		if (!itemClicked)
		{
			Widget option = findItemOption(context);
			if (option == null)
			{
				stop("the anvil menu has no " + productName(context).toLowerCase(Locale.ROOT)
					+ " option (Smithing level too low?)");
				return;
			}

			GameInput input = context.getInput();
			if (!input.isBusy() && input.click(option))
			{
				itemClicked = true;
				cooldown(1);
			}
			setStatus("Choosing " + productName(context));
			return;
		}

		Widget quantity = context.getTargets().findWidget(amount.widgetId).orElse(null);
		GameInput input = context.getInput();
		if (!quantityClicked)
		{
			if (quantity == null || quantity.isHidden())
			{
				if (++quantityAttempts >= MENU_ATTEMPTS)
				{
					stop("the anvil menu has no " + amount.label + " quantity button");
				}
				setStatus("Choosing " + amount.label);
				return;
			}

			if (!input.isBusy() && input.click(quantity))
			{
				quantityClicked = true;
				cooldown(1);
			}
			setStatus("Choosing " + amount.label);
			return;
		}

		if (!input.isBusy())
		{
			if (quantity != null && !quantity.isHidden() && input.click(quantity))
			{
				cooldown(1);
			}
			if (++menuAttempts >= MENU_ATTEMPTS)
			{
				stop("the anvil menu did not start");
			}
		}
	}

	private void enterMenu()
	{
		setPhase(Phase.CHOOSE_ITEM, "anvil menu open");
		menuWaitTicks = 0;
		menuAttempts = 0;
		quantityAttempts = 0;
		itemClicked = false;
		quantityClicked = false;
		levelUpAttempts = 0;
		setStatus("Choosing " + productName(getContext()));
	}

	private void startSmithing()
	{
		itemClicked = false;
		quantityClicked = false;
		menuAttempts = 0;
		quantityAttempts = 0;
		lastInputCount = getContext().getInventory().countItem(item.ingredientId(metal));
		interruptWatcher.reset(getContext().getClient());
		setPhase(Phase.SMITHING, "item chosen, smithing");
		setStatus("Smithing " + productName(getContext()));
	}

	private void smithingStep()
	{
		RoutineContext context = getContext();
		if (skillMenuOpen())
		{
			enterMenu();
			return;
		}

		Inventory inventory = context.getInventory();
		int held = inventory.countItem(item.ingredientId(metal));
		if (held < item.bars)
		{
			bankStage = BankStage.DEPOSIT;
			bankTicks = 0;
			depositAttempts = 0;
			restarts = 0;
			setPhase(Phase.BANK, "input used up, banking");
			setStatus("Walking back to the bank");
			return;
		}

		Player player = context.getPlayer();
		boolean animating = player != null && player.getAnimation() != -1;
		boolean usedInput = lastInputCount >= 0 && held < lastInputCount;
		lastInputCount = held;
		if (usedInput)
		{
			restarts = 0;
		}

		String interrupt = interruptWatcher.update(context.getClient(), animating, usedInput);
		if (interrupt == null)
		{
			setStatus("Smithing " + productName(context) + " (" + held + " " + ingredientName() + " left)");
			return;
		}

		if (++restarts > RESTART_LIMIT)
		{
			stop("smithing keeps stopping with input left");
			return;
		}

		setPhase(Phase.ANVIL, "restart: " + interrupt);
		setStatus("Restarting the anvil");
	}

	// -------------------------------------------------------------- helpers

	private Widget findItemOption(RoutineContext context)
	{
		Client client = context.getClient();
		int productId = item.productId(metal);
		String name = productName(context).toLowerCase(Locale.ROOT);
		for (int widgetId : MAKE_OPTIONS)
		{
			Widget option = client.getWidget(widgetId);
			if (option != null && !option.isHidden() && matchesItem(option, productId, name))
			{
				return option;
			}
		}
		return null;
	}

	private boolean matchesItem(Widget widget, int productId, String name)
	{
		if (widget.getItemId() == productId
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
				if (child != null && (child.getItemId() == productId
					|| GameInput.plainText(child.getText()).toLowerCase(Locale.ROOT).contains(name)))
				{
					return true;
				}
			}
		}
		return false;
	}

	private boolean skillMenuOpen()
	{
		return getContext().getTargets().findWidget(InterfaceID.Skillmulti.UNIVERSE).isPresent();
	}

	private boolean hasSmitherItems(Inventory inventory)
	{
		int productId = item.productId(metal);
		int ingredientId = item.ingredientId(metal);
		for (net.runelite.api.Item inventoryItem : inventory.getItems())
		{
			if (inventoryItem != null
				&& (inventoryItem.getId() == ingredientId || inventoryItem.getId() == productId))
			{
				return true;
			}
		}
		return false;
	}

	private String ingredientName()
	{
		return item.usesKeelParts() ? "keel parts"
			: metal.name().toLowerCase(Locale.ROOT) + " bars";
	}

	private String productName(RoutineContext context)
	{
		int productId = item.productId(metal);
		ItemComposition composition = context.getClient().getItemDefinition(productId);
		String name = composition == null ? null : composition.getName();
		return name != null ? name : metal.name() + " " + item.label;
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

	@Override
	public List<String> getOverlayLines()
	{
		return Arrays.asList(
			"Status: " + getStatus(),
			"Metal: " + metal.name() + " | Item: " + item.label + " | Amount: " + amount.name(),
			"State: " + (phase == null ? "-" : phase.name()) + " | Input left: "
				+ (lastInputCount < 0 ? "-" : lastInputCount));
	}
}
