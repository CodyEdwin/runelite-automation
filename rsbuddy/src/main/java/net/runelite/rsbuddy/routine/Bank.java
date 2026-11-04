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
package net.runelite.rsbuddy.routine;

import java.util.Locale;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.target.BankFinder;

/**
 * Bank module: open the nearest bank, deposit, withdraw, all through
 * {@link Interact}'s verified real clicks.
 * <p>
 * Example: {@code if (actions.bankOpen()) actions.depositAll(); else actions.openNearestBank(15, true);}
 */
public final class Bank
{
	private final RoutineContext context;
	private final Actions.Host host;
	private final ActionLog log;
	private final Interact interact;
	private final Movement movement;

	Bank(RoutineContext context, Actions.Host host, ActionLog log, Interact interact, Movement movement)
	{
		this.context = context;
		this.host = host;
		this.log = log;
		this.interact = interact;
		this.movement = movement;
	}

	/** @return true while the bank interface is open */
	public boolean isOpen()
	{
		return context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent();
	}

	/**
	 * Opens the nearest bank (booth, chest or banker) found by BankFinder:
	 * clicks it directly when on screen, otherwise walks there by minimap and
	 * clicks on arrival. Call every step until {@link #isOpen()} is true.
	 *
	 * @param radius search radius in tiles
	 * @param preferBooth prefer a booth/chest over a banker NPC when both are near
	 * @return true when the bank is already open
	 */
	public boolean openNearest(int radius, boolean preferBooth)
	{
		if (isOpen())
		{
			return true;
		}
		BankFinder.BankTarget bank = BankFinder.find(
			context.getClient(), context.getTargets(), context.getPlayerLocation(), radius, preferBooth, log.prefix());
		if (bank == null)
		{
			host.status("No bank nearby");
			return false;
		}
		String action = bank.getAction() == null ? "bank" : bank.getAction().toLowerCase(Locale.ROOT);
		String name = bank.getName() == null ? "" : bank.getName().toLowerCase(Locale.ROOT);
		interact.allowWithBankOpen("bank");
		if (bank.getObject() != null)
		{
			TileObject object = bank.getObject();
			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.distanceTo2D(object.getWorldLocation()) > 12
				&& !movement.clickableOnScreen(object))
			{
				if (!movement.isMoving())
				{
					movement.walkTo(object.getWorldLocation(), true, 2);
				}
				host.status("Walking to the bank");
				return false;
			}
			interact.clickObject(object, "bank", new String[]{action}, name);
		}
		else
		{
			interact.clickNpc(bank.getNpc(), "bank", new String[]{action}, name);
		}
		return false;
	}

	/**
	 * Clicks the bank's "Deposit inventory" button. Call until true.
	 *
	 * @return true when the click was sent
	 */
	public boolean depositAll()
	{
		Widget deposit = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
		interact.allowWithBankOpen("deposit inventory");
		return interact.clickWidget(deposit, "deposit inventory", new String[]{"deposit"}, "");
	}

	/**
	 * Withdraws with the item's left-click option. Set the bank's quantity
	 * button first for amounts other than its default. The item must be
	 * visible in the open bank tab. Call until true.
	 *
	 * @return true when the click was sent
	 */
	public boolean withdraw(int itemId)
	{
		Widget item = context.getInput().findBankItemWidget(itemId);
		interact.allowWithBankOpen("withdraw " + itemId);
		return interact.clickWidget(item, "withdraw " + itemId, new String[]{"withdraw"}, "");
	}
}
