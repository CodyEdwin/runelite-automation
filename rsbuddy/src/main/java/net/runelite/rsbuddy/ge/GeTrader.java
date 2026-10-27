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
package net.runelite.rsbuddy.ge;

import java.awt.Point;
import java.awt.Rectangle;
import java.util.Locale;
import net.runelite.api.Client;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;

/**
 * Drives the Grand Exchange interfaces for one buy-then-sell cycle.
 * <p>
 * The trader is a step machine: every game tick it advances at most one phase,
 * then waits a short moment to let the interface catch up. Each phase verifies
 * its own result through the GE varps and the setup widgets before moving on,
 * so a misclick or an unexpected screen aborts the trade instead of confirming
 * the wrong offer.
 * <p>
 * Interface layout, confirmed from a widget dump:
 * <ul>
 *   <li>{@code 465:7..14} offer slots; child 3 = create buy, child 4 = create sell,
 *       child 16 = status text, child 19 = item name.</li>
 *   <li>{@code 465:26} setup; child 0 = choose item, child 7 = enter quantity,
 *       child 12 = enter price, child 34 = quantity value, child 41 = price value.</li>
 *   <li>{@code 465:30} confirm button.</li>
 *   <li>{@code 465:6} collect-all; {@code 465:4} back.</li>
 *   <li>{@code 162:53} chatbox search results; rows expose a "Select" action.</li>
 * </ul>
 */
public final class GeTrader
{
	public interface Listener
	{
		void onLog(String message);

		void onCycleComplete(FlipCandidate candidate);

		void onAborted(String reason);
	}

	private static final int OFFER_SLOTS = 8;
	private static final int SLOT_CREATE_BUY = 3;
	private static final int SLOT_CREATE_SELL = 4;
	private static final int SLOT_STATUS = 16;

	private static final int SETUP_ITEM = 0;
	private static final int SETUP_QUANTITY = 7;
	private static final int SETUP_PRICE = 12;
	private static final int SETUP_TITLE = 20;
	private static final int SETUP_ICON = 23;
	private static final int SETUP_ITEM_NAME = 27;
	private static final int SETUP_QUANTITY_VALUE = 34;
	private static final int SETUP_PRICE_VALUE = 41;

	private static final long PHASE_TIMEOUT_MS = 20_000L;
	private static final long OFFER_TIMEOUT_MS = 180_000L;
	private static final long TRADE_TIMEOUT_MS = 600_000L;

	private enum Phase
	{
		IDLE,
		OPEN_BUY_SLOT,
		CHOOSE_ITEM,
		TYPE_SEARCH,
		PICK_RESULT,
		VERIFY_ITEM,
		SET_QUANTITY,
		TYPE_QUANTITY,
		VERIFY_QUANTITY,
		SET_PRICE,
		TYPE_PRICE,
		VERIFY_PRICE,
		CONFIRM_BUY,
		WAIT_BUY,
		COLLECT_ITEMS,
		OPEN_SELL_SLOT,
		HOVER_SELL_ITEM,
		SELECT_SELL_ITEM,
		VERIFY_SELL_ITEM,
		SET_SELL_QUANTITY,
		TYPE_SELL_QUANTITY,
		VERIFY_SELL_QUANTITY,
		SET_SELL_PRICE,
		TYPE_SELL_PRICE,
		VERIFY_SELL_PRICE,
		CONFIRM_SELL,
		WAIT_SELL,
		COLLECT_COINS,
		FINISH
	}

	private final Client client;
	private final GameInput input;
	private final Inventory inventory;
	private final Listener listener;

	private Phase phase = Phase.IDLE;
	private FlipCandidate trade;
	private int slotIndex = -1;
	private int attempts;
	private long nextActionAt;
	private long phaseStartedAt;
	private long tradeStartedAt;
	private int quantity;
	private int sellPrice;
	private boolean offerSeen;
	private boolean lastActionFailed;
	private int selectAttempts;

	public GeTrader(Client client, GameInput input, Inventory inventory, Listener listener)
	{
		this.client = client;
		this.input = input;
		this.inventory = inventory;
		this.listener = listener;
	}

	public boolean isBusy()
	{
		return phase != Phase.IDLE;
	}

	public String getStatus()
	{
		if (phase == Phase.IDLE)
		{
			return "Idle";
		}

		return phase.name().toLowerCase(Locale.ROOT).replace('_', ' ')
			+ (trade == null ? "" : " (" + trade.getName() + ")");
	}

	/**
	 * @return true when the trade could start
	 */
	public boolean start(FlipCandidate candidate, int quantity)
	{
		if (phase != Phase.IDLE || candidate == null || quantity < 1)
		{
			return false;
		}

		if (client.getWidget(InterfaceID.GeOffers.UNIVERSE) == null)
		{
			listener.onAborted("Grand Exchange interface is not open");
			return false;
		}

		if (inventory.getEmptySlots() < quantity)
		{
			listener.onAborted("Not enough inventory space for " + quantity + " item(s)");
			return false;
		}

		trade = candidate;
		this.quantity = quantity;
		this.sellPrice = candidate.getSellPrice();
		slotIndex = -1;
		attempts = 0;
		offerSeen = false;
		lastActionFailed = false;
		selectAttempts = 0;
		tradeStartedAt = System.currentTimeMillis();
		advance(Phase.OPEN_BUY_SLOT, 0L);
		return true;
	}

	public void stop()
	{
		Widget back = client.getWidget(InterfaceID.GeOffers.BACK);
		if (back != null && !back.isHidden())
		{
			input.click(back);
		}

		phase = Phase.IDLE;
		trade = null;
	}

	public void tick()
	{
		if (phase == Phase.IDLE || System.currentTimeMillis() < nextActionAt)
		{
			return;
		}

		long now = System.currentTimeMillis();

		// Retry loops move between phases, which restarts the phase clock, so
		// the whole trade also needs its own deadline.
		if (now - tradeStartedAt > TRADE_TIMEOUT_MS)
		{
			abort("The offer did not finish within 10 minutes");
			return;
		}

		long timeout = isOfferPhase() ? OFFER_TIMEOUT_MS : PHASE_TIMEOUT_MS;
		if (now - phaseStartedAt > timeout)
		{
			abort("Timed out while " + getStatus());
			return;
		}

		try
		{
			step();
		}
		catch (RuntimeException ex)
		{
			abort("Error in " + phase + ": " + ex.getMessage());
		}
	}

	private void step()
	{
		switch (phase)
		{
			case OPEN_BUY_SLOT:
				openSlot(SLOT_CREATE_BUY, Phase.CHOOSE_ITEM);
				break;
			case CHOOSE_ITEM:
				clickSetup(SETUP_ITEM, Phase.TYPE_SEARCH, 400L);
				break;
			case TYPE_SEARCH:
				typeSearch();
				break;
			case PICK_RESULT:
				pickResult();
				break;
			case VERIFY_ITEM:
				verifySelectedItem(Phase.SET_QUANTITY, Phase.PICK_RESULT);
				break;
			case SET_QUANTITY:
				clickSetup(SETUP_QUANTITY, Phase.TYPE_QUANTITY, 500L);
				break;
			case TYPE_QUANTITY:
				typeNumber(quantity, Phase.VERIFY_QUANTITY, Phase.SET_QUANTITY, "quantity", "how many");
				break;
			case VERIFY_QUANTITY:
				verifyNumber(SETUP_QUANTITY_VALUE, quantity, Phase.SET_PRICE, Phase.SET_QUANTITY);
				break;
			case SET_PRICE:
				clickSetup(SETUP_PRICE, Phase.TYPE_PRICE, 500L);
				break;
			case TYPE_PRICE:
				typeNumber(trade.getBuyPrice(), Phase.VERIFY_PRICE, Phase.SET_PRICE, "price");
				break;
			case VERIFY_PRICE:
				verifyNumber(SETUP_PRICE_VALUE, trade.getBuyPrice(), Phase.CONFIRM_BUY, Phase.SET_PRICE);
				break;
			case CONFIRM_BUY:
				confirm(Phase.WAIT_BUY);
				break;
			case WAIT_BUY:
				waitForOffer(GrandExchangeOfferState.BOUGHT, Phase.COLLECT_ITEMS);
				break;
			case COLLECT_ITEMS:
				collectItems();
				break;
			case OPEN_SELL_SLOT:
				openSlot(SLOT_CREATE_SELL, Phase.HOVER_SELL_ITEM);
				break;
			case HOVER_SELL_ITEM:
				hoverSellItem();
				break;
			case SELECT_SELL_ITEM:
				selectSellItem();
				break;
			case VERIFY_SELL_ITEM:
				verifySelectedItem(Phase.SET_SELL_QUANTITY, Phase.HOVER_SELL_ITEM);
				break;
			case SET_SELL_QUANTITY:
				clickSetup(SETUP_QUANTITY, Phase.TYPE_SELL_QUANTITY, 500L);
				break;
			case TYPE_SELL_QUANTITY:
				typeNumber(quantity, Phase.VERIFY_SELL_QUANTITY, Phase.SET_SELL_QUANTITY, "quantity", "how many");
				break;
			case VERIFY_SELL_QUANTITY:
				verifyNumber(SETUP_QUANTITY_VALUE, quantity, Phase.SET_SELL_PRICE, Phase.SET_SELL_QUANTITY);
				break;
			case SET_SELL_PRICE:
				clickSetup(SETUP_PRICE, Phase.TYPE_SELL_PRICE, 500L);
				break;
			case TYPE_SELL_PRICE:
				typeNumber(sellPrice, Phase.VERIFY_SELL_PRICE, Phase.SET_SELL_PRICE, "price");
				break;
			case VERIFY_SELL_PRICE:
				verifyNumber(SETUP_PRICE_VALUE, sellPrice, Phase.CONFIRM_SELL, Phase.SET_SELL_PRICE);
				break;
			case CONFIRM_SELL:
				confirm(Phase.WAIT_SELL);
				break;
			case WAIT_SELL:
				waitForOffer(GrandExchangeOfferState.SOLD, Phase.COLLECT_COINS);
				break;
			case COLLECT_COINS:
				collectCoins();
				break;
			case FINISH:
				finishCycle();
				break;
			default:
				phase = Phase.IDLE;
				break;
		}
	}

	private boolean isOfferPhase()
	{
		return phase == Phase.WAIT_BUY || phase == Phase.WAIT_SELL;
	}

	private void openSlot(int childIndex, Phase next)
	{
		if (slotIndex < 0)
		{
			slotIndex = findEmptySlot();
			if (slotIndex < 0)
			{
				abort("No empty Grand Exchange slot available");
				return;
			}
		}

		Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + slotIndex);
		Widget button = slot == null ? null : slot.getChild(childIndex);

		if (input.interactStripped(button, childIndex == SLOT_CREATE_BUY
			? "Create Buy offer" : "Create Sell offer"))
		{
			advance(next, 600L);
			return;
		}

		if (attempts++ > 5)
		{
			abort("Could not click the offer slot");
			return;
		}

		advance(phase, 400L);
	}

	private void clickSetup(int childIndex, Phase next, long delay)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget child = setup == null ? null : setup.getChild(childIndex);

		if (child != null && !child.isHidden())
		{
			input.click(child);
			advance(next, delay);
			return;
		}

		if (attempts++ > 5)
		{
			abort("Setup screen did not open");
			return;
		}

		advance(phase, 400L);
	}

	private void typeSearch()
	{
		String name = trade.getName().toLowerCase(Locale.ROOT);
		if (name.length() > 28)
		{
			name = name.substring(0, 28);
		}

		input.typeText(name);
		advance(Phase.PICK_RESULT, 1_100L);
	}

	private void pickResult()
	{
		Widget results = client.getWidget(InterfaceID.Chatbox.MES_LAYER_SCROLLCONTENTS);
		Widget row = findResultRow(results);

		if (row != null)
		{
			input.click(row);
			advance(Phase.VERIFY_ITEM, 700L);
			return;
		}

		if (attempts++ > 6)
		{
			abort("Item \"" + trade.getName() + "\" was not found in the search results");
			return;
		}

		advance(Phase.PICK_RESULT, 500L);
	}

	private Widget findResultRow(Widget results)
	{
		if (results == null || results.getDynamicChildren() == null)
		{
			return null;
		}

		String wanted = normalise(trade.getName());

		for (Widget child : results.getDynamicChildren())
		{
			if (child == null || child.isHidden())
			{
				continue;
			}

			String[] actions = child.getActions();
			if (actions == null || findAction(actions, "Select") < 0)
			{
				continue;
			}

			String name = child.getName();
			if (name != null && normalise(name).equals(wanted))
			{
				return child;
			}
		}

		return null;
	}

	/**
	 * Confirms the setup screen shows the item we intend to trade. Child 23 is
	 * the item icon and child 27 the item name in both buy and sell mode; with
	 * nothing chosen the icon is a placeholder and the name reads
	 * "Choose an item...".
	 *
	 * <p>Buy mode fills varp 1151 with the chosen id, but sell mode does not,
	 * so the widgets are the common check.
	 */
	private void verifySelectedItem(Phase next, Phase retry)
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget icon = setup == null ? null : setup.getChild(SETUP_ICON);
		Widget name = setup == null ? null : setup.getChild(SETUP_ITEM_NAME);

		int iconId = icon == null ? -1 : icon.getItemId();
		String shown = name == null ? "" : normalise(name.getText());

		boolean matches = iconId == trade.getItemId()
			|| (iconId <= 0 && !shown.isEmpty() && shown.equals(normalise(trade.getName())));

		if (matches)
		{
			advance(next, 200L);
			return;
		}

		if (attempts++ > 5)
		{
			abort("The setup screen shows " + (shown.isEmpty() ? "no item" : "\"" + shown + "\"")
				+ " instead of " + trade.getName());
			return;
		}

		advance(retry, 500L);
	}

	/**
	 * Types a number into the chatbox, but only after confirming that the
	 * expected prompt (not normal chat) is the thing on screen. This is what
	 * stops a stray click from typing a quantity into public chat.
	 */
	private void typeNumber(int value, Phase next, Phase retry, String... promptWords)
	{
		String prompt = chatPrompt();
		boolean matches = false;

		if (prompt != null)
		{
			String lower = prompt.toLowerCase(Locale.ROOT);
			for (String word : promptWords)
			{
				if (lower.contains(word))
				{
					matches = true;
					break;
				}
			}
		}

		if (!matches)
		{
			attempts++;
			if (attempts > 4)
			{
				abort("The Grand Exchange input prompt did not open");
				return;
			}

			advance(retry, 400L);
			return;
		}

		input.typeText(Integer.toString(value));
		input.pressEnter();
		advance(next, 800L);
	}

	private void verifyNumber(int childIndex, int expected, Phase next, Phase retry)
	{
		int actual = readNumber(client.getWidget(InterfaceID.GeOffers.SETUP), childIndex);
		if (actual == expected)
		{
			advance(next, 200L);
			return;
		}

		if (attempts++ > 4)
		{
			abort("The Grand Exchange did not accept " + expected + " (showed " + actual + ")");
			return;
		}

		advance(retry, 400L);
	}

	private void confirm(Phase next)
	{
		boolean buy = next == Phase.WAIT_BUY;

		if (buy && (trade.getBuyPrice() <= 0 || quantity <= 0))
		{
			abort("Refusing to confirm a buy with no price or quantity");
			return;
		}

		if (!buy && sellPrice < trade.getSellFloor())
		{
			abort("Refusing to list below the break-even floor");
			return;
		}

		Widget button = client.getWidget(InterfaceID.GeOffers.SETUP_CONFIRM);
		if (button == null || button.isHidden())
		{
			Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
			button = setup == null ? null : setup.getChild(58);
		}

		if (button == null)
		{
			if (attempts++ > 4)
			{
				abort("Confirm button not found");
				return;
			}
			advance(phase, 400L);
			return;
		}

		input.click(button);
		advance(next, 1_200L);
	}

	private void waitForOffer(GrandExchangeOfferState wanted, Phase next)
	{
		GrandExchangeOffer offer = offer();
		if (offer == null)
		{
			advance(phase, 600L);
			return;
		}

		GrandExchangeOfferState state = offer.getState();
		if (state == wanted)
		{
			advance(next, 700L);
			return;
		}

		if (state == GrandExchangeOfferState.BUYING || state == GrandExchangeOfferState.SELLING)
		{
			offerSeen = true;
			advance(phase, 600L);
			return;
		}

		if (!offerSeen && state == GrandExchangeOfferState.EMPTY
			&& System.currentTimeMillis() - phaseStartedAt > 6_000L)
		{
			abort("The Grand Exchange did not accept the offer (missing gp or items?)");
			return;
		}

		if (offerSeen && state == GrandExchangeOfferState.EMPTY)
		{
			// The offer filled and was collected elsewhere; move on.
			advance(next, 500L);
			return;
		}

		advance(phase, 600L);
	}

	private void collectItems()
	{
		int held = inventory.countItem(trade.getItemId());
		if (held >= quantity)
		{
			advance(Phase.OPEN_SELL_SLOT, 300L);
			offerSeen = false;
			slotIndex = -1;
			attempts = 0;
			return;
		}

		if (collectAll())
		{
			advance(Phase.COLLECT_ITEMS, 900L);
			return;
		}

		if (attempts++ > 6)
		{
			abort("Bought " + trade.getName() + " but could not collect it");
			return;
		}

		advance(Phase.COLLECT_ITEMS, 600L);
	}

	private void hoverSellItem()
	{
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		Widget title = setup == null ? null : setup.getChild(SETUP_TITLE);
		Widget name = setup == null ? null : setup.getChild(SETUP_ITEM_NAME);

		if (setup == null || title == null
			|| !normalise(title.getText()).contains("sell"))
		{
			if (attempts++ > 6)
			{
				abort("The sell offer screen did not open");
				return;
			}

			advance(phase, 500L);
			return;
		}

		// A retry after a failed selection may find the item already chosen.
		if (name != null && normalise(name.getText()).equals(normalise(trade.getName())))
		{
			advance(Phase.VERIFY_SELL_ITEM, 100L);
			return;
		}

		int slot = inventory.findSlot(trade.getItemId());
		if (slot < 0)
		{
			abort("No " + trade.getName() + " in the inventory to sell");
			return;
		}

		Widget itemWidget = inventory.getSlotWidget(slot);
		Rectangle bounds = itemWidget == null ? null : itemWidget.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			if (attempts++ > 5)
			{
				abort("Inventory slot for " + trade.getName() + " is not visible");
				return;
			}

			advance(phase, 500L);
			return;
		}

		Point center = new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
		if (!input.moveTo(center))
		{
			abort("Could not move the mouse onto " + trade.getName());
			return;
		}

		advance(Phase.SELECT_SELL_ITEM, 800L);
	}

	/**
	 * Selects the inventory item for the sell offer through the game's own
	 * "Select" menu entry instead of a plain click. The default option on food
	 * is Eat, so a plain click would destroy the item; the Select entry is
	 * dispatched directly and the trade aborts untouched when it is missing.
	 */
	private void selectSellItem()
	{
		MenuEntry select = input.findMenuEntry("Select", trade.getItemId());
		if (select != null)
		{
			if (input.invokeMenu(select))
			{
				selectAttempts = 0;
				advance(Phase.VERIFY_SELL_ITEM, 700L);
				return;
			}

			abort("Could not use the Select option on " + trade.getName());
			return;
		}

		String menu = String.join(", ", input.describeMenu());
		if (selectAttempts++ == 0)
		{
			listener.onLog("No Select option on " + trade.getName() + "; menu: ["
				+ (menu.isEmpty() ? "empty" : menu) + "]");
		}

		if (selectAttempts > 4)
		{
			abort("The inventory item offers no Select option; last menu: ["
				+ (menu.isEmpty() ? "empty" : menu) + "]");
			return;
		}

		advance(Phase.HOVER_SELL_ITEM, 600L);
	}

	private void collectCoins()
	{
		collectAll();
		advance(Phase.FINISH, 900L);
	}

	/**
	 * Waits until the sale proceeds reach the inventory, then reports the
	 * cycle. If collection never succeeds the coins are still safe in the
	 * exchange, so the trader logs it and moves on instead of hanging.
	 */
	private void finishCycle()
	{
		if (canCollect())
		{
			if (attempts++ < 5)
			{
				collectAll();
				advance(Phase.FINISH, 800L);
				return;
			}

			listener.onLog("Could not collect the coins for " + trade.getName()
				+ "; they are still in the Grand Exchange.");
		}

		phase = Phase.IDLE;

		FlipCandidate completed = trade;
		trade = null;
		listener.onCycleComplete(completed);
	}

	private boolean canCollect()
	{
		Widget button = client.getWidget(InterfaceID.GeOffers.COLLECTALL);
		Widget target = button == null ? null : button.getChild(0);
		return target != null && !target.isHidden()
			&& findAction(target.getActions(), "Collect to inventory") >= 0;
	}

	/**
	 * Collects everything the current offer has produced. Only the exact
	 * Collect menu action is used, so a missing button never turns into a
	 * stray click somewhere else.
	 */
	private boolean collectAll()
	{
		if (!canCollect())
		{
			return false;
		}

		Widget button = client.getWidget(InterfaceID.GeOffers.COLLECTALL);
		Widget target = button == null ? null : button.getChild(0);
		return input.interactStripped(target, "Collect to inventory");
	}

	private GrandExchangeOffer offer()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null || slotIndex < 0 || slotIndex >= offers.length)
		{
			return null;
		}

		return offers[slotIndex];
	}

	private int findEmptySlot()
	{
		for (int i = 0; i < OFFER_SLOTS; i++)
		{
			Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			if (slot == null)
			{
				continue;
			}

			Widget status = slot.getChild(SLOT_STATUS);
			String text = status == null ? null : stripTags(status.getText());
			if (text != null && text.equalsIgnoreCase("Empty"))
			{
				return i;
			}
		}

		return -1;
	}

	private String chatPrompt()
	{
		Widget prompt = client.getWidget(InterfaceID.Chatbox.MES_TEXT);
		if (prompt == null || prompt.getText() == null)
		{
			return null;
		}

		return stripTags(prompt.getText());
	}

	private static int readNumber(Widget setup, int childIndex)
	{
		if (setup == null)
		{
			return -1;
		}

		Widget child = setup.getChild(childIndex);
		if (child == null || child.getText() == null)
		{
			return -1;
		}

		String digits = child.getText().replaceAll("[^0-9]", "");
		return digits.isEmpty() ? -1 : Integer.parseInt(digits);
	}

	private static int findAction(String[] actions, String action)
	{
		if (actions == null || action == null)
		{
			return -1;
		}

		for (int i = 0; i < actions.length; i++)
		{
			if (actions[i] != null && stripTags(actions[i]).equalsIgnoreCase(action))
			{
				return i;
			}
		}

		return -1;
	}

	private static String stripTags(String value)
	{
		return value == null ? "" : value.replaceAll("<[^>]*>", "").trim();
	}

	private static String normalise(String value)
	{
		return stripTags(value).toLowerCase(Locale.ROOT);
	}

	private void advance(Phase next, long delay)
	{
		long now = System.currentTimeMillis();
		if (next != phase)
		{
			phaseStartedAt = now;
			attempts = 0;
			lastActionFailed = false;
		}

		phase = next;
		nextActionAt = now + delay;
	}

	private void abort(String reason)
	{
		listener.onLog("Aborted: " + reason);

		Widget back = client.getWidget(InterfaceID.GeOffers.BACK);
		if (back != null && !back.isHidden())
		{
			input.click(back);
		}

		phase = Phase.IDLE;
		trade = null;
		lastActionFailed = true;
		listener.onAborted(reason);
	}

	public boolean didLastActionFail()
	{
		return lastActionFailed;
	}
}
