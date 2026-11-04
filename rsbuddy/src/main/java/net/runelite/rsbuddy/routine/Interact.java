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

import java.awt.Point;
import java.awt.Shape;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.rsbuddy.input.GameInput;

/**
 * Interact module: verified, real, visible clicks on objects, NPCs, widgets
 * and inventory items.
 * <p>
 * Guarantees for every click:
 * <ul>
 * <li>a real mouse move and click through the OS hardware backend (never a
 * direct menu action; GameInput refuses those for every routine)</li>
 * <li>one curved move to the dead centre of the clickable hull</li>
 * <li>the real left-click option and target are checked before clicking;
 * Examine never counts, and a miss re-aims instead of clicking</li>
 * <li>nothing is aimed while the player moves, and the same target is not
 * re-aimed within {@link #POST_CLICK_GRACE_MS} of clicking it</li>
 * <li>after {@link #HOVER_ATTEMPTS} misses: camera turn plus a step closer;
 * after {@link #RECOVERIES} of those the routine halts with a reason</li>
 * </ul>
 * Each click method is called every step until it returns true (first call
 * aims, a later call clicks). Example:
 * {@code if (actions.clickObject(sack, "sack", new String[]{"search"}, "sack")) waitForOres();}
 */
public final class Interact
{
	/** Aim misses before turning the camera and walking a step closer. */
	public static final int HOVER_ATTEMPTS = 4;
	/** Camera/walk recoveries before the routine halts. */
	public static final int RECOVERIES = 3;
	/** After a click, wait this long for the walk to start before aiming at the same target again. */
	public static final long POST_CLICK_GRACE_MS = 1300L;

	private final RoutineContext context;
	private final Actions.Host host;
	private final ActionLog log;
	private final Aim aim;
	private final Movement movement;
	/** Keys that may be clicked while the bank is open (the bank itself, its buttons and items). */
	private final Set<String> bankKeys = new HashSet<>();

	Interact(RoutineContext context, Actions.Host host, ActionLog log, Aim aim, Movement movement)
	{
		this.context = context;
		this.host = host;
		this.log = log;
		this.aim = aim;
		this.movement = movement;
	}

	/**
	 * Lets {@code key} be clicked while the bank interface is open. By
	 * default nothing is clicked through an open bank.
	 *
	 * @param key the click key, for example "bank chest"
	 */
	public void allowWithBankOpen(String key)
	{
		bankKeys.add(key);
	}

	/**
	 * Uses a non-left-click option the real way: right-click the object, then
	 * click the option's row in the menu on a later call. Call until true.
	 *
	 * @param options accepted option texts, for example "Bank", "Use"
	 * @return true when the menu row was clicked
	 */
	public boolean chooseOption(TileObject object, String... options)
	{
		if (object == null)
		{
			return false;
		}
		if (context.getClient().isMenuOpen())
		{
			// Our right-click menu is open: click the option's row.
			return context.getInput().interactViaMenu(object, options);
		}
		// First call: the visible right-click.
		context.getInput().interactViaMenu(object, options);
		return false;
	}

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
		return verifiedClick(new Aim.Aimable()
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
		return verifiedClick(new Aim.Aimable()
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
		return verifiedClick(new Aim.Aimable()
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

	private boolean verifiedClick(Aim.Aimable object, String key, String[] optionPrefixes, String targetWord)
	{
		GameInput input = context.getInput();
		if (!key.equals(aim.verifyKey))
		{
			if (aim.verifyHovered || aim.verifyTarget != null)
			{
				aim.dropAim("new target " + key);
			}
			aim.verifyKey = key;
			aim.verifyHovered = false;
			aim.verifyMisses = 0;
			aim.verifyRecoveries = 0;
		}
		if (input.isBusy())
		{
			// The mouse is still travelling to the centre.
			return false;
		}
		if (!bankKeys.contains(key) && context.getTargets().findWidget(InterfaceID.Bankmain.UNIVERSE).isPresent())
		{
			// The bank closes once the walk starts; do not click through it.
			return false;
		}
		if (movement.isMoving())
		{
			// Never chase any target with the mouse while walking: leave it
			// idle and aim once the player has stopped.
			aim.dropAim(key + ": player moving, mouse idle until arrival");
			host.status("Walking to the " + key);
			return false;
		}
		if (key.equals(aim.lastClickKey) && System.currentTimeMillis() - aim.lastClickAt < POST_CLICK_GRACE_MS)
		{
			// Just clicked it: the walk starts a tick later. Do not re-aim at
			// (and then track) the same target in that gap.
			host.status("Walking to the " + key);
			return false;
		}
		if (!aim.verifyHovered)
		{
			// One move to the centre of the clickable area (the same shape
			// the check uses). A miss on the option only (something covers
			// the centre) tries another point inside it on the next aim.
			aim.aimTarget = aim.coverMiss ? null : object.centre();
			boolean moved = aim.aimTarget != null ? input.moveTo(aim.aimTarget) : object.hover();
			aim.coverMiss = false;
			if (moved)
			{
				aim.verifyHovered = true;
				aim.verifyTarget = object;
			}
			else
			{
				++aim.verifyMisses;
				ActionLog.LOG.info("{} {}: no clickable point on screen (miss {}/{})", log.prefix(), key, aim.verifyMisses,
					HOVER_ATTEMPTS);
				if (aim.verifyMisses >= HOVER_ATTEMPTS)
				{
					aim.verifyMisses = HOVER_ATTEMPTS - 1;
					return recover(object, key);
				}
			}
			host.status("Moving mouse to the " + key);
			return false;
		}
		aim.verifyHovered = false;
		aim.verifyTarget = null;
		input.follow(null);

		MenuEntry entry = input.getLeftClickEntry();
		String option = entry == null ? "" : GameInput.plainText(entry.getOption()).toLowerCase(Locale.ROOT);
		String target = entry == null ? "" : GameInput.plainText(entry.getTarget()).toLowerCase(Locale.ROOT);
		Point mouse = input.getMouseLocation();
		Point center = object.centre();
		Shape hull = object.hull();
		boolean onHull = mouse != null && hull != null && hull.contains(mouse);
		ActionLog.LOG.info("{} aim: target {} -> arrived {}, centre now {}, re-aim={}", log.prefix(), key
			+ " " + (aim.aimTarget == null ? "-" : aim.aimTarget.x + "," + aim.aimTarget.y),
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
				ActionLog.LOG.info("{} {} click: top='{} {}' at {},{} (centre {})", log.prefix(), key, option, target,
					mouse.x, mouse.y, center == null ? "-" : center.x + "," + center.y);
				log.markAction("click " + key);
				aim.verifyMisses = 0;
				aim.verifyKey = null;
				aim.aimTarget = null;
				aim.lastClickKey = key;
				aim.lastClickAt = System.currentTimeMillis();
				return true;
			}
			return false;
		}

		++aim.verifyMisses;
		aim.coverMiss = onHull;
		if (aim.menuDumped.add(key))
		{
			// Once per start and target: the full menu ordering, to check
			// which entry the game treats as the left click.
			ActionLog.LOG.info("{} {} menu order: {}", log.prefix(), key, input.describeMenuOrder());
		}
		ActionLog.LOG.info("{} {} hover miss {}/{}: top='{} {}' onHull={} -> re-aiming", log.prefix(), key,
			aim.verifyMisses, HOVER_ATTEMPTS, option, target, onHull);
		if (aim.verifyMisses < HOVER_ATTEMPTS)
		{
			host.cooldown(0);
			return false;
		}

		return recover(object, key);
	}

	private boolean recover(Aim.Aimable object, String key)
	{
		// Never act invisibly. Turn the camera toward it and walk a step
		// closer (a real tile/minimap click), then re-aim; halt after a few
		// recoveries with a clear log.
		aim.verifyMisses = 0;
		if (++aim.verifyRecoveries > RECOVERIES)
		{
			ActionLog.LOG.info("{} {}: left click never verified after {} recoveries, halting", log.prefix(), key, RECOVERIES);
			host.halt("Stopped: could not click the " + key);
			return false;
		}
		WorldPoint location = object.location();
		if (location == null)
		{
			ActionLog.LOG.info("{} {}: recovery {}/{} - re-aiming", log.prefix(), key, aim.verifyRecoveries, RECOVERIES);
			host.cooldown(1);
			return false;
		}
		ActionLog.LOG.info("{} {}: recovery {}/{} - turning camera and walking closer, then re-aiming", log.prefix(), key,
			aim.verifyRecoveries, RECOVERIES);
		context.getInput().turnCameraToward(location);
		movement.walkTo(movement.onPlayerPlane(location), true, 1);
		host.cooldown(2);
		return false;
	}

}
