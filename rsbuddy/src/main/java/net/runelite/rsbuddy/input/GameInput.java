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
package net.runelite.rsbuddy.input;

import java.awt.Canvas;
import java.awt.Dimension;
import java.awt.Point;
import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.TileObject;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;

/**
 * Sends mouse and keyboard input to the game canvas and invokes menu actions.
 * <p>
 * Clicks are dispatched as AWT events on the canvas, so they go through
 * RuneLite's normal input pipeline (including stretched-mode translation).
 * Mouse movement is animated along a slightly curved path with variable speed,
 * small jitter, and a short press/release delay to look less mechanical.
 */
public final class GameInput
{
	private static final int RANDOM_POINT_ATTEMPTS = 25;
	private static final double TAU = Math.PI * 2.0;
	private static final long CLICK_EFFECT_LIFETIME_MS = 1000L;

	private static final int CAMERA_YAW_UNITS = 16384;
	private static final int CAMERA_HALF_TURN = CAMERA_YAW_UNITS / 2;
	private static final int CAMERA_DEADBAND = 128;
	private static final int CAMERA_HOLD_MS = 800;

	private final Client client;
	private final Random random = new Random();

	private Point lastMousePoint;
	private Timer movementTimer;
	private boolean busy;
	private volatile boolean fastClicking;

	private volatile Point mouseLocation;
	private volatile Point clickLocation;
	private volatile long clickStartedAt;
	private volatile int clickButton = MouseEvent.NOBUTTON;
	private final List<ClickEffect> clickEffects = new CopyOnWriteArrayList<>();

	private volatile int cameraKey = -1;
	private volatile Timer cameraKeyTimer;
	private volatile int cameraPulseYaw;
	private int cameraLeftSign = 1;
	private int cameraRightSign = -1;
	private volatile boolean zooming;

	public GameInput(Client client)
	{
		this.client = client;
	}

	/**
	 * @return true while a simulated mouse movement or click is in progress
	 */
	public boolean isBusy()
	{
		return busy || fastClicking;
	}

	/**
	 * @return the last simulated mouse position in game canvas coordinates
	 */
	public Point getMouseLocation()
	{
		Point location = mouseLocation;
		if (location != null)
		{
			return new Point(location);
		}

		net.runelite.api.Point actual = client.getMouseCanvasPosition();
		return actual == null ? null : new Point(actual.getX(), actual.getY());
	}

	/**
	 * @return the position of the most recent simulated click in game canvas
	 * coordinates, or null if nothing has been clicked yet
	 */
	public Point getClickLocation()
	{
		Point location = clickLocation;
		return location == null ? null : new Point(location);
	}

	/**
	 * @return the wall-clock time at which the most recent click was pressed
	 */
	public long getClickStartedAt()
	{
		return clickStartedAt;
	}

	/**
	 * @return the mouse button of the most recent click
	 */
	public int getClickButton()
	{
		return clickButton;
	}

	/**
	 * @return recent simulated clicks, oldest first
	 */
	public List<ClickEffect> getRecentClicks()
	{
		long cutoff = System.currentTimeMillis() - CLICK_EFFECT_LIFETIME_MS;
		clickEffects.removeIf(effect -> effect.startedAt < cutoff);
		return new ArrayList<>(clickEffects);
	}

	public boolean click(Point gamePoint)
	{
		return click(gamePoint, MouseEvent.BUTTON1);
	}

	public boolean click(Shape shape)
	{
		Point point = randomPoint(shape);
		return point != null && click(point);
	}

	public boolean click(Rectangle bounds)
	{
		Point point = randomPoint(bounds);
		return point != null && click(point);
	}

	public boolean click(TileObject object)
	{
		return click(object, MouseEvent.BUTTON1);
	}

	public boolean click(TileObject object, int button)
	{
		if (object == null)
		{
			return false;
		}

		Shape clickbox = object.getClickbox();
		if (clickbox != null)
		{
			Point point = randomPoint(clickbox);
			return point != null && click(point, button);
		}

		Polygon tilePoly = object.getCanvasTilePoly();
		if (tilePoly != null)
		{
			Point point = randomPoint(tilePoly);
			return point != null && click(point, button);
		}

		net.runelite.api.Point canvasLocation = object.getCanvasLocation();
		return canvasLocation != null
			&& click(new Point(canvasLocation.getX(), canvasLocation.getY()), button);
	}

	public boolean click(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}

		Shape hull = npc.getConvexHull();
		if (hull != null)
		{
			return click(hull);
		}

		Polygon tilePoly = npc.getCanvasTilePoly();
		return tilePoly != null && click(tilePoly);
	}

	public boolean click(Widget widget)
	{
		return click(widget, MouseEvent.BUTTON1);
	}

	public boolean click(Widget widget, int button)
	{
		if (widget == null || widget.isHidden())
		{
			return false;
		}

		Rectangle bounds = widget.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return false;
		}

		Point point = randomPoint(bounds);
		return point != null && click(point, button);
	}

	/**
	 * Moves the mouse onto an NPC without clicking it, using the same curved
	 * path as a click.
	 *
	 * @return true if a movement was started
	 */
	public boolean hover(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}

		Shape hull = npc.getConvexHull();
		if (hull != null)
		{
			return hover(hull);
		}

		Polygon tilePoly = npc.getCanvasTilePoly();
		return tilePoly != null && hover(tilePoly);
	}

	/**
	 * Moves the mouse onto a tile object without clicking it, using the same
	 * curved path as a click.
	 *
	 * @return true if a movement was started
	 */
	public boolean hover(TileObject object)
	{
		if (object == null)
		{
			return false;
		}

		Shape clickbox = object.getClickbox();
		if (clickbox != null)
		{
			return hover(clickbox);
		}

		Polygon tilePoly = object.getCanvasTilePoly();
		if (tilePoly != null)
		{
			return hover(tilePoly);
		}

		net.runelite.api.Point canvasLocation = object.getCanvasLocation();
		return canvasLocation != null
			&& moveTo(new Point(canvasLocation.getX(), canvasLocation.getY()));
	}

	/**
	 * Moves the mouse to a random point inside the shape without clicking.
	 *
	 * @return true if a movement was started
	 */
	public boolean hover(Shape shape)
	{
		Point point = randomPoint(shape);
		return point != null && moveTo(point);
	}

	/**
	 * Animates the mouse to a point in game canvas coordinates without
	 * pressing a button.
	 *
	 * @return true if a movement was started
	 */
	public boolean moveTo(Point gamePoint)
	{
		if (gamePoint == null)
		{
			return false;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		Point canvasPoint = toCanvasPoint(gamePoint);
		if (!canvas.contains(canvasPoint))
		{
			return false;
		}

		busy = true;
		SwingUtilities.invokeLater(() -> beginMove(canvas, canvasPoint));
		return true;
	}

	/**
	 * @return true if the simulated mouse is currently inside the shape
	 */
	public boolean isMouseOver(Shape shape)
	{
		if (shape == null)
		{
			return false;
		}

		Point location = getMouseLocation();
		return location != null && shape.contains(location);
	}

	/**
	 * @return true if the simulated mouse is currently over the NPC's model
	 */
	public boolean isMouseOver(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}

		Shape hull = npc.getConvexHull();
		if (hull != null && isMouseOver(hull))
		{
			return true;
		}

		Polygon tilePoly = npc.getCanvasTilePoly();
		return tilePoly != null && isMouseOver(tilePoly);
	}

	/**
	 * @return true if the simulated mouse is currently over the object
	 */
	public boolean isMouseOver(TileObject object)
	{
		if (object == null)
		{
			return false;
		}

		Shape clickbox = object.getClickbox();
		if (clickbox != null && isMouseOver(clickbox))
		{
			return true;
		}

		Polygon tilePoly = object.getCanvasTilePoly();
		return tilePoly != null && isMouseOver(tilePoly);
	}

	/**
	 * Clicks several widgets in quick succession without the animated mouse
	 * path. This is intended for rapid inventory actions such as
	 * power-dropping fish. The clicks are sequenced so each one lands after the
	 * client has processed the move to its target.
	 *
	 * @return the number of clicks scheduled
	 */
	public int fastClick(List<Widget> widgets)
	{
		if (widgets == null || widgets.isEmpty())
		{
			return 0;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return 0;
		}

		List<Point> gamePoints = new ArrayList<>();
		List<Point> canvasPoints = new ArrayList<>();

		for (Widget widget : widgets)
		{
			if (widget == null || widget.isHidden())
			{
				continue;
			}

			Rectangle bounds = widget.getBounds();
			if (bounds == null || bounds.isEmpty())
			{
				continue;
			}

			Point point = randomPoint(bounds);
			if (point == null)
			{
				continue;
			}

			Point canvasPoint = toCanvasPoint(point);
			if (!canvas.contains(canvasPoint))
			{
				continue;
			}

			gamePoints.add(point);
			canvasPoints.add(canvasPoint);
		}

		if (gamePoints.isEmpty())
		{
			return 0;
		}

		stopCamera();
		final List<Point> targets = gamePoints;
		final List<Point> canvasTargets = canvasPoints;
		fastClicking = true;
		SwingUtilities.invokeLater(() -> fastClickNext(canvas, targets, canvasTargets, 0));
		return targets.size();
	}

	public void rightClick(Point gamePoint)
	{
		click(gamePoint, MouseEvent.BUTTON3);
	}

	public void rightClick(NPC npc)
	{
		if (npc == null)
		{
			return;
		}

		Shape hull = npc.getConvexHull();
		Point point = hull != null ? randomPoint(hull) : null;
		if (point != null)
		{
			rightClick(point);
		}
	}

	/**
	 * @return true if the NPC's model is currently inside the game viewport
	 */
	public boolean isVisible(NPC npc)
	{
		if (npc == null)
		{
			return false;
		}

		Shape hull = npc.getConvexHull();
		if (hull != null && isInViewport(hull))
		{
			return true;
		}

		Polygon tilePoly = npc.getCanvasTilePoly();
		return tilePoly != null && isInViewport(tilePoly);
	}

	/**
	 * @return true if the object is currently inside the game viewport
	 */
	public boolean isVisible(TileObject object)
	{
		if (object == null)
		{
			return false;
		}

		Shape clickbox = object.getClickbox();
		if (clickbox != null && isInViewport(clickbox))
		{
			return true;
		}

		Polygon tilePoly = object.getCanvasTilePoly();
		return tilePoly != null && isInViewport(tilePoly);
	}

	private boolean isInViewport(Shape shape)
	{
		if (shape == null)
		{
			return false;
		}

		int width = client.getViewportWidth();
		int height = client.getViewportHeight();
		if (width <= 0 || height <= 0)
		{
			return true;
		}

		// Do not use Shape.intersects here. RuneLite's SimplePolygon only
		// reports an intersection when the viewport edges cross the polygon,
		// which is false for a target that is fully inside the viewport.
		Rectangle bounds = shape.getBounds();
		if (bounds == null)
		{
			return false;
		}

		Rectangle viewport = new Rectangle(
			client.getViewportXOffset(), client.getViewportYOffset(), width, height);
		if (bounds.width <= 0 || bounds.height <= 0)
		{
			return viewport.contains(bounds.getLocation());
		}

		return viewport.intersects(bounds);
	}

	private boolean isInViewport(int x, int y)
	{
		int width = client.getViewportWidth();
		int height = client.getViewportHeight();
		if (width <= 0 || height <= 0)
		{
			return true;
		}

		int viewportX = client.getViewportXOffset();
		int viewportY = client.getViewportYOffset();
		return x >= viewportX && x < viewportX + width
			&& y >= viewportY && y < viewportY + height;
	}

	/**
	 * @return true while an arrow key is being held to rotate the camera
	 */
	public boolean isCameraBusy()
	{
		return cameraKey != -1;
	}

	/**
	 * @return true while the mouse wheel is being scrolled to zoom the camera
	 */
	public boolean isZooming()
	{
		return zooming;
	}

	/**
	 * Scrolls the mouse wheel down to zoom the camera out, one notch at a
	 * time with a small human-like delay between notches.
	 *
	 * @param notches number of wheel notches to scroll
	 * @return true if zooming was started
	 */
	public boolean zoomOut(int notches)
	{
		if (notches <= 0)
		{
			return false;
		}

		if (zooming)
		{
			return true;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		Dimension size = canvas.getSize();
		if (size.width <= 0 || size.height <= 0)
		{
			return false;
		}

		final int x = size.width / 2;
		final int y = size.height / 2;
		zooming = true;

		SwingUtilities.invokeLater(() ->
		{
			canvas.requestFocusInWindow();
			zoomStep(canvas, x, y, notches);
		});

		return true;
	}

	private void zoomStep(Canvas canvas, int x, int y, int remaining)
	{
		if (remaining <= 0)
		{
			zooming = false;
			return;
		}

		canvas.dispatchEvent(new MouseWheelEvent(canvas, MouseEvent.MOUSE_WHEEL,
			System.currentTimeMillis(), 0, x, y, 0, false, MouseWheelEvent.WHEEL_UNIT_SCROLL, 3, 1));

		Timer timer = new Timer(80 + random.nextInt(70), event -> zoomStep(canvas, x, y, remaining - 1));
		timer.setRepeats(false);
		timer.start();
	}

	/**
	 * Starts turning the camera toward a world position by holding the
	 * appropriate arrow key, exactly as a player would. The key is released
	 * automatically after a short hold, or immediately with
	 * {@link #stopCamera()}.
	 *
	 * @return true if the camera is already turning or a turn was started,
	 * false if the camera already faces the position
	 */
	public boolean turnCameraToward(WorldPoint worldPoint)
	{
		if (cameraKey != -1)
		{
			return true;
		}

		Player player = client.getLocalPlayer();
		if (player == null || worldPoint == null)
		{
			return false;
		}

		WorldPoint origin = player.getWorldLocation();
		if (origin == null)
		{
			return false;
		}

		int dx = worldPoint.getX() - origin.getX();
		int dy = worldPoint.getY() - origin.getY();
		if (dx == 0 && dy == 0)
		{
			return false;
		}

		int desired = (int) Math.round(Math.atan2(-dx, dy) * CAMERA_YAW_UNITS / TAU)
			& (CAMERA_YAW_UNITS - 1);
		return turnCameraToYaw(desired);
	}

	/**
	 * Starts turning the camera toward an absolute yaw by holding the
	 * appropriate arrow key, using the direction learned from earlier pulses.
	 *
	 * @param desiredYaw target yaw in JAU14 units
	 * @return true if the camera is already turning or a turn was started,
	 * false if the camera already faces the target yaw
	 */
	public boolean turnCameraToYaw(int desiredYaw)
	{
		if (cameraKey != -1)
		{
			return true;
		}

		int desired = desiredYaw & (CAMERA_YAW_UNITS - 1);
		int current = client.getCameraYawTarget() & (CAMERA_YAW_UNITS - 1);
		int delta = normalizeYaw(desired - current);
		if (Math.abs(delta) <= CAMERA_DEADBAND)
		{
			return false;
		}

		boolean useLeft = delta > 0 ? cameraLeftSign > 0 : cameraLeftSign < 0;
		cameraPulseYaw = current;
		return holdKey(useLeft ? KeyEvent.VK_LEFT : KeyEvent.VK_RIGHT, CAMERA_HOLD_MS);
	}

	/**
	 * Releases a held camera arrow key, if any.
	 */
	public void stopCamera()
	{
		int keyCode = cameraKey;
		if (keyCode == -1)
		{
			return;
		}

		cameraKey = -1;
		Timer timer = cameraKeyTimer;
		cameraKeyTimer = null;
		if (timer != null)
		{
			timer.stop();
		}

		finishCameraPulse(keyCode);

		Canvas canvas = client.getCanvas();
		if (canvas != null)
		{
			SwingUtilities.invokeLater(() -> dispatchKey(canvas, KeyEvent.KEY_RELEASED, keyCode));
		}
	}

	/**
	 * Holds a key down for the given time and releases it afterwards.
	 */
	public boolean holdKey(int keyCode, int holdMillis)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		if (cameraKey == keyCode)
		{
			return true;
		}

		stopCamera();
		cameraKey = keyCode;
		final int duration = Math.max(50, holdMillis);

		SwingUtilities.invokeLater(() ->
		{
			if (cameraKey != keyCode)
			{
				return;
			}

			canvas.requestFocusInWindow();
			dispatchKey(canvas, KeyEvent.KEY_PRESSED, keyCode);

			Timer timer = new Timer(duration, event -> releaseCameraKey(keyCode));
			timer.setRepeats(false);
			cameraKeyTimer = timer;
			timer.start();
		});

		return true;
	}

	/**
	 * Sends a key press/release pair to the game canvas.
	 */
	public boolean pressKey(int keyCode)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		SwingUtilities.invokeLater(() ->
		{
			canvas.requestFocusInWindow();

			long when = System.currentTimeMillis();
			dispatchKey(canvas, KeyEvent.KEY_PRESSED, keyCode);

			char keyChar = keyCode == KeyEvent.VK_SPACE ? ' ' : KeyEvent.CHAR_UNDEFINED;
			if (keyChar != KeyEvent.CHAR_UNDEFINED)
			{
				canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_TYPED, when, 0,
					KeyEvent.VK_UNDEFINED, keyChar));
			}

			canvas.dispatchEvent(new KeyEvent(canvas, KeyEvent.KEY_RELEASED, when + 45, 0, keyCode, keyChar));
		});

		return true;
	}

	/**
	 * Learns which way each arrow key turns the camera from the actual yaw
	 * change, so the mapping does not have to be assumed.
	 */
	private void finishCameraPulse(int keyCode)
	{
		int change = normalizeYaw(
			(client.getCameraYawTarget() & (CAMERA_YAW_UNITS - 1)) - cameraPulseYaw);
		if (Math.abs(change) < 8)
		{
			return;
		}

		int sign = change > 0 ? 1 : -1;
		if (keyCode == KeyEvent.VK_LEFT)
		{
			cameraLeftSign = sign;
			cameraRightSign = -sign;
		}
		else if (keyCode == KeyEvent.VK_RIGHT)
		{
			cameraRightSign = sign;
			cameraLeftSign = -sign;
		}
	}

	private void releaseCameraKey(int keyCode)
	{
		if (cameraKey != keyCode)
		{
			return;
		}

		cameraKey = -1;
		cameraKeyTimer = null;
		finishCameraPulse(keyCode);

		Canvas canvas = client.getCanvas();
		if (canvas != null)
		{
			dispatchKey(canvas, KeyEvent.KEY_RELEASED, keyCode);
		}
	}

	private static void dispatchKey(Canvas canvas, int id, int keyCode)
	{
		char keyChar = keyCode == KeyEvent.VK_SPACE ? ' ' : KeyEvent.CHAR_UNDEFINED;
		canvas.dispatchEvent(new KeyEvent(canvas, id, System.currentTimeMillis(),
			0, keyCode, keyChar));
	}

	private static int normalizeYaw(int yaw)
	{
		int normalized = yaw & (CAMERA_YAW_UNITS - 1);
		return normalized >= CAMERA_HALF_TURN ? normalized - CAMERA_YAW_UNITS : normalized;
	}

	public boolean pressSpace()
	{
		return pressKey(KeyEvent.VK_SPACE);
	}

	/**
	 * Interacts with a tile object by opening its right-click menu and then
	 * clicking the matching option in that menu. Using the real context menu
	 * avoids relying on synthesised menu actions, which the client does not
	 * always resolve for tile objects.
	 */
	public boolean interact(TileObject object, String... actions)
	{
		if (object == null || actions == null || actions.length == 0)
		{
			return false;
		}

		if (client.isMenuOpen())
		{
			MenuEntry entry = findObjectMenuEntry(object, actions);
			if (entry != null)
			{
				return clickMenuEntry(entry);
			}

			// The menu is open for something else or does not contain the
			// requested option. Close it so the next attempt can start over.
			closeMenu();
			return false;
		}

		// Right-click the object to open its menu. The next tick picks the
		// matching option from the menu.
		return click(object, MouseEvent.BUTTON3);
	}

	private MenuEntry findObjectMenuEntry(TileObject object, String... actions)
	{
		net.runelite.api.Menu menu = client.getMenu();
		MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
		if (entries == null)
		{
			return null;
		}

		ObjectComposition composition = client.getObjectDefinition(object.getId());
		String name = composition == null ? null : composition.getName();
		int objectId = object.getId() & 0xFFFF;
		MenuEntry optionOnly = null;
		boolean multipleOptions = false;

		for (MenuEntry entry : entries)
		{
			if (entry == null || !matchesOption(entry.getOption(), actions))
			{
				continue;
			}

			if ((entry.getIdentifier() & 0xFFFF) == objectId)
			{
				return entry;
			}

			String target = entry.getTarget();
			if (target != null && name != null && target.equalsIgnoreCase(name))
			{
				return entry;
			}

			if (optionOnly == null)
			{
				optionOnly = entry;
			}
			else if (optionOnly != entry)
			{
				multipleOptions = true;
			}
		}

		// Some wall objects use a packed identifier rather than the object
		// id. If there is exactly one entry with the wanted option, use it.
		return multipleOptions ? null : optionOnly;
	}

	public boolean interact(NPC npc, String action)
	{
		if (npc == null)
		{
			return false;
		}

		NPCComposition composition = npc.getComposition();
		if (composition == null)
		{
			return false;
		}

		int actionIndex = findAction(composition.getActions(), action);
		if (actionIndex < 0)
		{
			return false;
		}

		// The first option is what a normal left click selects. Using a real
		// click makes the cursor visibly travel to the NPC instead of the
		// action appearing out of nowhere.
		if (actionIndex == 0)
		{
			return click(npc);
		}

		// Other options need a right-click menu in game. Move onto the NPC
		// like a player would, then invoke the exact option. Hovering keeps
		// the pointer movement visible while avoiding a menu left open
		// across ticks.
		hover(npc);
		client.menuAction(npc.getIndex(), 0, npcMenuAction(actionIndex), npc.getIndex(), -1, action, npc.getName());
		return true;
	}

	public boolean interact(Widget widget, String action)
	{
		if (widget == null || widget.isHidden())
		{
			return false;
		}

		int actionIndex = findAction(widget.getActions(), action);
		if (actionIndex < 0)
		{
			return false;
		}

		client.menuAction(-1, widget.getId(), MenuAction.CC_OP, actionIndex + 1, -1, action, "");
		return true;
	}

	/**
	 * Finds the visible bank widget for an item in the main bank tab.
	 */
	public Widget findBankItemWidget(int itemId)
	{
		Widget items = client.getWidget(InterfaceID.Bankmain.ITEMS);
		if (items == null)
		{
			return null;
		}

		Widget[] children = items.getChildren();
		if (children == null)
		{
			return null;
		}

		for (Widget child : children)
		{
			if (child == null || child.isSelfHidden() || child.getItemId() != itemId)
			{
				continue;
			}

			return child;
		}

		return null;
	}

	/**
	 * Withdraws an item from the bank by right-clicking its bank slot and
	 * clicking the requested withdraw option in the real context menu.
	 */
	public boolean withdrawBankItem(int itemId, String withdrawOption)
	{
		if (client.isMenuOpen())
		{
			MenuEntry entry = findBankMenuEntry(itemId, withdrawOption);
			if (entry != null)
			{
				return clickMenuEntry(entry);
			}

			closeMenu();
			return false;
		}

		Widget widget = findBankItemWidget(itemId);
		return widget != null && click(widget, MouseEvent.BUTTON3);
	}

	private MenuEntry findBankMenuEntry(int itemId, String withdrawOption)
	{
		net.runelite.api.Menu menu = client.getMenu();
		MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
		if (entries == null)
		{
			return null;
		}

		MenuEntry optionMatch = null;
		for (MenuEntry entry : entries)
		{
			if (entry == null
				|| (entry.getType() != MenuAction.CC_OP
					&& entry.getType() != MenuAction.CC_OP_LOW_PRIORITY)
				|| !matchesOption(entry.getOption(), withdrawOption))
			{
				continue;
			}

			// Bank item entries carry the item id; prefer the entry that
			// belongs to the slot we right-clicked.
			if (entry.getItemId() == itemId
				&& entry.getParam1() == InterfaceID.Bankmain.ITEMS)
			{
				return entry;
			}

			if (optionMatch == null)
			{
				optionMatch = entry;
			}
		}

		return optionMatch;
	}

	/**
	 * Finds the Take entry for a ground item in the currently open menu.
	 */
	private MenuEntry findGroundItemMenuEntry(int itemId, int sceneX, int sceneY)
	{
		net.runelite.api.Menu menu = client.getMenu();
		MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
		if (entries == null)
		{
			return null;
		}

		int id = itemId & 0xFFFF;
		MenuEntry fallback = null;

		for (MenuEntry entry : entries)
		{
			if (entry == null || !isGroundItemAction(entry.getType())
				|| !matchesOption(entry.getOption(), "Take", "Pick up", "Pickup")
				|| (entry.getIdentifier() & 0xFFFF) != id)
			{
				continue;
			}

			// The menu only contains items from the tile that was
			// right-clicked, but prefer an entry with matching coordinates.
			if (entry.getParam0() == sceneX && entry.getParam1() == sceneY)
			{
				return entry;
			}

			if (fallback == null)
			{
				fallback = entry;
			}
		}

		return fallback;
	}

	private static boolean isGroundItemAction(MenuAction type)
	{
		if (type == null)
		{
			return false;
		}

		int id = type.getId();
		return id >= MenuAction.GROUND_ITEM_FIRST_OPTION.getId()
			&& id <= MenuAction.GROUND_ITEM_FIFTH_OPTION.getId();
	}

	private static boolean matchesOption(String option, String... actions)
	{
		if (option == null)
		{
			return false;
		}

		for (String action : actions)
		{
			if (action != null && option.trim().equalsIgnoreCase(action.trim()))
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Clicks a row in the client's currently open context menu. The client
	 * lays the menu out with a 19px header and 15px rows, with the array's
	 * last entry at the top.
	 */
	public boolean clickMenuEntry(MenuEntry entry)
	{
		if (entry == null)
		{
			return false;
		}

		net.runelite.api.Menu menu = client.getMenu();
		MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
		if (!client.isMenuOpen() || entries == null || entries.length == 0)
		{
			return invokeMenuEntry(entry);
		}

		int index = -1;
		for (int i = 0; i < entries.length; i++)
		{
			if (entries[i] == entry)
			{
				index = i;
				break;
			}
		}

		if (index < 0 || client.isMenuScrollable())
		{
			return invokeMenuEntry(entry);
		}

		int row = entries.length - 1 - index;
		int x = menu.getMenuX() + Math.max(8, menu.getMenuWidth() / 2);
		int y = menu.getMenuY() + 19 + row * 15 + 7;
		return clickMenuPoint(new Point(x, y));
	}

	private boolean invokeMenuEntry(MenuEntry entry)
	{
		client.menuAction(entry.getParam0(), entry.getParam1(), entry.getType(),
			entry.getIdentifier(), entry.getItemId(), entry.getOption(), entry.getTarget());
		return true;
	}

	/**
	 * Closes the currently open right-click menu, if any.
	 */
	public boolean closeMenu()
	{
		if (!client.isMenuOpen())
		{
			return false;
		}

		return pressKey(KeyEvent.VK_ESCAPE);
	}

	/**
	 * Picks up a ground item by right-clicking its tile and then clicking the
	 * Take entry in the context menu.
	 */
	public boolean takeGroundItem(int sceneX, int sceneY, int itemId, String itemName)
	{
		if (client.isMenuOpen())
		{
			MenuEntry entry = findGroundItemMenuEntry(itemId, sceneX, sceneY);
			if (entry != null)
			{
				return clickMenuEntry(entry);
			}

			closeMenu();
			return false;
		}

		LocalPoint localPoint = LocalPoint.fromScene(sceneX, sceneY, client.getTopLevelWorldView());
		if (localPoint == null)
		{
			return false;
		}

		Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
		Point point = randomPoint(tilePoly);
		return point != null && click(point, MouseEvent.BUTTON3);
	}

	public boolean walkTo(WorldPoint worldPoint)
	{
		if (worldPoint == null)
		{
			return false;
		}

		LocalPoint localPoint = LocalPoint.fromWorld(client, worldPoint);
		if (localPoint == null)
		{
			return false;
		}

		Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
		return tilePoly != null && click(tilePoly);
	}

	/**
	 * Clicks a point inside the already-open context menu. The cursor is
	 * moved there in a single event, because animated movement across the
	 * game world can close the menu before the click lands.
	 */
	private boolean clickMenuPoint(Point gamePoint)
	{
		if (gamePoint == null)
		{
			return false;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		Point canvasPoint = toCanvasPoint(gamePoint);
		if (!canvas.contains(canvasPoint))
		{
			return false;
		}

		stopCamera();
		stopMovement();
		busy = true;
		SwingUtilities.invokeLater(() ->
		{
			canvas.requestFocusInWindow();
			lastMousePoint = canvasPoint;
			mouseLocation = new Point(gamePoint);
			dispatch(canvas, MouseEvent.MOUSE_MOVED, canvasPoint, MouseEvent.NOBUTTON, 0, 0);

			Timer timer = new Timer(45 + random.nextInt(45), event ->
			{
				long now = System.currentTimeMillis();
				clickLocation = new Point(gamePoint);
				clickStartedAt = now;
				clickButton = MouseEvent.BUTTON1;
				addClickEffect(new Point(gamePoint), MouseEvent.BUTTON1, now);

				dispatch(canvas, MouseEvent.MOUSE_PRESSED, canvasPoint, MouseEvent.BUTTON1, 1,
					InputEvent.BUTTON1_DOWN_MASK);
				dispatch(canvas, MouseEvent.MOUSE_RELEASED, canvasPoint, MouseEvent.BUTTON1, 1, 0);
				dispatch(canvas, MouseEvent.MOUSE_CLICKED, canvasPoint, MouseEvent.BUTTON1, 1, 0);
				busy = false;
			});
			timer.setRepeats(false);
			timer.start();
		});
		return true;
	}

	private boolean click(Point gamePoint, int button)
	{
		if (gamePoint == null)
		{
			return false;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		Point canvasPoint = toCanvasPoint(gamePoint);
		if (!canvas.contains(canvasPoint))
		{
			return false;
		}

		stopCamera();
		busy = true;
		SwingUtilities.invokeLater(() -> beginClick(canvas, canvasPoint, button));
		return true;
	}

	private void fastClickNext(Canvas canvas, List<Point> gamePoints, List<Point> canvasPoints, int index)
	{
		if (index >= gamePoints.size())
		{
			fastClicking = false;
			return;
		}

		Point gamePoint = gamePoints.get(index);
		Point canvasPoint = canvasPoints.get(index);

		canvas.requestFocusInWindow();
		lastMousePoint = canvasPoint;
		mouseLocation = new Point(gamePoint);
		dispatch(canvas, MouseEvent.MOUSE_MOVED, canvasPoint, MouseEvent.NOBUTTON, 0, 0);

		// Give the client one frame to update its hover target before
		// pressing, then immediately move on to the next click.
		Timer timer = new Timer(15 + random.nextInt(20), event ->
		{
			long now = System.currentTimeMillis();
			clickLocation = new Point(gamePoint);
			clickStartedAt = now;
			clickButton = MouseEvent.BUTTON1;
			addClickEffect(gamePoint, MouseEvent.BUTTON1, now);

			dispatch(canvas, MouseEvent.MOUSE_PRESSED, canvasPoint, MouseEvent.BUTTON1, 1,
				InputEvent.BUTTON1_DOWN_MASK);
			dispatch(canvas, MouseEvent.MOUSE_RELEASED, canvasPoint, MouseEvent.BUTTON1, 1, 0);
			dispatch(canvas, MouseEvent.MOUSE_CLICKED, canvasPoint, MouseEvent.BUTTON1, 1, 0);

			fastClickNext(canvas, gamePoints, canvasPoints, index + 1);
		});
		timer.setRepeats(false);
		timer.start();
	}

	private void addClickEffect(Point gamePoint, int button, long startedAt)
	{
		clickEffects.add(new ClickEffect(new Point(gamePoint), button, startedAt));

		long cutoff = System.currentTimeMillis() - CLICK_EFFECT_LIFETIME_MS;
		clickEffects.removeIf(effect -> effect.startedAt < cutoff);
	}

	private void beginClick(Canvas canvas, Point target, int button)
	{
		Point start = beginMovement(canvas);
		if (start == null)
		{
			busy = false;
			return;
		}

		List<MouseStep> steps = buildMoveSteps(start, target);
		appendClickSteps(steps, target, button);
		playSteps(canvas, steps);
	}

	private void beginMove(Canvas canvas, Point target)
	{
		Point start = beginMovement(canvas);
		if (start == null)
		{
			busy = false;
			return;
		}

		playSteps(canvas, buildMoveSteps(start, target));
	}

	/**
	 * Sets up the starting point for a movement and updates the simulated
	 * mouse position to it. Must be called on the Swing event thread.
	 *
	 * @return the canvas point the movement starts from, or null if the canvas
	 * has no usable size
	 */
	private Point beginMovement(Canvas canvas)
	{
		stopMovement();

		Point start = lastMousePoint;
		if (start == null || !canvas.contains(start))
		{
			Dimension size = canvas.getSize();
			if (size.width <= 0 || size.height <= 0)
			{
				return null;
			}

			start = new Point(
				size.width / 2 + random.nextInt(Math.max(1, size.width / 6)) - size.width / 12,
				size.height / 2 + random.nextInt(Math.max(1, size.height / 6)) - size.height / 12);
			lastMousePoint = start;
		}

		canvas.requestFocusInWindow();
		mouseLocation = toGamePoint(start);
		return start;
	}

	private void playSteps(Canvas canvas, List<MouseStep> steps)
	{
		final long startedAt = System.nanoTime();
		final int[] index = {0};

		Timer timer = new Timer(5, null);
		timer.addActionListener(event ->
		{
			long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L;

			while (index[0] < steps.size() && elapsedMs >= steps.get(index[0]).delayMs)
			{
				MouseStep step = steps.get(index[0]++);
				dispatch(canvas, step.id, step.point, step.button, step.clickCount, step.modifiers);
				mouseLocation = toGamePoint(step.point);

				if (step.id == MouseEvent.MOUSE_MOVED)
				{
					lastMousePoint = step.point;
				}
				else if (step.id == MouseEvent.MOUSE_PRESSED)
				{
					clickLocation = toGamePoint(step.point);
					clickStartedAt = System.currentTimeMillis();
					clickButton = step.button;
					addClickEffect(clickLocation, clickButton, clickStartedAt);
				}
			}

			if (index[0] >= steps.size())
			{
				timer.stop();
				if (movementTimer == timer)
				{
					movementTimer = null;
					busy = false;
				}
			}
		});

		movementTimer = timer;
		timer.start();
	}

	private List<MouseStep> buildMoveSteps(Point start, Point target)
	{
		List<MouseStep> steps = new ArrayList<>();

		double dx = target.x - start.x;
		double dy = target.y - start.y;
		double distance = Math.hypot(dx, dy);

		if (distance < 2.0)
		{
			steps.add(new MouseStep(MouseEvent.MOUSE_MOVED, target, MouseEvent.NOBUTTON, 0, 0, 0));
		}
		else
		{
			int moveSteps = Math.max(12, Math.min(50, (int) (distance / 11.0) + 9));
			double length = Math.max(1.0, distance);
			double perpX = -dy / length;
			double perpY = dx / length;

			// A mild curve makes straight-line teleporting look less robotic.
			double bend = (random.nextDouble() * 2.0 - 1.0) * Math.min(70.0, distance * 0.11);
			double control1X = start.x + dx * 0.32 + perpX * bend;
			double control1Y = start.y + dy * 0.32 + perpY * bend;
			double control2X = start.x + dx * 0.68 + perpX * bend * 0.55;
			double control2Y = start.y + dy * 0.68 + perpY * bend * 0.55;

			long delay = 0;
			for (int i = 1; i <= moveSteps; i++)
			{
				double progress = (double) i / moveSteps;
				double eased = 1.0 - Math.pow(1.0 - progress, 2.2);

				if (i < moveSteps && random.nextInt(6) == 0)
				{
					eased = Math.min(1.0, Math.max(0.0, eased + (random.nextDouble() - 0.5) * 0.035));
				}

				double x = cubic(start.x, control1X, control2X, target.x, eased);
				double y = cubic(start.y, control1Y, control2Y, target.y, eased);

				if (i < moveSteps)
				{
					double jitter = (1.0 - progress) * Math.min(5.0, Math.max(1.0, distance * 0.012));
					x += (random.nextDouble() * 2.0 - 1.0) * jitter;
					y += (random.nextDouble() * 2.0 - 1.0) * jitter;
				}
				else
				{
					x = target.x;
					y = target.y;
				}

				delay += 12 + random.nextInt(10);
				if (i < moveSteps && random.nextInt(12) == 0)
				{
					delay += 25 + random.nextInt(50);
				}

				steps.add(new MouseStep(MouseEvent.MOUSE_MOVED,
					new Point((int) Math.round(x), (int) Math.round(y)),
					MouseEvent.NOBUTTON, 0, 0, delay));
			}
		}

		return steps;
	}

	private void appendClickSteps(List<MouseStep> steps, Point target, int button)
	{
		long pressDelay = steps.get(steps.size() - 1).delayMs + 30 + random.nextInt(70);
		int downMask = button == MouseEvent.BUTTON3
			? InputEvent.BUTTON3_DOWN_MASK
			: InputEvent.BUTTON1_DOWN_MASK;

		steps.add(new MouseStep(MouseEvent.MOUSE_PRESSED, target, button, 1, downMask, pressDelay));
		long releaseDelay = pressDelay + 45 + random.nextInt(75);
		steps.add(new MouseStep(MouseEvent.MOUSE_RELEASED, target, button, 1, 0, releaseDelay));
		steps.add(new MouseStep(MouseEvent.MOUSE_CLICKED, target, button, 1, 0,
			releaseDelay + 15 + random.nextInt(35)));
	}

	private static double cubic(double a, double b, double c, double d, double t)
	{
		double u = 1.0 - t;
		return u * u * u * a + 3.0 * u * u * t * b + 3.0 * u * t * t * c + t * t * t * d;
	}

	private void stopMovement()
	{
		if (movementTimer != null)
		{
			movementTimer.stop();
			movementTimer = null;
		}
	}

	private void dispatch(Canvas canvas, int id, Point point, int button, int clickCount, int modifiers)
	{
		MouseEvent event = new MouseEvent(canvas, id, System.currentTimeMillis(), modifiers,
			point.x, point.y, clickCount, false, button);
		canvas.dispatchEvent(event);
	}

	private Point toCanvasPoint(Point gamePoint)
	{
		if (!client.isStretchedEnabled())
		{
			return gamePoint;
		}

		Dimension realDimensions = client.getRealDimensions();
		Dimension stretchedDimensions = client.getStretchedDimensions();
		if (realDimensions == null || stretchedDimensions == null
			|| realDimensions.width <= 0 || realDimensions.height <= 0)
		{
			return gamePoint;
		}

		return new Point(
			gamePoint.x * stretchedDimensions.width / realDimensions.width,
			gamePoint.y * stretchedDimensions.height / realDimensions.height);
	}

	private Point toGamePoint(Point canvasPoint)
	{
		if (!client.isStretchedEnabled())
		{
			return new Point(canvasPoint);
		}

		Dimension realDimensions = client.getRealDimensions();
		Dimension stretchedDimensions = client.getStretchedDimensions();
		if (realDimensions == null || stretchedDimensions == null
			|| stretchedDimensions.width <= 0 || stretchedDimensions.height <= 0)
		{
			return new Point(canvasPoint);
		}

		return new Point(
			canvasPoint.x * realDimensions.width / stretchedDimensions.width,
			canvasPoint.y * realDimensions.height / stretchedDimensions.height);
	}

	private Point randomPoint(Shape shape)
	{
		if (shape == null)
		{
			return null;
		}

		Rectangle bounds = shape.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		double centerX = bounds.getCenterX();
		double centerY = bounds.getCenterY();

		for (int i = 0; i < RANDOM_POINT_ATTEMPTS; i++)
		{
			int x = (int) Math.round(centerX + (random.nextDouble() * 2.0 - 1.0) * bounds.width * 0.36);
			int y = (int) Math.round(centerY + (random.nextDouble() * 2.0 - 1.0) * bounds.height * 0.36);
			if (shape.contains(x, y) && isInViewport(x, y) && !isCoveredByInterface(x, y))
			{
				return new Point(x, y);
			}
		}

		int x = (int) Math.round(centerX);
		int y = (int) Math.round(centerY);
		return shape.contains(x, y) && isInViewport(x, y) && !isCoveredByInterface(x, y)
			? new Point(x, y)
			: null;
	}

	/**
	 * @return true if the inventory or chat interface covers the given canvas
	 * point, so a click there would hit the interface instead of the game
	 * world
	 */
	private boolean isCoveredByInterface(int x, int y)
	{
		return isWithinWidget(InterfaceID.Inventory.ITEMS, x, y)
			|| isWithinWidget(InterfaceID.Chatbox.UNIVERSE, x, y);
	}

	private boolean isWithinWidget(int widgetId, int x, int y)
	{
		Widget widget = client.getWidget(widgetId);
		if (widget == null || widget.isHidden())
		{
			return false;
		}

		Rectangle bounds = widget.getBounds();
		return bounds != null && !bounds.isEmpty() && bounds.contains(x, y);
	}

	private Point randomPoint(Rectangle bounds)
	{
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		int marginX = Math.min(5, bounds.width / 4);
		int marginY = Math.min(5, bounds.height / 4);
		int width = Math.max(1, bounds.width - marginX * 2);
		int height = Math.max(1, bounds.height - marginY * 2);

		return new Point(
			bounds.x + marginX + random.nextInt(width),
			bounds.y + marginY + random.nextInt(height));
	}

	private static int findAction(String[] actions, String action)
	{
		if (actions == null || action == null)
		{
			return -1;
		}

		for (int i = 0; i < actions.length; i++)
		{
			if (action.equalsIgnoreCase(actions[i]))
			{
				return i;
			}
		}

		return -1;
	}

	private static MenuAction objectMenuAction(int index)
	{
		switch (index)
		{
			case 0:
				return MenuAction.GAME_OBJECT_FIRST_OPTION;
			case 1:
				return MenuAction.GAME_OBJECT_SECOND_OPTION;
			case 2:
				return MenuAction.GAME_OBJECT_THIRD_OPTION;
			case 3:
				return MenuAction.GAME_OBJECT_FOURTH_OPTION;
			case 4:
				return MenuAction.GAME_OBJECT_FIFTH_OPTION;
			default:
				return null;
		}
	}

	private static MenuAction npcMenuAction(int index)
	{
		switch (index)
		{
			case 0:
				return MenuAction.NPC_FIRST_OPTION;
			case 1:
				return MenuAction.NPC_SECOND_OPTION;
			case 2:
				return MenuAction.NPC_THIRD_OPTION;
			case 3:
				return MenuAction.NPC_FOURTH_OPTION;
			case 4:
				return MenuAction.NPC_FIFTH_OPTION;
			default:
				return null;
		}
	}

	private static final class MouseStep
	{
		private final int id;
		private final Point point;
		private final int button;
		private final int clickCount;
		private final int modifiers;
		private final long delayMs;

		private MouseStep(int id, Point point, int button, int clickCount, int modifiers, long delayMs)
		{
			this.id = id;
			this.point = point;
			this.button = button;
			this.clickCount = clickCount;
			this.modifiers = modifiers;
			this.delayMs = delayMs;
		}
	}

	/**
	 * A simulated click that the mouse debug overlay can animate.
	 */
	public static final class ClickEffect
	{
		private final Point location;
		private final int button;
		private final long startedAt;

		private ClickEffect(Point location, int button, long startedAt)
		{
			this.location = location;
			this.button = button;
			this.startedAt = startedAt;
		}

		public Point getLocation()
		{
			return new Point(location);
		}

		public int getButton()
		{
			return button;
		}

		public long getStartedAt()
		{
			return startedAt;
		}
	}
}
