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
package net.runelite.rsbuddy.inventory;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;

/**
 * Read and click helpers for the player's inventory.
 */
public final class Inventory
{
	public static final int SIZE = 28;

	private static final Set<Integer> RAW_FISH = new HashSet<>(Arrays.asList(
		ItemID.RAW_TROUT,
		ItemID.RAW_SALMON));

	private static final Set<Integer> COOKED_FISH = new HashSet<>(Arrays.asList(
		ItemID.TROUT,
		ItemID.SALMON));

	private static final Set<Integer> BURNT_FISH = new HashSet<>(Arrays.asList(
		ItemID.BURNTFISH2));

	private static final Set<Integer> DROP_FISH = new HashSet<>();

	static
	{
		DROP_FISH.addAll(RAW_FISH);
		DROP_FISH.addAll(COOKED_FISH);
		DROP_FISH.addAll(BURNT_FISH);
	}

	private final Client client;
	private final GameInput input;

	public Inventory(Client client, GameInput input)
	{
		this.client = client;
		this.input = input;
	}

	public Item[] getItems()
	{
		ItemContainer container = client.getItemContainer(InventoryID.INV);
		return container == null ? new Item[0] : container.getItems();
	}

	public int getEmptySlots()
	{
		Item[] items = getItems();
		int empty = SIZE;
		for (int slot = 0; slot < Math.min(items.length, SIZE); slot++)
		{
			Item item = items[slot];
			if (item != null && item.getId() != -1)
			{
				--empty;
			}
		}

		return Math.max(0, empty);
	}

	public boolean isFull()
	{
		return getEmptySlots() == 0;
	}

	public int countRawFish()
	{
		return count(RAW_FISH);
	}

	public int countItem(int itemId)
	{
		int count = 0;
		for (Item item : getItems())
		{
			if (item != null && item.getId() == itemId)
			{
				++count;
			}
		}

		return count;
	}

	public int countCookedFish()
	{
		return count(COOKED_FISH);
	}

	public int countBurntFish()
	{
		return count(BURNT_FISH);
	}

	public int countDroppableFish()
	{
		return count(DROP_FISH);
	}

	public boolean hasRawFish()
	{
		return countRawFish() > 0;
	}

	public boolean hasCookedFish()
	{
		return countCookedFish() > 0 || countBurntFish() > 0;
	}

	public boolean hasDroppableFish()
	{
		return countDroppableFish() > 0;
	}

	/**
	 * @return the first slot holding cooked or burnt fish, then raw fish, or -1
	 */
	public int findDroppableFishSlot()
	{
		int slot = findSlot(COOKED_FISH);
		if (slot < 0)
		{
			slot = findSlot(BURNT_FISH);
		}
		if (slot < 0)
		{
			slot = findSlot(RAW_FISH);
		}

		return slot;
	}

	public int findSlot(int itemId)
	{
		Item[] items = getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item != null && item.getId() == itemId)
			{
				return slot;
			}
		}

		return -1;
	}

	/**
	 * @return every slot holding cooked, burnt, or raw fish, in inventory order
	 */
	public List<Integer> findDroppableFishSlots()
	{
		List<Integer> slots = new ArrayList<>();
		Item[] items = getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item != null && DROP_FISH.contains(item.getId()))
			{
				slots.add(slot);
			}
		}

		return slots;
	}

	public Widget getItemsWidget()
	{
		return client.getWidget(InterfaceID.Inventory.ITEMS);
	}

	public Widget getSlotWidget(int slot)
	{
		if (slot < 0 || slot >= SIZE)
		{
			return null;
		}

		Widget items = getItemsWidget();
		return items == null ? null : items.getChild(slot);
	}

	public Rectangle getSlotBounds(int slot)
	{
		Widget widget = getSlotWidget(slot);
		if (widget == null || widget.isHidden())
		{
			return null;
		}

		Rectangle bounds = widget.getBounds();
		return bounds == null || bounds.isEmpty() ? null : bounds;
	}

	public boolean isOpen()
	{
		Widget items = getItemsWidget();
		if (items == null || items.isHidden())
		{
			return false;
		}

		Rectangle bounds = items.getBounds();
		return bounds != null && !bounds.isEmpty();
	}

	/**
	 * Opens the inventory tab if it is not already visible.
	 *
	 * @return true if the inventory is visible or a tab click was sent
	 */
	public boolean open()
	{
		if (isOpen())
		{
			return true;
		}

		Widget tab = getInventoryTab();
		if (tab == null || tab.isHidden())
		{
			return false;
		}

		Rectangle bounds = tab.getBounds();
		return bounds != null && !bounds.isEmpty() && input.click(tab);
	}

	private int count(Set<Integer> itemIds)
	{
		int count = 0;
		for (Item item : getItems())
		{
			if (item != null && itemIds.contains(item.getId()))
			{
				++count;
			}
		}

		return count;
	}

	private int findSlot(Set<Integer> itemIds)
	{
		Item[] items = getItems();
		for (int slot = 0; slot < items.length; slot++)
		{
			Item item = items[slot];
			if (item != null && itemIds.contains(item.getId()))
			{
				return slot;
			}
		}

		return -1;
	}

	private Widget getInventoryTab()
	{
		int topLevel = client.getTopLevelInterfaceId();
		int widgetId;

		if (topLevel == InterfaceID.TOPLEVEL_OSRS_STRETCH)
		{
			widgetId = InterfaceID.ToplevelOsrsStretch.STONE3;
		}
		else if (topLevel == InterfaceID.TOPLEVEL_PRE_EOC)
		{
			widgetId = InterfaceID.ToplevelPreEoc.STONE3;
		}
		else
		{
			widgetId = InterfaceID.Toplevel.STONE3;
		}

		Widget tab = client.getWidget(widgetId);
		return tab != null ? tab : findTabByAction(client.getWidgetRoots());
	}

	private Widget findTabByAction(Widget[] widgets)
	{
		if (widgets == null)
		{
			return null;
		}

		for (Widget widget : widgets)
		{
			if (widget == null)
			{
				continue;
			}

			String[] actions = widget.getActions();
			if (actions != null && Arrays.stream(actions)
				.filter(action -> action != null)
				.anyMatch(action -> action.equalsIgnoreCase("Inventory")))
			{
				Rectangle bounds = widget.getBounds();
				if (bounds != null && !bounds.isEmpty())
				{
					return widget;
				}
			}

			Widget found = findTabByAction(widget.getChildren());
			if (found != null)
			{
				return found;
			}

			found = findTabByAction(widget.getDynamicChildren());
			if (found != null)
			{
				return found;
			}
		}

		return null;
	}
}
