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
package net.runelite.rsbuddy.debug;

import java.awt.Point;
import java.awt.Rectangle;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.MenuEntry;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;

/**
 * Drives the Grand Exchange sell screen itself and writes down what the game
 * exposes at each step, so the sell-item selection no longer needs the player
 * to record it by hand.
 * <p>
 * The probe only opens a sell offer and hovers an item; it never sets a price,
 * never presses Confirm and always backs out. Everything it learns (menu
 * entries with their full identifiers, widget children, varps) is appended to
 * {@code ~/.runelite/rsbuddy-probe.txt}.
 */
public final class GeProbe
{
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final Path FILE = Paths.get(System.getProperty("user.home"), ".runelite", "rsbuddy-probe.txt");

	private static final int SETUP_TITLE = 20;
	private static final int SETUP_ICON = 23;
	private static final int SETUP_ITEM_NAME = 27;

	public interface Listener
	{
		void onLog(String message);
	}

	private final Client client;
	private final GameInput input;
	private final Inventory inventory;
	private final Listener listener;

	private final List<Step> steps = new ArrayList<>();
	private int index;
	private long nextAt;
	private boolean finished;

	public GeProbe(Client client, GameInput input, Inventory inventory, Listener listener)
	{
		this.client = client;
		this.input = input;
		this.inventory = inventory;
		this.listener = listener;

		buildSteps();
	}

	public boolean isFinished()
	{
		return finished;
	}

	public void tick()
	{
		if (finished || System.currentTimeMillis() < nextAt)
		{
			return;
		}

		if (index >= steps.size())
		{
			finish("completed");
			return;
		}

		Step step = steps.get(index++);
		try
		{
			step.action.run();
		}
		catch (RuntimeException ex)
		{
			write("ERROR in " + step.label + ": " + ex);
		}

		nextAt = System.currentTimeMillis() + step.delay;
	}

	/**
	 * Writes one labelled dump of the menu, the setup screen and the varps.
	 */
	public void capture(String label)
	{
		StringBuilder out = new StringBuilder();
		out.append('[').append(TIME.format(LocalDateTime.now())).append("] ").append(label).append('\n');

		out.append("  geOpen=").append(client.getWidget(InterfaceID.GeOffers.UNIVERSE) != null)
			.append("  mouse=").append(describePoint(input.getMouseLocation()))
			.append('\n');
		out.append("  varp TRADINGPOST_SEARCH=").append(client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH))
			.append("  varbit GE_NEWOFFER_TYPE=").append(client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE))
			.append("  varbit GE_SELECTEDSLOT=").append(client.getVarbitValue(VarbitID.GE_SELECTEDSLOT))
			.append('\n');

		for (int i = 0; i < 8; i++)
		{
			Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			Widget status = slot == null ? null : slot.getChild(16);
			if (status == null)
			{
				continue;
			}

			out.append("  slot ").append(i).append(" status=\"").append(strip(status.getText())).append('"')
				.append('\n');
		}

		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		out.append("  setup=").append(setup == null ? "null" : setup.getId() + (setup.isHidden() ? " hidden" : ""))
			.append('\n');
		if (setup != null && !setup.isHidden())
		{
			out.append("  setup title=\"").append(text(setup, SETUP_TITLE)).append('"')
				.append(" itemName=\"").append(text(setup, SETUP_ITEM_NAME)).append('"')
				.append(" iconId=").append(itemId(setup, SETUP_ICON))
				.append('\n');

			Widget[] children = setup.getChildren();
			if (children != null)
			{
				for (int i = 0; i < children.length; i++)
				{
					Widget child = children[i];
					if (child == null || child.isHidden())
					{
						continue;
					}

					boolean interesting = child.getActions() != null
						|| child.getText() != null
						|| child.getItemId() > 0;
					if (!interesting)
					{
						continue;
					}

					out.append("    child ").append(i)
						.append(" type=").append(child.getType())
						.append(" bounds=").append(describeBounds(child.getBounds()));
					if (child.getItemId() > 0)
					{
						out.append(" item=").append(child.getItemId());
					}
					if (child.getText() != null)
					{
						out.append(" text=\"").append(strip(child.getText())).append('"');
					}
					if (child.getActions() != null)
					{
						out.append(" actions=").append(describeActions(child.getActions()));
					}
					out.append('\n');
				}
			}
		}

		out.append("  menu (").append(menuEntries().length).append("):\n");
		for (MenuEntry entry : menuEntries())
		{
			out.append("    ").append(describeEntry(entry)).append('\n');
		}

		Item[] items = inventory.getItems();
		out.append("  inventoryContainer (").append(items.length).append("):");
		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			out.append(' ').append(i).append(':').append(item.getId()).append('x').append(item.getQuantity());
		}
		out.append('\n');

		Widget itemsWidget = inventory.getItemsWidget();
		out.append("  itemsWidget=");
		if (itemsWidget == null)
		{
			out.append("null");
		}
		else
		{
			out.append(itemsWidget.getId())
				.append(itemsWidget.isHidden() ? " hidden" : " visible")
				.append(" children=").append(itemsWidget.getChildren() == null ? 0 : itemsWidget.getChildren().length);
		}
		out.append('\n');

		Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS);
		out.append("  detailsOpen=").append(details != null && !details.isHidden()).append('\n');

		write(out.toString());
	}

	/**
	 * Hovers an inventory slot and leaves the menu to settle.
	 */
	public boolean hoverSlot(int slot)
	{
		Widget widget = inventory.getSlotWidget(slot);
		Rectangle bounds = widget == null ? null : widget.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return false;
		}

		input.moveTo(new Point(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2));
		return true;
	}

	/**
	 * @return the index of the first inventory slot holding an item
	 */
	public int firstFilledSlot()
	{
		Item[] items = inventory.getItems();
		for (int i = 0; i < Math.min(items.length, 28); i++)
		{
			Item item = items[i];
			if (item == null || item.getId() <= 0)
			{
				continue;
			}

			Widget slot = inventory.getSlotWidget(i);
			if (slot != null && !slot.isHidden())
			{
				return i;
			}
		}

		return -1;
	}

	/**
	 * Describes the item actions of an inventory slot, for comparison with the
	 * menu entries captured while the sell screen is open.
	 */
	public String describeSlot(int slot)
	{
		Widget widget = inventory.getSlotWidget(slot);
		if (widget == null)
		{
			return "slot " + slot + " missing";
		}

		return "slot " + slot + " item=" + widget.getItemId() + " qty=" + widget.getItemQuantity()
			+ " actions=" + describeActions(widget.getActions());
	}

	public String menuDump()
	{
		StringBuilder out = new StringBuilder();
		MenuEntry[] entries = menuEntries();
		for (int i = 0; i < entries.length; i++)
		{
			out.append("    #").append(i).append(' ').append(describeEntry(entries[i])).append('\n');
		}

		return out.toString();
	}

	public MenuEntry findSelect(int itemId)
	{
		for (MenuEntry entry : menuEntries())
		{
			if (entry != null && entry.getOption() != null
				&& "Select".equalsIgnoreCase(strip(entry.getOption()))
				&& (entry.getItemId() == itemId || itemId < 0))
			{
				return entry;
			}
		}

		return null;
	}

	public boolean invoke(MenuEntry entry)
	{
		return input.invokeMenu(entry);
	}

	public boolean clickBack()
	{
		Widget back = client.getWidget(InterfaceID.GeOffers.BACK);
		return back != null && !back.isHidden() && input.click(back);
	}

	public boolean openSellSlot()
	{
		boolean clicked = false;

		for (int i = 0; i < 8; i++)
		{
			Widget slot = client.getWidget(InterfaceID.GeOffers.INDEX_0 + i);
			if (slot == null)
			{
				write("  slot " + i + ": widget missing\n");
				continue;
			}

			Widget status = slot.getChild(16);
			String text = status == null ? "?" : strip(status.getText());
			Widget sell = slot.getChild(4);
			write("  slot " + i + " status=\"" + text + "\""
				+ " buy=" + describeWidget(slot.getChild(3))
				+ " sell=" + describeWidget(sell) + "\n");

			if (clicked || !"Empty".equalsIgnoreCase(text) || sell == null)
			{
				continue;
			}

			if (input.interactStripped(sell, "Create Sell offer"))
			{
				write("  clicked slot " + i + " Create Sell offer\n");
				clicked = true;
			}
		}

		return clicked;
	}

	/**
	 * Leaves the offer-detail or setup screen so the slot buttons are usable
	 * again. Only presses Back when one of those screens is actually open.
	 */
	public boolean clickBackIfSubScreen()
	{
		Widget details = client.getWidget(InterfaceID.GeOffers.DETAILS);
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		boolean subScreen = (details != null && !details.isHidden())
			|| (setup != null && !setup.isHidden());

		return subScreen && clickBack();
	}

	private static String describeWidget(Widget widget)
	{
		if (widget == null)
		{
			return "missing";
		}

		return (widget.isHidden() ? "hidden" : "shown") + describeActions(widget.getActions());
	}

	public void write(String text)
	{
		try
		{
			Files.write(FILE, text.getBytes(StandardCharsets.UTF_8),
				StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException ignored)
		{
			// Diagnostics must never break the client.
		}
	}

	private void buildSteps()
	{
		steps.add(new Step("start", 600L, () ->
		{
			StringBuilder header = new StringBuilder();
			header.append("\n=== GE PROBE ").append(STAMP.format(LocalDateTime.now())).append(" ===\n");
			write(header.toString());

			if (client.getWidget(InterfaceID.GeOffers.UNIVERSE) == null)
			{
				write("Grand Exchange is not open; nothing to probe.\n");
				return;
			}

			capture("start");
		}));

		steps.add(new Step("leave-sub-screen", 900L, () ->
		{
			boolean backed = clickBackIfSubScreen();
			write("  backed out of a sub-screen: " + backed + "\n");
		}));

		steps.add(new Step("hover-before", 900L, () ->
		{
			int slot = firstFilledSlot();
			if (slot < 0)
			{
				write("No inventory item to hover: the inventory is empty, or its"
					+ " widgets are missing. Put at least one item in the inventory"
					+ " and run ::rsbuddy probe again.\n");
				return;
			}

			write("  hovering inventory slot " + slot + "\n");
			hoverSlot(slot);
		}));

		steps.add(new Step("menu-before", 500L, () ->
		{
			write("[menu with no sell screen open]\n");
			write(menuDump());
			int slot = firstFilledSlot();
			if (slot >= 0)
			{
				write("  " + describeSlot(slot) + "\n");
			}
		}));

		steps.add(new Step("open-sell", 1_400L, () ->
		{
			if (!openSellSlot())
			{
				write("No empty slot accepted Create Sell offer; stopping here.\n");
			}
		}));

		steps.add(new Step("setup-opened", 700L, () -> capture("sell setup opened")));

		steps.add(new Step("hover-item", 1_100L, () ->
		{
			int slot = firstFilledSlot();
			if (slot >= 0)
			{
				hoverSlot(slot);
			}
		}));

		steps.add(new Step("menu-sell", 600L, () ->
		{
			write("[menu with the sell screen open]\n");
			int slot = firstFilledSlot();
			if (slot >= 0)
			{
				write("  " + describeSlot(slot) + "\n");
			}
			write(menuDump());

			int itemId = slot < 0 ? -1 : inventory.getSlotWidget(slot).getItemId();
			MenuEntry select = findSelect(itemId);
			write("  select entry: " + (select == null ? "MISSING" : describeEntry(select)) + "\n");
			if (select != null)
			{
				invoke(select);
			}
		}));

		steps.add(new Step("after-select", 1_200L, () -> capture("after running Select")));

		steps.add(new Step("back", 900L, () ->
		{
			boolean backed = clickBack();
			write("  pressed Back: " + backed + "\n");
		}));

		steps.add(new Step("done", 0L, () -> capture("after Back")));
	}

	private void finish(String reason)
	{
		finished = true;
		write("=== probe " + reason + " -> " + FILE + " ===\n");
		listener.onLog("Probe " + reason + ". Wrote " + FILE);
	}

	private MenuEntry[] menuEntries()
	{
		net.runelite.api.Menu menu = client.getMenu();
		if (menu != null)
		{
			MenuEntry[] entries = menu.getMenuEntries();
			if (entries != null && entries.length > 0)
			{
				return entries;
			}
		}

		MenuEntry[] fallback = client.getMenuEntries();
		return fallback == null ? new MenuEntry[0] : fallback;
	}

	private static String describeEntry(MenuEntry entry)
	{
		if (entry == null)
		{
			return "null";
		}

		return "option=\"" + strip(entry.getOption()) + "\""
			+ " target=\"" + strip(entry.getTarget()) + "\""
			+ " op=" + entry.getType()
			+ " id=" + entry.getIdentifier()
			+ " p0=" + entry.getParam0()
			+ " p1=" + entry.getParam1()
			+ " item=" + entry.getItemId()
			+ " itemOp=" + (entry.isItemOp() ? entry.getItemOp() : 0);
	}

	private static String describeActions(String[] actions)
	{
		if (actions == null)
		{
			return "none";
		}

		StringBuilder out = new StringBuilder("[");
		boolean first = true;
		for (int i = 0; i < actions.length; i++)
		{
			if (actions[i] == null)
			{
				continue;
			}

			if (!first)
			{
				out.append(", ");
			}
			out.append(i).append(':').append(strip(actions[i]));
			first = false;
		}

		return out.append(']').toString();
	}

	private static String text(Widget widget, int childIndex)
	{
		Widget child = widget.getChild(childIndex);
		return child == null ? "?" : strip(child.getText());
	}

	private static int itemId(Widget widget, int childIndex)
	{
		Widget child = widget.getChild(childIndex);
		return child == null ? -1 : child.getItemId();
	}

	private static String describePoint(Point point)
	{
		return point == null ? "?" : "(" + point.x + "," + point.y + ")";
	}

	private static String describeBounds(Rectangle bounds)
	{
		return bounds == null ? "?"
			: bounds.x + "," + bounds.y + "," + bounds.width + "x" + bounds.height;
	}

	private static String strip(String value)
	{
		return value == null ? "" : value.replaceAll("<[^>]*>", "").trim();
	}

	private static final class Step
	{
		private final String label;
		private final long delay;
		private final Runnable action;

		private Step(String label, long delay, Runnable action)
		{
			this.label = label;
			this.delay = delay;
			this.action = action;
		}
	}
}
