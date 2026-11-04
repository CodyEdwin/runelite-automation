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
package net.runelite.rsbuddy.script;

import java.awt.Point;
import java.awt.Shape;
import java.awt.event.KeyEvent;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.walk.Walker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one shared action layer every input-using script goes through.
 * <p>
 * The standing rules are built in, so a script only says what to click:
 * <ul>
 * <li>real, visible mouse moves and clicks only (OS hardware backend;
 * GameInput refuses direct menu actions for every script)</li>
 * <li>one curved move to the dead centre of the clickable hull, then the
 * real left-click option and target are verified before clicking (Examine
 * is never the verified option)</li>
 * <li>click directly when on screen, otherwise walk by minimap and click on
 * arrival</li>
 * <li>the mouse never hovers or tracks anything while the player moves</li>
 * <li>quick human reaction (80-200 ms after the cursor arrives, clicked from
 * the per-frame hook), no idle gaps, no random camera moves</li>
 * <li>every action logs the gap since the previous one; every aim logs
 * target vs arrival</li>
 * </ul>
 * Call {@link #onClientTick()} from the script's per-frame hook; when it
 * returns true, run the script's step again immediately.
 */
public final class Actions
{
	private static final Logger log = LoggerFactory.getLogger(Actions.class);

	/** Aim misses before turning the camera and walking a step closer. */
	public static final int HOVER_ATTEMPTS = 4;
	/** Camera/walk recoveries before the script halts. */
	public static final int RECOVERIES = 3;
	/** After a click, wait this long for the walk to start before aiming at the same target again. */
	public static final long POST_CLICK_GRACE_MS = 1300L;

	/** What the action layer needs from its script. */
	public interface Host
	{
		void status(String status);

		/** Stop the script with a reason shown in the panel. */
		void halt(String status);

		/** Skip this many game ticks before the next step (0 = next tick). */
		void cooldown(int ticks);

		/** A short label for the current step, used in walk logs. */
		default String stepName()
		{
			return "";
		}
	}

	/** Where the hull/centre of the current target comes from (re-read every frame). */
	private interface Aimable
	{
		Shape hull();

		Point centre();

		boolean hover();

		WorldPoint location();
	}

	private final ScriptContext context;
	private final Host host;
	private final String prefix;
	private final Walker walker;

	private String verifyKey;
	private boolean verifyHovered;
	private Aimable verifyTarget;
	private Point aimTarget;
	private boolean coverMiss;
	private int verifyMisses;
	private int verifyRecoveries;
	private String lastClickKey;
	private long lastClickAt;
	private long fastReadyAt;
	private long lastActionAt;
	private String lastAction = "start";
	private final Set<String> menuDumped = new HashSet<>();
	private final Map<String, Integer> waits = new HashMap<>();
	/** Keys that may be clicked while the bank is open (e.g. the bank itself). */
	private final Set<String> bankKeys = new HashSet<>();

	/**
	 * @param prefix log prefix, for example "[MotherlodeMiner]"
	 */
	public Actions(ScriptContext context, Host host, String prefix)
	{
		this.context = context;
		this.host = host;
		this.prefix = prefix;
		this.walker = new Walker(context.getClient(), context.getInput());
	}

	public Walker walker()
	{
		return walker;
	}

	/** Lets {@code key} be clicked while the bank interface is open (the bank booth/chest). */
	public Actions allowWithBankOpen(String key)
	{
		bankKeys.add(key);
		return this;
	}

	// ------------------------------------------------------------------
	// State checks

	public boolean isMoving()
	{
		return isMoving(context);
	}

	public static boolean isMoving(ScriptContext context)
	{
		Player player = context.getPlayer();
		return player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();
	}

	public boolean isAnimating()
	{
		Player player = context.getPlayer();
		return player != null && player.getAnimation() != -1;
	}

	public boolean bankOpen()
	{
		return context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent();
	}

	/**
	 * @return true when the object has a clickable point on screen (not off
	 * the viewport or under an interface), so it can be clicked directly
	 * instead of walking to it first
	 */
	public boolean clickableOnScreen(TileObject object)
	{
		// With the bank open the game view is covered: walk by minimap
		// instead (the bank closes itself once the walk starts).
		return object != null && context.getInput().objectCenter(object) != null && !bankOpen();
	}

	/** @return true while a verified hover is in progress (mouse on or moving to a target) */
	public boolean isAiming()
	{
		return verifyHovered;
	}

	/** @return true when any aim target is held (hovered, or being followed) */
	public boolean hasTarget()
	{
		return verifyHovered || verifyTarget != null || aimTarget != null;
	}

	public String targetKey()
	{
		return verifyKey;
	}

	// ------------------------------------------------------------------
	// Logging

	/** Logs an action and the time since the previous one. */
	public void markAction(String action)
	{
		long now = System.currentTimeMillis();
		log.info("{} +{}ms {} (after {})", prefix, lastActionAt == 0 ? 0 : now - lastActionAt, action, lastAction);
		lastActionAt = now;
		lastAction = action;
	}

	// ------------------------------------------------------------------
	// Walking

	/**
	 * Minimap (or tile) walk, logged with the gap since the previous action.
	 * The mouse does nothing else until arrival.
	 */
	public boolean walkTo(WorldPoint target, boolean minimap, int stopDistance)
	{
		if (target == null)
		{
			return false;
		}
		if (hasTarget())
		{
			dropAim("walking");
		}
		boolean sent = walker.walkTo(target, minimap, stopDistance);
		if (sent)
		{
			markAction("walk " + host.stepName() + " -> " + target.getX() + "," + target.getY());
		}
		return sent;
	}

	public boolean walkTo(WorldPoint target)
	{
		return walkTo(target, true, 2);
	}

	/** Same tile, on the player's plane. */
	public WorldPoint onPlayerPlane(WorldPoint point)
	{
		WorldPoint location = context.getPlayerLocation();
		return location == null ? point : new WorldPoint(point.getX(), point.getY(), location.getPlane());
	}

	// ------------------------------------------------------------------
	// Verified clicks

	/**
	 * Verified left click on a game object, aimed at the dead centre of its
	 * clickable hull. Call every step until it returns true. First call: one
	 * curved move onto the centre. When the cursor has arrived (per-frame
	 * hook, after a human 80-200 ms pause): click only if the cursor is on the
	 * hull and the real left-click option starts with one of
	 * {@code optionPrefixes} and the target contains {@code targetWord};
	 * otherwise re-aim. After {@link #HOVER_ATTEMPTS} misses it turns the
	 * camera and walks a step closer; it halts after {@link #RECOVERIES}.
	 * While the player moves, nothing is aimed or tracked.
	 *
	 * @param key a short stable name for the target ("sack", "bank chest")
	 * @return true when the click was sent
	 */
	public boolean clickObject(TileObject object, String key, String[] optionPrefixes, String targetWord)
	{
		if (object == null)
		{
			return false;
		}
		GameInput input = context.getInput();
		return verifiedClick(new Aimable()
		{
			@Override
			public Shape hull()
			{
				return input.objectHull(object);
			}

			@Override
			public Point centre()
			{
				return input.objectCenter(object);
			}

			@Override
			public boolean hover()
			{
				return input.hover(object);
			}

			@Override
			public WorldPoint location()
			{
				return object.getWorldLocation();
			}
		}, key, optionPrefixes, targetWord);
	}

	/** {@link #clickObject} for an NPC (its convex hull). */
	public boolean clickNpc(NPC npc, String key, String[] optionPrefixes, String targetWord)
	{
		if (npc == null)
		{
			return false;
		}
		GameInput input = context.getInput();
		return verifiedClick(new Aimable()
		{
			@Override
			public Shape hull()
			{
				return npc.getConvexHull();
			}

			@Override
			public Point centre()
			{
				Shape hull = npc.getConvexHull();
				if (hull == null)
				{
					return null;
				}
				java.awt.Rectangle b = hull.getBounds();
				Point c = new Point((int) b.getCenterX(), (int) b.getCenterY());
				return hull.contains(c) ? c : null;
			}

			@Override
			public boolean hover()
			{
				return input.hover(npc);
			}

			@Override
			public WorldPoint location()
			{
				return npc.getWorldLocation();
			}
		}, key, optionPrefixes, targetWord);
	}

	/** {@link #clickObject} for a widget (inventory item, bank item, button). */
	public boolean clickWidget(Widget widget, String key, String[] optionPrefixes, String targetWord)
	{
		if (widget == null || widget.isHidden())
		{
			return false;
		}
		GameInput input = context.getInput();
		return verifiedClick(new Aimable()
		{
			@Override
			public Shape hull()
			{
				return widget.isHidden() ? null : widget.getBounds();
			}

			@Override
			public Point centre()
			{
				java.awt.Rectangle b = widget.getBounds();
				return b == null || widget.isHidden() ? null : new Point((int) b.getCenterX(), (int) b.getCenterY());
			}

			@Override
			public boolean hover()
			{
				return input.hover(widget);
			}

			@Override
			public WorldPoint location()
			{
				return null;
			}
		}, key, optionPrefixes, targetWord);
	}

	/** Clicks an inventory item whose left-click option starts with {@code option}. */
	public boolean clickItem(int itemId, String option)
	{
		Widget item = context.getInventory().getSlotWidget(context.getInventory().findSlot(itemId));
		return clickWidget(item, "item " + itemId, new String[]{option.toLowerCase(Locale.ROOT)}, "");
	}

	/**
	 * Item on item: clicks "Use" on {@code first}, then the second item once
	 * the first is selected. Call until it returns true.
	 */
	public boolean useItemOn(int first, int second)
	{
		if (context.getClient().isWidgetSelected())
		{
			Widget target = context.getInventory().getSlotWidget(context.getInventory().findSlot(second));
			return clickWidget(target, "use on " + second, new String[]{"use"}, "->");
		}
		clickItem(first, "use");
		return false;
	}

	private boolean verifiedClick(Aimable object, String key, String[] optionPrefixes, String targetWord)
	{
		GameInput input = context.getInput();
		if (!key.equals(verifyKey))
		{
			if (verifyHovered || verifyTarget != null)
			{
				dropAim("new target " + key);
			}
			verifyKey = key;
			verifyHovered = false;
			verifyMisses = 0;
			verifyRecoveries = 0;
		}
		if (input.isBusy())
		{
			// The mouse is still travelling to the centre.
			return false;
		}
		if (!bankKeys.contains(key) && bankOpen())
		{
			// The bank closes once the walk starts; do not click through it.
			return false;
		}
		if (isMoving())
		{
			// Never chase any target with the mouse while walking: leave it
			// idle and aim once the player has stopped.
			dropAim(key + ": player moving, mouse idle until arrival");
			host.status("Walking to the " + key);
			return false;
		}
		if (key.equals(lastClickKey) && System.currentTimeMillis() - lastClickAt < POST_CLICK_GRACE_MS)
		{
			// Just clicked it: the walk starts a tick later. Do not re-aim at
			// (and then track) the same target in that gap.
			host.status("Walking to the " + key);
			return false;
		}
		if (!verifyHovered)
		{
			// One move to the centre of the clickable area (the same shape
			// the check uses). A miss on the option only (something covers
			// the centre) tries another point inside it on the next aim.
			aimTarget = coverMiss ? null : object.centre();
			boolean moved = aimTarget != null ? input.moveTo(aimTarget) : object.hover();
			coverMiss = false;
			if (moved)
			{
				verifyHovered = true;
				verifyTarget = object;
			}
			else
			{
				++verifyMisses;
				log.info("{} {}: no clickable point on screen (miss {}/{})", prefix, key, verifyMisses,
					HOVER_ATTEMPTS);
				if (verifyMisses >= HOVER_ATTEMPTS)
				{
					verifyMisses = HOVER_ATTEMPTS - 1;
					return recover(object, key);
				}
			}
			host.status("Moving mouse to the " + key);
			return false;
		}
		verifyHovered = false;
		verifyTarget = null;
		input.follow(null);

		MenuEntry entry = input.getLeftClickEntry();
		String option = entry == null ? "" : GameInput.plainText(entry.getOption()).toLowerCase(Locale.ROOT);
		String target = entry == null ? "" : GameInput.plainText(entry.getTarget()).toLowerCase(Locale.ROOT);
		Point mouse = input.getMouseLocation();
		Point center = object.centre();
		Shape hull = object.hull();
		boolean onHull = mouse != null && hull != null && hull.contains(mouse);
		log.info("{} aim: target {} -> arrived {}, centre now {}, re-aim={}", prefix, key
			+ " " + (aimTarget == null ? "-" : aimTarget.x + "," + aimTarget.y),
			mouse == null ? "-" : mouse.x + "," + mouse.y, center == null ? "-" : center.x + "," + center.y,
			onHull ? "no" : "yes");
		boolean optionOk = target.contains(targetWord);
		boolean prefixOk = false;
		for (String p : optionPrefixes)
		{
			prefixOk |= option.startsWith(p);
		}
		if (onHull && optionOk && prefixOk)
		{
			if (input.click(mouse))
			{
				log.info("{} {} click: top='{} {}' at {},{} (centre {})", prefix, key, option, target,
					mouse.x, mouse.y, center == null ? "-" : center.x + "," + center.y);
				markAction("click " + key);
				verifyMisses = 0;
				verifyKey = null;
				lastClickKey = key;
				lastClickAt = System.currentTimeMillis();
				return true;
			}
			return false;
		}

		++verifyMisses;
		coverMiss = onHull;
		if (menuDumped.add(key))
		{
			// Once per start and target: the full menu ordering, to check
			// which entry the game treats as the left click.
			log.info("{} {} menu order: {}", prefix, key, input.describeMenuOrder());
		}
		log.info("{} {} hover miss {}/{}: top='{} {}' onHull={} -> re-aiming", prefix, key,
			verifyMisses, HOVER_ATTEMPTS, option, target, onHull);
		if (verifyMisses < HOVER_ATTEMPTS)
		{
			host.cooldown(0);
			return false;
		}

		return recover(object, key);
	}

	private boolean recover(Aimable object, String key)
	{
		// Never act invisibly. Turn the camera toward it and walk a step
		// closer (a real tile/minimap click), then re-aim; halt after a few
		// recoveries with a clear log.
		verifyMisses = 0;
		if (++verifyRecoveries > RECOVERIES)
		{
			log.info("{} {}: left click never verified after {} recoveries, halting", prefix, key, RECOVERIES);
			host.halt("Stopped: could not click the " + key);
			return false;
		}
		WorldPoint location = object.location();
		if (location == null)
		{
			log.info("{} {}: recovery {}/{} - re-aiming", prefix, key, verifyRecoveries, RECOVERIES);
			host.cooldown(1);
			return false;
		}
		log.info("{} {}: recovery {}/{} - turning camera and walking closer, then re-aiming", prefix, key,
			verifyRecoveries, RECOVERIES);
		context.getInput().turnCameraToward(location);
		walkTo(onPlayerPlane(location), true, 1);
		host.cooldown(2);
		return false;
	}

	/**
	 * Clears every hover/aim target and stops the cursor follower, logging
	 * the reason when there was something to drop.
	 */
	public void dropAim(String reason)
	{
		if (hasTarget())
		{
			log.info("{} {}, dropping the {} target", prefix, reason, verifyKey == null ? "aim" : verifyKey);
		}
		verifyHovered = false;
		verifyTarget = null;
		aimTarget = null;
		context.getInput().follow(null);
	}

	/** Forget the current target entirely (next click starts fresh). */
	public void resetTarget()
	{
		dropAim("target reset");
		verifyKey = null;
	}

	/** Clears all per-run state. Call from onStart. */
	public void reset()
	{
		verifyKey = null;
		verifyHovered = false;
		verifyTarget = null;
		aimTarget = null;
		coverMiss = false;
		verifyMisses = 0;
		verifyRecoveries = 0;
		lastClickKey = null;
		lastClickAt = 0;
		fastReadyAt = 0;
		lastActionAt = 0;
		lastAction = "start";
		menuDumped.clear();
		waits.clear();
		walker.reset();
	}

	// ------------------------------------------------------------------
	// Per-frame chaining

	/**
	 * Per-frame (client thread) hook. Drops targets while moving, glides the
	 * end of a move onto a shifted centre, and after the cursor arrives plus
	 * a human 80-200 ms pause returns true so the script runs its step now
	 * (which then verifies and clicks) instead of waiting for the next tick.
	 */
	public boolean onClientTick()
	{
		GameInput input = context.getInput();
		if (input.isBusy())
		{
			fastReadyAt = 0;
			return false;
		}
		if (hasTarget() && isMoving())
		{
			// Never hover or track a target while the player runs.
			dropAim("player is moving");
			fastReadyAt = 0;
			return false;
		}
		if (verifyHovered && verifyTarget != null)
		{
			Shape area = verifyTarget.hull();
			Point mouse = input.getMouseLocation();
			if (area != null && mouse != null && !area.contains(mouse))
			{
				// The scene shifted after the move (camera settling): glide
				// the end of the path onto the live centre rather than a
				// second hop.
				Point centre = verifyTarget.centre();
				if (centre != null)
				{
					input.follow(centre);
					fastReadyAt = 0;
					return false;
				}
			}
		}
		if (!verifyHovered)
		{
			fastReadyAt = 0;
			return false;
		}
		long now = System.currentTimeMillis();
		if (fastReadyAt == 0)
		{
			fastReadyAt = now + ThreadLocalRandom.current().nextLong(80, 201);
			return false;
		}
		if (now >= fastReadyAt)
		{
			fastReadyAt = 0;
			return true;
		}
		return false;
	}

	// ------------------------------------------------------------------
	// Keys and waits

	/** A real key press through the hardware backend. */
	public boolean pressKey(int keyCode)
	{
		boolean sent = context.getInput().pressKey(keyCode);
		if (sent)
		{
			markAction("key " + KeyEvent.getKeyText(keyCode));
		}
		return sent;
	}

	/** Make-all in a make/production menu: Space (the highlighted option). */
	public boolean makeAll()
	{
		return pressKey(KeyEvent.VK_SPACE);
	}

	/** Picks option {@code n} (1-9) in a make/production menu with its number key. */
	public boolean makeOption(int n)
	{
		return pressKey(KeyEvent.VK_0 + Math.max(1, Math.min(9, n)));
	}

	/** Result of {@link #waitUntil}. */
	public enum Wait
	{
		WAITING, DONE, TIMED_OUT
	}

	/**
	 * Tick-counted wait: DONE as soon as {@code condition} holds, TIMED_OUT
	 * after {@code maxTicks} calls (the cap is a timeout only, never a delay),
	 * otherwise WAITING. Each {@code key} counts separately; it resets once
	 * it finishes.
	 */
	public Wait waitUntil(String key, BooleanSupplier condition, int maxTicks)
	{
		if (condition.getAsBoolean())
		{
			waits.remove(key);
			return Wait.DONE;
		}
		int n = waits.merge(key, 1, Integer::sum);
		if (n > maxTicks)
		{
			waits.remove(key);
			log.info("{} wait '{}' timed out after {} ticks", prefix, key, maxTicks);
			return Wait.TIMED_OUT;
		}
		return Wait.WAITING;
	}

	/**
	 * Clicks the object directly if it is clickable on screen, otherwise
	 * walks to it by minimap (mouse idle) and clicks on arrival.
	 *
	 * @return true when the click was sent
	 */
	public boolean goAndClick(Supplier<TileObject> find, WorldPoint fallbackArea, String key,
		String[] optionPrefixes, String targetWord, int reach)
	{
		TileObject object = find.get();
		WorldPoint location = context.getPlayerLocation();
		if (object == null)
		{
			if (fallbackArea != null && !isMoving())
			{
				walkTo(onPlayerPlane(fallbackArea), true, 2);
			}
			host.status("Looking for the " + key);
			return false;
		}
		if (location != null && location.distanceTo2D(object.getWorldLocation()) > reach && !clickableOnScreen(object))
		{
			if (!isMoving())
			{
				walkTo(object.getWorldLocation(), true, 2);
			}
			host.status("Walking to the " + key);
			return false;
		}
		return clickObject(object, key, optionPrefixes, targetWord);
	}

	// ------------------------------------------------------------------
	// Bank

	/**
	 * Opens the nearest bank (booth, chest or banker) found by BankFinder:
	 * clicks it directly when on screen, otherwise walks there by minimap and
	 * clicks on arrival. Call until {@link #bankOpen()} is true.
	 */
	public boolean openNearestBank(int radius, boolean preferBooth)
	{
		if (bankOpen())
		{
			return true;
		}
		net.runelite.rsbuddy.target.BankFinder.BankTarget bank = net.runelite.rsbuddy.target.BankFinder.find(
			context.getClient(), context.getTargets(), context.getPlayerLocation(), radius, preferBooth, prefix);
		if (bank == null)
		{
			host.status("No bank nearby");
			return false;
		}
		String action = bank.getAction() == null ? "bank" : bank.getAction().toLowerCase(Locale.ROOT);
		String name = bank.getName() == null ? "" : bank.getName().toLowerCase(Locale.ROOT);
		allowWithBankOpen("bank");
		if (bank.getObject() != null)
		{
			TileObject object = bank.getObject();
			WorldPoint location = context.getPlayerLocation();
			if (location != null && location.distanceTo2D(object.getWorldLocation()) > 12 && !clickableOnScreen(object))
			{
				if (!isMoving())
				{
					walkTo(object.getWorldLocation(), true, 2);
				}
				host.status("Walking to the bank");
				return false;
			}
			clickObject(object, "bank", new String[]{action}, name);
		}
		else
		{
			clickNpc(bank.getNpc(), "bank", new String[]{action}, name);
		}
		return false;
	}

	/** Clicks the bank's "Deposit inventory" button. */
	public boolean depositAll()
	{
		Widget deposit = context.getTargets().findWidget(InterfaceID.Bankmain.DEPOSITINV).orElse(null);
		allowWithBankOpen("deposit inventory");
		return clickWidget(deposit, "deposit inventory", new String[]{"deposit"}, "");
	}

	/**
	 * Withdraws with the item's left-click option (set the bank's quantity
	 * button first for amounts other than its default).
	 */
	public boolean withdraw(int itemId)
	{
		Widget item = context.getInput().findBankItemWidget(itemId);
		allowWithBankOpen("withdraw " + itemId);
		return clickWidget(item, "withdraw " + itemId, new String[]{"withdraw"}, "");
	}
}
