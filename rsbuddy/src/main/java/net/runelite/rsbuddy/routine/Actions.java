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

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.walk.Walker;

/**
 * The one action layer every input-using routine goes through. It is a thin
 * facade over small modules, each with one job:
 * <table>
 * <caption>Modules</caption>
 * <tr><td>{@link #aim()}</td><td>{@link Aim}: dead-centre target, drift correction, drop target, reaction delay</td></tr>
 * <tr><td>{@link #interact()}</td><td>{@link Interact}: verified clicks on objects, NPCs, widgets, items</td></tr>
 * <tr><td>{@link #movement()}</td><td>{@link Movement}: minimap walking, isMoving, click-or-walk</td></tr>
 * <tr><td>{@link #bank()}</td><td>{@link Bank}: open nearest, deposit all, withdraw</td></tr>
 * <tr><td>{@link #keys()}</td><td>{@link Keys}: key presses, make-all</td></tr>
 * <tr><td>{@link #waits()}</td><td>{@link Waits}: tick-counted waits, isAnimating</td></tr>
 * <tr><td>{@link #log()}</td><td>{@link ActionLog}: gap and aim logging</td></tr>
 * </table>
 * The common calls are also flat methods here ({@code actions.clickObject(...)}),
 * so simple routines never need the module accessors.
 * <p>
 * Standing rules every routine gets: real visible mouse moves and clicks
 * only (OS hardware backend; direct menu actions are refused); one curved
 * move to the dead centre; the left-click option is verified before
 * clicking; on-screen targets are clicked directly, others walked to by
 * minimap and clicked on arrival; no hovering while moving; 80-200 ms human
 * reaction; no random camera moves; every action logs its gap.
 * <p>
 * A {@link TaskRoutine} creates this for you as {@code actions}. A plain
 * {@link Routine} creates one with {@code new Actions(getContext(), host, "[Name]")}
 * and calls {@link #onClientTick()} from its per-frame hook.
 */
public final class Actions
{
	/** What the action layer needs from its routine. */
	public interface Host
	{
		/** Shows a short status in the panel. */
		void status(String status);

		/** Stops the routine with a reason shown in the panel. */
		void halt(String status);

		/** Skips this many game ticks before the next step (0 = next tick). */
		void cooldown(int ticks);

		/** @return a short label for the current step, used in walk logs */
		default String stepName()
		{
			return "";
		}
	}

	private final ActionLog log;
	private final Aim aim;
	private final Movement movement;
	private final Interact interact;
	private final Bank bank;
	private final Keys keys;
	private final Waits waits;

	/**
	 * @param context the routine's context
	 * @param host status/halt/cooldown callbacks (TaskRoutine implements it)
	 * @param prefix log prefix, for example "[MotherlodeMiner]"
	 */
	public Actions(RoutineContext context, Host host, String prefix)
	{
		this.log = new ActionLog(prefix);
		this.aim = new Aim(context, log);
		this.movement = new Movement(context, host, log, aim);
		this.interact = new Interact(context, host, log, aim, movement);
		this.movement.bind(interact);
		this.bank = new Bank(context, host, log, interact, movement);
		this.keys = new Keys(context, log);
		this.waits = new Waits(context, log);
	}

	// ------------------------------------------------------------------
	// Modules

	/** @return the mouse/aim module */
	public Aim aim()
	{
		return aim;
	}

	/** @return the verified-click module */
	public Interact interact()
	{
		return interact;
	}

	/** @return the walking module */
	public Movement movement()
	{
		return movement;
	}

	/** @return the bank module */
	public Bank bank()
	{
		return bank;
	}

	/** @return the key module */
	public Keys keys()
	{
		return keys;
	}

	/** @return the wait module */
	public Waits waits()
	{
		return waits;
	}

	/** @return the timing/logging module */
	public ActionLog log()
	{
		return log;
	}

	/** Clears all per-run state (targets, waits, walker, gap log). Call from onStart when reusing. */
	public void reset()
	{
		aim.reset();
		waits.reset();
		movement.reset();
		log.reset();
	}

	// ------------------------------------------------------------------
	// Flat shortcuts (each delegates to one module)

	/** @see Movement#walker() */
	public Walker walker()
	{
		return movement.walker();
	}

	/**
	 * Lets {@code key} be clicked while the bank is open. Chains.
	 *
	 * @see Interact#allowWithBankOpen(String)
	 */
	public Actions allowWithBankOpen(String key)
	{
		interact.allowWithBankOpen(key);
		return this;
	}

	/** @see Movement#isMoving() */
	public boolean isMoving()
	{
		return movement.isMoving();
	}

	/** @see Movement#isMoving(RoutineContext) */
	public static boolean isMoving(RoutineContext context)
	{
		return Movement.isMoving(context);
	}

	/** @see Movement#isMoving(Player) */
	public static boolean isMoving(Player player)
	{
		return Movement.isMoving(player);
	}

	/** @see Waits#isAnimating() */
	public boolean isAnimating()
	{
		return waits.isAnimating();
	}

	/** @see Bank#isOpen() */
	public boolean bankOpen()
	{
		return bank.isOpen();
	}

	/** @see Movement#clickableOnScreen(TileObject) */
	public boolean clickableOnScreen(TileObject object)
	{
		return movement.clickableOnScreen(object);
	}

	/** @see Aim#isAiming() */
	public boolean isAiming()
	{
		return aim.isAiming();
	}

	/** @see Aim#hasTarget() */
	public boolean hasTarget()
	{
		return aim.hasTarget();
	}

	/** @see Aim#targetKey() */
	public String targetKey()
	{
		return aim.targetKey();
	}

	/** @see ActionLog#markAction(String) */
	public void markAction(String action)
	{
		log.markAction(action);
	}

	/** @see Movement#walkTo(WorldPoint, boolean, int) */
	public boolean walkTo(WorldPoint target, boolean minimap, int stopDistance)
	{
		return movement.walkTo(target, minimap, stopDistance);
	}

	/** @see Movement#walkTo(WorldPoint) */
	public boolean walkTo(WorldPoint target)
	{
		return movement.walkTo(target);
	}

	/** @see Movement#onPlayerPlane(WorldPoint) */
	public WorldPoint onPlayerPlane(WorldPoint point)
	{
		return movement.onPlayerPlane(point);
	}

	/**
	 * Verified real click on an object's dead centre. Call until true.
	 * Example: {@code actions.clickObject(sack, "sack", new String[]{"search"}, "sack")}
	 *
	 * @see Interact#clickObject(TileObject, String, String[], String)
	 */
	public boolean clickObject(TileObject object, String key, String[] optionPrefixes, String targetWord)
	{
		return interact.clickObject(object, key, optionPrefixes, targetWord);
	}

	/** @see Interact#clickNpc(NPC, String, String[], String) */
	public boolean clickNpc(NPC npc, String key, String[] optionPrefixes, String targetWord)
	{
		return interact.clickNpc(npc, key, optionPrefixes, targetWord);
	}

	/** @see Interact#clickWidget(Widget, String, String[], String) */
	public boolean clickWidget(Widget widget, String key, String[] optionPrefixes, String targetWord)
	{
		return interact.clickWidget(widget, key, optionPrefixes, targetWord);
	}

	/** @see Interact#clickItem(int, String) */
	public boolean clickItem(int itemId, String option)
	{
		return interact.clickItem(itemId, option);
	}

	/** @see Interact#useItemOn(int, int) */
	public boolean useItemOn(int first, int second)
	{
		return interact.useItemOn(first, second);
	}

	/** @see Interact#chooseOption(TileObject, String...) */
	public boolean chooseOption(TileObject object, String... options)
	{
		return interact.chooseOption(object, options);
	}

	/** @see Aim#dropAim(String) */
	public void dropAim(String reason)
	{
		aim.dropAim(reason);
	}

	/** @see Aim#forgetKey() */
	public void forgetKey()
	{
		aim.forgetKey();
	}

	/** @see Aim#resetTarget() */
	public void resetTarget()
	{
		aim.resetTarget();
	}

	/**
	 * Per-frame hook; call from the routine's onClientTick. When true, run
	 * the routine's step now (TaskRoutine does this for you).
	 *
	 * @see Aim#onClientTick()
	 */
	public boolean onClientTick()
	{
		return aim.onClientTick();
	}

	/** @see Keys#press(int) */
	public boolean pressKey(int keyCode)
	{
		return keys.press(keyCode);
	}

	/** @see Keys#makeAll() */
	public boolean makeAll()
	{
		return keys.makeAll();
	}

	/** @see Keys#makeOption(int) */
	public boolean makeOption(int n)
	{
		return keys.makeOption(n);
	}

	/** @see Waits#until(String, BooleanSupplier, int) */
	public Waits.Result waitUntil(String key, BooleanSupplier condition, int maxTicks)
	{
		return waits.until(key, condition, maxTicks);
	}

	/** @see Movement#goAndClick */
	public boolean goAndClick(Supplier<TileObject> find, WorldPoint fallbackArea, String key,
		String[] optionPrefixes, String targetWord, int reach)
	{
		return movement.goAndClick(find, fallbackArea, key, optionPrefixes, targetWord, reach);
	}

	/** @see Bank#openNearest(int, boolean) */
	public boolean openNearestBank(int radius, boolean preferBooth)
	{
		return bank.openNearest(radius, preferBooth);
	}

	/** @see Bank#depositAll() */
	public boolean depositAll()
	{
		return bank.depositAll();
	}

	/** @see Bank#withdraw(int) */
	public boolean withdraw(int itemId)
	{
		return bank.withdraw(itemId);
	}
}
