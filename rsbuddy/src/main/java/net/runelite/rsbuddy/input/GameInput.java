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
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import net.runelite.api.Client;
import net.runelite.api.InventoryID;
import net.runelite.api.ItemContainer;
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
import net.runelite.rsbuddy.input.backend.HardwareInput;
import net.runelite.rsbuddy.input.backend.HardwareInputFactory;
import net.runelite.rsbuddy.input.backend.InputBackendType;

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
	private volatile boolean physicalOnly;
	private static final int RANDOM_POINT_ATTEMPTS = 25;
	private static final double TAU = Math.PI * 2.0;
	private static final long CLICK_EFFECT_LIFETIME_MS = 1000L;

	// Continuous cursor tracking: a follow target is refreshed every client
	// frame but eased toward at frame rate, so the cursor keeps moving with the
	// object instead of hopping once per tick. The per-step cap keeps a long
	// flick human-fast instead of teleporting straight to the object.
	private static final int FOLLOW_FRAME_MS = 15;
	private static final double FOLLOW_EPSILON_PX = 0.75;
	private static final double FOLLOW_MAX_STEP_PX = 24.0;

	private static final int CAMERA_YAW_UNITS = 16384;
	private static final int CAMERA_HALF_TURN = CAMERA_YAW_UNITS / 2;
	private static final int CAMERA_DEADBAND = 128;
	private static final int CAMERA_HOLD_MS = 800;

	private static final MenuAction[] NPC_ACTIONS = {
		MenuAction.NPC_FIRST_OPTION,
		MenuAction.NPC_SECOND_OPTION,
		MenuAction.NPC_THIRD_OPTION,
		MenuAction.NPC_FOURTH_OPTION,
		MenuAction.NPC_FIFTH_OPTION
	};

	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(GameInput.class);

	private final Client client;
	private final Random random = new Random();
	private volatile HardwareInput hardwareInput;
	private volatile InputBackendType backendPreference = InputBackendType.AUTO;
	private volatile long lastRefusalLogAt;

	private Point lastMousePoint;
	private Timer movementTimer;
	private boolean busy;
	private volatile boolean fastClicking;

	// Cursor target that is eased toward continuously. The script updates it
	// each tick; the Swing timer follows it at frame rate.
	private volatile Point followPoint;
	private volatile Timer followTimer;

	// Item ids whose "Take" option stays the default left click on the ground.
	// While a script is running every other ground item is pushed below
	// "Walk here", so a left click on a pile of drops walks instead of
	// picking up whatever happens to be under the cursor.
	private volatile Set<Integer> groundItemPickupIds = Collections.emptySet();

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

	// When set, mouse movements follow a quicker, straighter profile modelled on
	// an attentive player: a short human reaction delay, then a Fitts-style
	// movement whose duration grows with the log of the distance.
	private volatile boolean fastMouse;

	public GameInput(Client client)
	{
		this.client = client;
		this.hardwareInput = HardwareInputFactory.create(backendPreference);
	}

	/**
	 * Makes sure an OS hardware backend is ready, detecting it again if the
	 * cached one is missing or failed. There is no Java input fallback.
	 *
	 * @return true when input can be sent
	 */
	public synchronized boolean ensureHardwareInput()
	{
		if (hardwareInput == null)
		{
			hardwareInput = HardwareInputFactory.create(backendPreference);
		}
		return hardwareInput != null;
	}

	/** Changes the backend choice (Auto / Linux X11 / Windows SendInput) and detects again. */
	public synchronized void setBackendPreference(InputBackendType preference)
	{
		InputBackendType next = preference == null ? InputBackendType.AUTO : preference;
		if (next == backendPreference && hardwareInput != null)
		{
			return;
		}
		backendPreference = next;
		closeBackend();
		hardwareInput = HardwareInputFactory.create(next);
	}

	public InputBackendType getBackendPreference()
	{
		return backendPreference;
	}

	/** @return a one-line backend status for the panel */
	public String getBackendStatus()
	{
		HardwareInput backend = hardwareInput;
		return backend != null ? "using " + backend.getName()
			: "NONE - " + HardwareInputFactory.getLastProblem();
	}

	private synchronized void closeBackend()
	{
		HardwareInput backend = hardwareInput;
		hardwareInput = null;
		if (backend != null)
		{
			try
			{
				backend.close();
			}
			catch (Throwable ex)
			{
				log.debug("[Input] closing backend failed", ex);
			}
		}
	}

	/** A backend call threw: drop it so the next start detects again. */
	private void backendFailed(Throwable ex)
	{
		log.warn("[Input] hardware backend failed, input stopped until it is detected again: {}", ex.toString());
		closeBackend();
		HardwareInputFactory.setProblem("backend failed: " + ex);
	}

	/** Logs (at most every 2s) that input was refused because there is no hardware backend. */
	private void refuseNoBackend(String what)
	{
		long now = System.currentTimeMillis();
		if (now - lastRefusalLogAt > 2000)
		{
			lastRefusalLogAt = now;
			log.warn("[Input] refusing {}: no hardware input backend ({}). Java input is never used.", what,
				HardwareInputFactory.getLastProblem());
		}
	}

	/**
	 * @return true when pointer and keys go through the operating system
	 * instead of synthetic AWT events
	 */
	public boolean isHardwareInput()
	{
		return hardwareInput != null;
	}

	/**
	 * Releases every key and button held by the hardware backend and closes
	 * it. Called when the plugin shuts down.
	 */
	public void shutdown()
	{
		closeBackend();
	}

	/**
	 * Switches between the default relaxed mouse profile and the fast,
	 * attentive one. Set by {@link net.runelite.rsbuddy.routine.RoutineManager}
	 * from the running script.
	 */
	public void setFastMouse(boolean fastMouse)
	{
		this.fastMouse = fastMouse;
	}

	public boolean isFastMouse()
	{
		return fastMouse;
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
		return click(npc, MouseEvent.BUTTON1);
	}

	public boolean click(NPC npc, int button)
	{
		if (npc == null)
		{
			return false;
		}

		// The hull follows the model, so every point in it belongs to this
		// NPC. randomPoint only returns points that are on screen and not
		// behind an interface, so a null result means the NPC is not
		// clickable and the tile marker below is tried instead.
		Shape hull = npc.getConvexHull();
		if (hull != null)
		{
			Point point = randomPoint(hull);
			if (point != null)
			{
				return click(point, button);
			}
		}

		Point point = randomPoint(npc.getCanvasTilePoly());
		return point != null && click(point, button);
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

		Point point = centerPoint(bounds);
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
	 * Moves the mouse onto a widget without clicking it, using the same curved
	 * path as a click. The point is the widget's centre, matching
	 * {@link #click(Widget)}.
	 *
	 * @return true if a movement was started
	 */
	public boolean hover(Widget widget)
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

		Point point = centerPoint(bounds);
		return point != null && moveTo(point);
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
	 * The object's convex hull (any object type), or its clickbox when it
	 * has no hull. Client thread only.
	 */
	public Shape objectHull(TileObject object)
	{
		if (object == null)
		{
			return null;
		}
		// The clickbox is the model the game actually hit-tests; the convex
		// hull can include parts covered by neighbouring objects.
		Shape clickbox = object.getClickbox();
		if (clickbox != null)
		{
			return clickbox;
		}
		Shape hull = null;
		if (object instanceof net.runelite.api.GameObject)
		{
			hull = ((net.runelite.api.GameObject) object).getConvexHull();
		}
		else if (object instanceof net.runelite.api.WallObject)
		{
			hull = ((net.runelite.api.WallObject) object).getConvexHull();
		}
		else if (object instanceof net.runelite.api.DecorativeObject)
		{
			hull = ((net.runelite.api.DecorativeObject) object).getConvexHull();
		}
		else if (object instanceof net.runelite.api.GroundObject)
		{
			hull = ((net.runelite.api.GroundObject) object).getConvexHull();
		}
		return hull;
	}

	/**
	 * Dead centre of the object's hull: the area centroid of the hull
	 * polygon, or the clickable point inside the hull nearest to it when the
	 * centroid itself is outside, off the viewport or under an interface. No
	 * random offset. Recompute every time (client thread) because it moves
	 * with the camera and the player.
	 *
	 * @return the point, or null when the object is not clickable on screen
	 */
	public Point objectCenter(TileObject object)
	{
		Shape hull = objectHull(object);
		if (hull == null)
		{
			return null;
		}
		Rectangle bounds = hull.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		double cx = bounds.getCenterX();
		double cy = bounds.getCenterY();
		if (hull instanceof Polygon)
		{
			Polygon poly = (Polygon) hull;
			double area = 0;
			double sx = 0;
			double sy = 0;
			for (int i = 0; i < poly.npoints; i++)
			{
				int j = (i + 1) % poly.npoints;
				double cross = (double) poly.xpoints[i] * poly.ypoints[j] - (double) poly.xpoints[j] * poly.ypoints[i];
				area += cross;
				sx += (poly.xpoints[i] + poly.xpoints[j]) * cross;
				sy += (poly.ypoints[i] + poly.ypoints[j]) * cross;
			}
			if (Math.abs(area) > 1e-6)
			{
				cx = sx / (3 * area);
				cy = sy / (3 * area);
			}
		}

		int x0 = (int) Math.round(cx);
		int y0 = (int) Math.round(cy);
		if (clickable(hull, x0, y0))
		{
			return new Point(x0, y0);
		}
		// Nearest clickable point to the centroid, ring by ring.
		int maxRadius = Math.max(bounds.width, bounds.height);
		for (int r = 1; r <= maxRadius; r++)
		{
			Point best = null;
			double bestDistance = Double.MAX_VALUE;
			for (int dx = -r; dx <= r; dx++)
			{
				for (int dy = -r; dy <= r; dy++)
				{
					if (Math.max(Math.abs(dx), Math.abs(dy)) != r || !clickable(hull, x0 + dx, y0 + dy))
					{
						continue;
					}
					double distance = dx * dx + dy * dy;
					if (distance < bestDistance)
					{
						bestDistance = distance;
						best = new Point(x0 + dx, y0 + dy);
					}
				}
			}
			if (best != null)
			{
				return best;
			}
		}
		return null;
	}

	private boolean clickable(Shape shape, int x, int y)
	{
		return shape.contains(x, y) && isInViewport(x, y) && !isCoveredByInterface(x, y);
	}

	/** Curved human-like move onto the dead centre of the object's hull. */
	public boolean hoverCenter(TileObject object)
	{
		Point point = objectCenter(object);
		return point != null && moveTo(point);
	}

	/** Curved human-like move and left click on the dead centre of the object's hull. */
	public boolean clickCenter(TileObject object)
	{
		Point point = objectCenter(object);
		return point != null && click(point);
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

		stopFollowing();
		busy = true;
		SwingUtilities.invokeLater(() -> beginMove(canvas, canvasPoint));
		return true;
	}

	/**
	 * Eases the simulated cursor toward a point and keeps following it while
	 * the point moves, refreshing at frame rate. Unlike {@link #moveTo(Point)},
	 * which plays one fixed path and then stops, this keeps easing toward the
	 * latest target until it is reached. The script re-reads a moving object's
	 * projection every client frame, so the cursor glides with it instead of
	 * hopping once per game tick.
	 *
	 * @param gamePoint point to follow, or null to stop following
	 * @return true if the point is on the canvas and will be followed
	 */
	public boolean follow(Point gamePoint)
	{
		if (gamePoint == null)
		{
			stopFollowing();
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
			stopFollowing();
			return false;
		}

		followPoint = new Point(gamePoint);
		if (followTimer == null)
		{
			// Only pay for the EDT hop when the follower is not already
			// running; onClientTick calls this every frame.
			SwingUtilities.invokeLater(() -> ensureFollowTimer(canvas));
		}
		return true;
	}

	/**
	 * Stops continuous cursor following. The target is cleared immediately so
	 * a step that is already queued does nothing; the timer is stopped on the
	 * Swing event thread.
	 */
	private void stopFollowing()
	{
		followPoint = null;
		SwingUtilities.invokeLater(this::stopFollowTimer);
	}

	private void ensureFollowTimer(Canvas canvas)
	{
		if (followPoint == null || followTimer != null)
		{
			return;
		}

		Point target = toCanvasPoint(followPoint);
		if (lastMousePoint != null
			&& lastMousePoint.distance(target) < FOLLOW_EPSILON_PX)
		{
			return;
		}

		followTimer = new Timer(FOLLOW_FRAME_MS, event -> stepFollow(canvas));
		followTimer.start();
	}

	private void stepFollow(Canvas canvas)
	{
		Point point = followPoint;
		if (point == null)
		{
			stopFollowTimer();
			return;
		}

		// Never fight a click or a normal animated path.
		if (busy || fastClicking || movementTimer != null)
		{
			return;
		}

		Point target = toCanvasPoint(point);
		if (!canvas.contains(target))
		{
			stopFollowTimer();
			return;
		}

		Point current = lastMousePoint;
		if (current == null || !canvas.contains(current))
		{
			Dimension size = canvas.getSize();
			if (size.width <= 0 || size.height <= 0)
			{
				return;
			}

			current = new Point(size.width / 2, size.height / 2);
			lastMousePoint = current;
			mouseLocation = toGamePoint(current);
		}

		double dx = target.x - current.x;
		double dy = target.y - current.y;
		double distance = Math.hypot(dx, dy);

		if (distance < FOLLOW_EPSILON_PX)
		{
			if (!target.equals(current))
			{
				lastMousePoint = target;
				mouseLocation = toGamePoint(target);
				dispatch(canvas, MouseEvent.MOUSE_MOVED, target, MouseEvent.NOBUTTON, 0, 0);
			}

			stopFollowTimer();
			return;
		}

		// Ease out toward the point. The fraction is larger while far away so
		// the cursor catches up quickly, then shrinks into a smooth landing.
		// Cap the step so a long move cannot jump across the screen in a frame.
		double fraction = distance > 90.0
			? 0.30 + Math.min(0.20, distance / 1200.0)
			: 0.30;
		double step = Math.min(distance * fraction, FOLLOW_MAX_STEP_PX);
		if (step < 1.0)
		{
			// Always advance at least one pixel, or integer rounding could
			// leave the cursor stuck just short of a slow-moving target.
			step = 1.0;
		}

		int x = (int) Math.round(current.x + dx / distance * step);
		int y = (int) Math.round(current.y + dy / distance * step);

		// A little hand tremor while travelling, gone as it settles.
		if (distance > 12.0)
		{
			x += random.nextInt(3) - 1;
			y += random.nextInt(3) - 1;
		}

		Point next = new Point(x, y);
		lastMousePoint = next;
		mouseLocation = toGamePoint(next);
		dispatch(canvas, MouseEvent.MOUSE_MOVED, next, MouseEvent.NOBUTTON, 0, 0);
	}

	private void stopFollowTimer()
	{
		if (followTimer != null)
		{
			followTimer.stop();
			followTimer = null;
		}
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
		stopFollowing();
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

	/**
	 * Clicks several widgets one after another using the animated mouse path.
	 * The speed multiplier scales the movement and click timing, so a value of
	 * 2.0 makes the whole sequence twice as fast as normal clicking.
	 *
	 * @return true if the click sequence was started
	 */
	public boolean clickEach(List<Widget> widgets, double speedMultiplier)
	{
		if (widgets == null || widgets.isEmpty())
		{
			return false;
		}

		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		List<Point> gamePoints = new ArrayList<>();

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

			Point point = centerPoint(bounds);
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
		}

		if (gamePoints.isEmpty())
		{
			return false;
		}

		stopCamera();
		stopFollowing();
		busy = true;
		final double speed = Math.max(0.1, speedMultiplier);
		final List<Point> targets = gamePoints;
		SwingUtilities.invokeLater(() -> beginClickEach(canvas, targets, speed));
		return true;
	}

	private void beginClickEach(Canvas canvas, List<Point> targets, double speed)
	{
		Point start = beginMovement(canvas);
		if (start == null)
		{
			busy = false;
			return;
		}

		List<MouseStep> steps = new ArrayList<>();
		Point cursor = start;
		long offset = 0;

		for (Point target : targets)
		{
			List<MouseStep> segment = buildMoveSteps(cursor, target);
			appendClickSteps(segment, target, MouseEvent.BUTTON1);

			for (MouseStep step : segment)
			{
				long delay = Math.round(step.delayMs / speed);
				steps.add(new MouseStep(step.id, step.point, step.button,
					step.clickCount, step.modifiers, offset + delay));
			}

			offset += Math.round(segment.get(segment.size() - 1).delayMs / speed);
			cursor = target;
		}

		playSteps(canvas, steps);
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

		HardwareInput backend = hardwareInput;
		if (backend == null)
		{
			refuseNoBackend("zoom");
			zooming = false;
			return;
		}
		try
		{
			Point screen = toScreenPoint(canvas, new Point(x, y));
			backend.movePointer(screen.x, screen.y);
			backend.scroll(1);
			backend.flush();
		}
		catch (RuntimeException ex)
		{
			backendFailed(ex);
			zooming = false;
			return;
		}

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
	 * Briefly holds the left or right arrow key to rotate the camera a small
	 * amount, like a player idly nudging the view. Used for anti-idle.
	 *
	 * @param left true to hold the left arrow key, false for the right one
	 * @param holdMillis how long to hold the key
	 * @return true if the nudge was started, false if the camera is already
	 * turning or the canvas is unavailable
	 */
	public boolean nudgeCamera(boolean left, int holdMillis)
	{
		if (cameraKey != -1)
		{
			return false;
		}

		// Record the starting yaw so the pulse still calibrates the arrow
		// key directions used by turnCameraToYaw.
		cameraPulseYaw = client.getCameraYawTarget() & (CAMERA_YAW_UNITS - 1);
		return holdKey(left ? KeyEvent.VK_LEFT : KeyEvent.VK_RIGHT, holdMillis);
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

		if (hardwareInput == null)
		{
			refuseNoBackend("key " + KeyEvent.getKeyText(keyCode));
			return false;
		}

		SwingUtilities.invokeLater(() ->
		{
			canvas.requestFocusInWindow();
			dispatchKey(canvas, KeyEvent.KEY_PRESSED, keyCode);
			dispatchKey(canvas, KeyEvent.KEY_RELEASED, keyCode);
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

	private void dispatchKey(Canvas canvas, int id, int keyCode)
	{
		HardwareInput backend = hardwareInput;
		if (backend == null)
		{
			refuseNoBackend("key " + KeyEvent.getKeyText(keyCode));
			return;
		}
		try
		{
			if (id == KeyEvent.KEY_PRESSED)
			{
				backend.pressKey(keyCode);
			}
			else if (id == KeyEvent.KEY_RELEASED)
			{
				backend.releaseKey(keyCode);
			}

			backend.flush();
		}
		catch (RuntimeException ex)
		{
			backendFailed(ex);
		}
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
	 * Types text into whatever currently has chatbox focus, one character at a
	 * time so the game's input handler keeps up. The characters are dispatched
	 * as KEY_TYPED events, which is what the chatbox listens for.
	 */
	public boolean typeText(String text)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null || text == null || text.isEmpty())
		{
			return false;
		}

		if (hardwareInput == null)
		{
			refuseNoBackend("typing");
			return false;
		}

		final char[] characters = text.toCharArray();

		SwingUtilities.invokeLater(() ->
		{
			canvas.requestFocusInWindow();

			final int[] index = {0};
			Timer timer = new Timer(60, null);
			timer.addActionListener(event ->
			{
				if (index[0] >= characters.length)
				{
					timer.stop();
					return;
				}

				char character = characters[index[0]++];
				HardwareInput backend = hardwareInput;
				if (backend == null)
				{
					refuseNoBackend("typing");
					timer.stop();
					return;
				}
				try
				{
					backend.typeCharacter(character);
					backend.flush();
				}
				catch (RuntimeException ex)
				{
					backendFailed(ex);
					timer.stop();
					return;
				}
				timer.setDelay(55 + random.nextInt(45));
			});
			timer.setInitialDelay(0);
			timer.start();
		});

		return true;
	}

	/**
	 * Submits the active chatbox input.
	 */
	public boolean pressEnter()
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null)
		{
			return false;
		}

		if (hardwareInput == null)
		{
			refuseNoBackend("Enter");
			return false;
		}

		SwingUtilities.invokeLater(() ->
		{
			canvas.requestFocusInWindow();
			dispatchKey(canvas, KeyEvent.KEY_PRESSED, KeyEvent.VK_ENTER);
			dispatchKey(canvas, KeyEvent.KEY_RELEASED, KeyEvent.VK_ENTER);
		});

		return true;
	}

	/**
	 * Clicks a widget option while ignoring colour tags, so an action stored as
	 * {@code Create <col=ff9040>Buy</col> offer} can be matched with plain
	 * {@code Create Buy offer}.
	 */
	public boolean interactStripped(Widget widget, String action)
	{
		if (widget == null || widget.isHidden() || action == null)
		{
			return false;
		}

		String[] actions = widget.getActions();
		if (actions == null)
		{
			return false;
		}

		for (int i = 0; i < actions.length; i++)
		{
			if (actions[i] != null && stripTags(actions[i]).equalsIgnoreCase(stripTags(action)))
			{
				if (physicalOnly)
				{
					return widgetViaMenu(widget, i, actions[i]);
				}
				if (!directActionAllowed("interactStripped"))
				{
					return false;
				}
				client.menuAction(-1, widget.getId(), MenuAction.CC_OP, i + 1, -1, actions[i], "");
				return true;
			}
		}

		return false;
	}

	private static String stripTags(String value)
	{
		return value.replaceAll("<[^>]*>", "").trim();
	}

	/*
	 * Interaction primitives
	 *
	 * Every target type has two paths:
	 *  - interact(...) dispatches the exact menu action directly. No context
	 *    menu is opened, so it takes one tick and cannot get stuck on one.
	 *  - interactViaMenu(...) right-clicks the target, then runs the matching
	 *    entry by its identifier on the next tick. Entries are never picked by
	 *    row position and never fall back to an unrelated target.
	 *
	 * closeMenu/closeInterface/closeInterface keep leftover UI from blocking
	 * later actions.
	 */

	/**
	 * Interacts with a tile object. The default left-click option is clicked
	 * normally; any other option is dispatched directly from the object's
	 * composition, without opening a context menu.
	 */
	public boolean interact(TileObject object, String... actions)
	{
		if (object == null || actions == null || actions.length == 0)
		{
			return false;
		}

		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return false;
		}

		int actionIndex = findAction(composition.getActions(), actions);
		if (actionIndex < 0)
		{
			return false;
		}

		if (actionIndex == 0)
		{
			return click(object);
		}

		MenuAction type = objectMenuAction(actionIndex);
		if (type == null)
		{
			return false;
		}

		if (physicalOnly)
		{
			// Real clicks only: right-click it, then click the option's row.
			return interactViaMenu(object, actions);
		}

		long hash = object.getHash();
		int sceneX = (int) (hash & 0x7F);
		int sceneY = (int) ((hash >>> 7) & 0x7F);

		if (!directActionAllowed("interact"))
		{
			return false;
		}
		client.menuAction(sceneX, sceneY, type, object.getId(), -1,
			composition.getActions()[actionIndex],
			composition.getName() == null ? "" : composition.getName());
		return true;
	}

	/**
	 * Runs one of a tile object's options directly, without any mouse click,
	 * so nothing drawn in front of the object (another object, a wall) can
	 * take the click instead. Used as a last resort when the right-click menu
	 * did not offer the option.
	 */
	public boolean invokeObjectAction(TileObject object, String... actions)
	{
		if (object == null || actions == null || actions.length == 0)
		{
			return false;
		}

		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition == null)
		{
			return false;
		}

		int actionIndex = findAction(composition.getActions(), actions);
		MenuAction type = actionIndex < 0 ? null : objectMenuAction(actionIndex);
		if (type == null)
		{
			return false;
		}

		if (physicalOnly)
		{
			return interactViaMenu(object, actions);
		}

		long hash = object.getHash();
		int sceneX = (int) (hash & 0x7F);
		int sceneY = (int) ((hash >>> 7) & 0x7F);
		if (!directActionAllowed("invokeObjectAction"))
		{
			return false;
		}
		client.menuAction(sceneX, sceneY, type, object.getId(), -1,
			composition.getActions()[actionIndex],
			composition.getName() == null ? "" : composition.getName());
		return true;
	}

	/**
	 * Two-step fallback for a tile object: right-click it, then run the exact
	 * matching entry. Use this when the direct path does not affect the object
	 * (for example wall objects whose menu identifier is packed differently).
	 */
	public boolean interactViaMenu(TileObject object, String... actions)
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
				return invokeMenuEntry(entry);
			}

			closeMenu();
			return false;
		}

		return click(object, MouseEvent.BUTTON3);
	}

	private MenuEntry findObjectMenuEntry(TileObject object, String... actions)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		String name = composition == null ? null : composition.getName();
		int objectId = object.getId() & 0xFFFF;
		MenuEntry optionOnly = null;
		boolean multipleOptions = false;

		for (MenuEntry entry : currentMenuEntries())
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
			if (target != null && name != null && stripTags(target).equalsIgnoreCase(stripTags(name)))
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

	/**
	 * Interacts with an NPC. The default left-click option is clicked normally;
	 * any other option is sent straight to the NPC index without opening a
	 * context menu.
	 */
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

		if (actionIndex == 0)
		{
			return click(npc);
		}

		if (physicalOnly)
		{
			return interactViaMenu(npc, action);
		}

		MenuAction type = npcMenuAction(actionIndex);
		if (type == null)
		{
			return false;
		}

		if (!directActionAllowed("interact"))
		{
			return false;
		}
		client.menuAction(0, 0, type, npc.getIndex(), -1,
			composition.getActions()[actionIndex],
			npc.getName() == null ? "" : npc.getName());
		return true;
	}

	/**
	 * Two-step fallback for an NPC: right-click it, then run the entry that
	 * belongs to that exact NPC index. This never uses the entry above or
	 * below the intended one.
	 */
	public boolean interactViaMenu(NPC npc, String action)
	{
		if (npc == null)
		{
			return false;
		}

		if (client.isMenuOpen())
		{
			MenuEntry entry = findExactMenuEntry(NPC_ACTIONS, npc.getIndex(), -1, action);
			if (entry != null)
			{
				return invokeMenuEntry(entry);
			}

			closeMenu();
			return false;
		}

		return click(npc, MouseEvent.BUTTON3);
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

		if (physicalOnly)
		{
			return widgetViaMenu(widget, actionIndex, action);
		}

		if (!directActionAllowed("interact"))
		{
			return false;
		}
		client.menuAction(-1, widget.getId(), MenuAction.CC_OP, actionIndex + 1, -1, action, "");
		return true;
	}

	/**
	 * Physical widget option: left click when it is the first option,
	 * otherwise right-click the widget and click the option's row next call.
	 */
	private boolean widgetViaMenu(Widget widget, int actionIndex, String action)
	{
		if (client.isMenuOpen())
		{
			for (MenuEntry entry : currentMenuEntries())
			{
				if (entry != null && entry.getParam1() == widget.getId() && matchesOption(entry.getOption(), action))
				{
					return clickOpenMenuRow(entry);
				}
			}
			closeMenu();
			return false;
		}
		return actionIndex == 0 ? click(widget) : click(widget, MouseEvent.BUTTON3);
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
	 * Physically clicks an item in the bank, moving the cursor onto its slot
	 * and running the bank's left-click withdraw option like a player would.
	 *
	 * @return true if a movement/click was started
	 */
	public boolean clickBankItem(int itemId)
	{
		Widget item = findBankItemWidget(itemId);
		if (item == null || item.isHidden())
		{
			return false;
		}

		return click(item);
	}

	/**
	 * Withdraws an item from the bank by sending the bank slot's withdraw
	 * action directly. No context menu is opened, so the cursor cannot land on
	 * the wrong slot or option.
	 */
	public boolean withdrawBankItem(int itemId, String withdrawOption)
	{
		if (client.isMenuOpen())
		{
			MenuEntry entry = findBankMenuEntry(itemId, withdrawOption);
			if (entry != null)
			{
				return invokeMenuEntry(entry);
			}

			closeMenu();
			return false;
		}

		// The bank container holds every tab, so its slot is what the withdraw
		// action needs. The item does not have to be in the tab that happens
		// to be open, which is why this no longer requires a visible widget.
		ItemContainer bank = client.getItemContainer(InventoryID.BANK);
		int slot = bank == null ? -1 : bank.find(itemId);
		if (slot < 0)
		{
			return false;
		}

		Widget widget = findBankItemWidget(itemId);
		String[] actions = widget == null ? null : widget.getActions();
		int actionIndex = findAction(actions, withdrawOption);
		if (actionIndex < 0)
		{
			actionIndex = bankWithdrawOptionIndex(withdrawOption);
		}

		if (actionIndex < 0)
		{
			return false;
		}

		String option = actions != null && actionIndex < actions.length
			? actions[actionIndex]
			: withdrawOption;

		if (physicalOnly)
		{
			// Real clicks on the visible slot only.
			if (widget == null || widget.isHidden())
			{
				log.info("[GameInput] withdraw {}: item not visible in the open bank tab", itemId);
				return false;
			}
			return widgetViaMenu(widget, actionIndex, option);
		}

		if (!directActionAllowed("withdrawBankItem"))
		{
			return false;
		}
		client.menuAction(slot, InterfaceID.Bankmain.ITEMS, MenuAction.CC_OP,
			actionIndex + 1, itemId, option, "");
		return true;
	}

	/**
	 * Bank items always list the same withdraw options in the same order, and
	 * the action index is that order. Used when the item has no visible slot
	 * widget, for example because it sits in a bank tab that is not open.
	 *
	 * @return the zero-based action index, or -1 for an unknown option
	 */
	private static int bankWithdrawOptionIndex(String option)
	{
		if (option == null)
		{
			return -1;
		}

		switch (stripTags(option).toLowerCase(java.util.Locale.ROOT))
		{
			case "withdraw-1":
				return 0;
			case "withdraw-5":
				return 1;
			case "withdraw-10":
				return 2;
			case "withdraw-all":
				return 3;
			case "withdraw-x":
				return 4;
			default:
				return -1;
		}
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
	 * Finds the Take entry for the exact ground item stack at the given
	 * scene coordinates. Entries for other nearby tiles are ignored: when
	 * drops overlap, the menu can contain several items with the same id,
	 * and taking one of those would pick up the wrong item.
	 */
	private MenuEntry findGroundItemMenuEntry(int itemId, int sceneX, int sceneY)
	{
		int id = itemId & 0xFFFF;

		for (MenuEntry entry : currentMenuEntries())
		{
			if (entry == null || !isGroundItemAction(entry.getType())
				|| !matchesOption(entry.getOption(), "Take", "Pick up", "Pickup"))
			{
				continue;
			}

			if (entry.getParam0() == sceneX
				&& entry.getParam1() == sceneY
				&& (entry.getIdentifier() & 0xFFFF) == id)
			{
				return entry;
			}
		}

		return null;
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
		if (option == null || actions == null)
		{
			return false;
		}

		String plainOption = stripTags(option);
		for (String action : actions)
		{
			if (action != null && plainOption.equalsIgnoreCase(stripTags(action)))
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

		if (!client.isMenuOpen() || client.isMenuScrollable() || !menuContains(entry))
		{
			return invokeMenuEntry(entry);
		}
		return clickOpenMenuRow(entry);
	}

	private boolean menuContains(MenuEntry entry)
	{
		net.runelite.api.Menu menu = client.getMenu();
		MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
		if (entries != null)
		{
			for (MenuEntry e : entries)
			{
				if (e == entry)
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Visible click on an entry's row in the open context menu. Refuses (and
	 * logs) when the menu is closed, scrollable or lacks the entry.
	 */
	private boolean clickOpenMenuRow(MenuEntry entry)
	{
		net.runelite.api.Menu menu = client.getMenu();
		MenuEntry[] entries = menu == null ? null : menu.getMenuEntries();
		if (!client.isMenuOpen() || entries == null || entries.length == 0)
		{
			log.info("[GameInput] refused '{}': no open menu to click (physical clicks only)",
				entry == null ? "" : plainText(entry.getOption()));
			return false;
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
			log.info("[GameInput] refused '{}': entry not clickable in the open menu", plainText(entry.getOption()));
			return false;
		}

		int row = entries.length - 1 - index;
		int x = menu.getMenuX() + Math.max(8, menu.getMenuWidth() / 2);
		int y = menu.getMenuY() + 19 + row * 15 + 7;
		return clickMenuPoint(new Point(x, y));
	}

	private boolean invokeMenuEntry(MenuEntry entry)
	{
		if (physicalOnly)
		{
			// Real clicks only: click the entry's row in the open menu.
			return clickOpenMenuRow(entry);
		}
		if (!directActionAllowed("invokeMenuEntry"))
		{
			return false;
		}
		client.menuAction(entry.getParam0(), entry.getParam1(), entry.getType(),
			entry.getIdentifier(), entry.getItemId(), entry.getOption(), entry.getTarget());

		// Running an entry directly skips the menu's own click handler, which
		// normally dismisses the menu. Leave a Cancel entry alone so closing
		// the menu cannot recurse.
		if (entry.getType() != MenuAction.CANCEL && client.isMenuOpen())
		{
			closeMenu();
		}

		return true;
	}

	/**
	 * Runs a context-menu entry directly. This lets callers use an option that
	 * is not the default left-click action without opening the menu, and it
	 * never falls back to a plain click, so nothing unexpected is triggered.
	 */
	public boolean invokeMenu(MenuEntry entry)
	{
		return entry != null && invokeMenuEntry(entry);
	}

	/**
	 * Finds an entry in the client's current context menu. The menu is built
	 * from whatever the mouse is hovering, so callers must hover the target and
	 * wait a frame before asking.
	 *
	 * @param option option text, colour tags ignored
	 * @param itemId item id to require, or -1 to accept any
	 */
	public MenuEntry findMenuEntry(String option, int itemId)
	{
		if (option == null)
		{
			return null;
		}

		MenuEntry untyped = null;
		int untypedCount = 0;

		for (MenuEntry entry : currentMenuEntries())
		{
			if (entry == null || entry.getOption() == null
				|| !stripTags(entry.getOption()).equalsIgnoreCase(stripTags(option)))
			{
				continue;
			}

			if (entry.getItemId() == itemId)
			{
				return entry;
			}

			if (entry.getItemId() <= 0)
			{
				untyped = entry;
				untypedCount++;
			}
		}

		// Fall back only when there is exactly one candidate, so an ambiguous
		// menu can never be resolved to the wrong item.
		return untypedCount == 1 ? untyped : null;
	}

	/**
	 * Finds the single entry that matches every identifying field. Unlike
	 * {@link #findMenuEntry(String, int)}, this never falls back to an entry
	 * for another target, which is what makes menu interactions deterministic.
	 */
	private MenuEntry findExactMenuEntry(MenuAction[] types, int identifier, int itemId, String option)
	{
		for (MenuEntry entry : currentMenuEntries())
		{
			if (entry == null || entry.getType() == null)
			{
				continue;
			}

			if (types != null && !containsType(types, entry.getType()))
			{
				continue;
			}

			if (identifier >= 0 && (entry.getIdentifier() & 0xFFFF) != (identifier & 0xFFFF))
			{
				continue;
			}

			if (itemId >= 0 && entry.getItemId() != itemId)
			{
				continue;
			}

			if (option != null && !matchesOption(entry.getOption(), option))
			{
				continue;
			}

			return entry;
		}

		return null;
	}

	private static boolean containsType(MenuAction[] types, MenuAction type)
	{
		for (MenuAction candidate : types)
		{
			if (candidate == type)
			{
				return true;
			}
		}

		return false;
	}

	/**
	 * Lists the options in the current context menu, for logging when an
	 * expected option is missing.
	 */
	public List<String> describeMenu()
	{
		List<String> options = new ArrayList<>();

		for (MenuEntry entry : currentMenuEntries())
		{
			if (entry == null || entry.getOption() == null)
			{
				continue;
			}

			String target = stripTags(entry.getTarget() == null ? "" : entry.getTarget());
			options.add(stripTags(entry.getOption()) + (target.isEmpty() ? "" : " " + target));
		}

		return options;
	}

	private MenuEntry[] currentMenuEntries()
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

		MenuEntry[] entries = client.getMenuEntries();
		return entries == null ? new MenuEntry[0] : entries;
	}

	/**
	 * Closes the currently open right-click menu, if any.
	 * <p>
	 * The Cancel row is clicked like a real player would, because clicking the
	 * menu itself is the client's normal dismissal path. Synthetic Escape keys
	 * are unreliable while the canvas does not have keyboard focus, which used
	 * to leave the script looping on an open menu forever.
	 */
	public boolean closeMenu()
	{
		if (!client.isMenuOpen())
		{
			return false;
		}

		// An earlier menu click may still be in flight. Let it land instead of
		// stacking another click on top of it.
		if (busy || fastClicking)
		{
			return true;
		}

		for (MenuEntry entry : currentMenuEntries())
		{
			if (entry != null && entry.getType() == MenuAction.CANCEL)
			{
				return clickMenuEntry(entry);
			}
		}

		if (physicalOnly)
		{
			// No Cancel row: move the cursor off the menu, which closes it.
			net.runelite.api.Menu menu = client.getMenu();
			int x = menu.getMenuX() > 40 ? menu.getMenuX() - 25 : menu.getMenuX() + menu.getMenuWidth() + 25;
			int y = menu.getMenuY() + Math.max(10, menu.getMenuHeight() / 2);
			return moveTo(new Point(x, y));
		}
		// Some menus do not include a Cancel row. Run the action directly.
		if (!directActionAllowed("closeMenu"))
		{
			return false;
		}
		client.menuAction(-1, -1, MenuAction.CANCEL, -1, -1, "", "");
		return true;
	}

	/**
	 * Aborts any simulated movement or click and clears the busy state. This
	 * is a last-resort recovery when an input callback never ran.
	 */
	public void reset()
	{
		stopCamera();
		stopMovement();
		HardwareInput backend = hardwareInput;
		if (backend != null)
		{
			try
			{
				backend.releaseAll();
			}
			catch (RuntimeException ex)
			{
				backendFailed(ex);
			}
		}
		busy = false;
		fastClicking = false;
		pileActive = false;
	}

	/**
	 * Closes an open interface by clicking its own Close button, the way a
	 * player would. Some interfaces ignore Escape, and while they are open
	 * every world click is swallowed by the interface.
	 *
	 * @return true if the button was found and clicked
	 */
	public boolean closeInterface(int interfaceId)
	{
		Widget close = findCloseWidget(interfaceId);
		return close != null && click(close);
	}

	/**
	 * Finds the Close button of an interface. The button belongs to the
	 * interface frame, not to the content container, so the search starts at
	 * the root of the whole interface group; searching only the container the
	 * caller knows about finds nothing and leaves the interface open.
	 */
	private Widget findCloseWidget(int interfaceId)
	{
		int group = interfaceId >>> 16;
		Widget root = client.getWidget(group << 16);
		Widget close = root == null ? null : findWidgetWithAction(root, "Close");
		if (close != null)
		{
			return close;
		}

		Widget component = client.getWidget(interfaceId);
		return component == null || component == root
			? null
			: findWidgetWithAction(component, "Close");
	}

	private Widget findWidgetWithAction(Widget root, String action)
	{
		java.util.ArrayDeque<Widget> queue = new java.util.ArrayDeque<>();
		java.util.Set<Widget> seen =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
		queue.add(root);

		int visited = 0;
		while (!queue.isEmpty() && visited++ < 5000)
		{
			Widget widget = queue.poll();
			if (widget == null || !seen.add(widget))
			{
				continue;
			}

			if (findAction(widget.getActions(), action) >= 0)
			{
				return widget;
			}

			addChildren(queue, widget.getChildren());
			addChildren(queue, widget.getDynamicChildren());
			addChildren(queue, widget.getStaticChildren());
			addChildren(queue, widget.getNestedChildren());
		}

		return null;
	}

	private static void addChildren(java.util.Queue<Widget> queue, Widget[] children)
	{
		if (children == null)
		{
			return;
		}

		for (Widget child : children)
		{
			if (child != null)
			{
				queue.add(child);
			}
		}
	}

	/**
	 * Sets which ground items keep "Take" as their default left click. An
	 * empty set disables the filter, which leaves the menu exactly as the
	 * client built it.
	 */
	public void setGroundItemPickupFilter(Set<Integer> itemIds)
	{
		groundItemPickupIds = itemIds == null || itemIds.isEmpty()
			? Collections.emptySet()
			: Collections.unmodifiableSet(new java.util.HashSet<>(itemIds));
	}

	/**
	 * @return true if a ground item of this id is allowed to be taken by a
	 * plain left click
	 */
	public boolean isGroundItemPickupAllowed(int itemId)
	{
		Set<Integer> allowed = groundItemPickupIds;
		return !allowed.isEmpty()
			&& (allowed.contains(itemId) || allowed.contains(itemId & 0xFFFF));
	}

	/**
	 * Keeps only the configured ground items left-clickable, the same way the
	 * menu entry swapper keeps a swappable option from being the default.
	 * <p>
	 * Deprioritized entries are sorted below the rest of the menu, so for an
	 * unwanted drop the "Take" row sinks underneath "Walk here" and a click on
	 * that tile walks instead of looting. Must be called on the client thread,
	 * from the menu entry event.
	 */
	public void filterGroundItemMenuEntry(MenuEntry entry)
	{
		Set<Integer> allowed = groundItemPickupIds;
		if (entry == null || allowed.isEmpty() || !isGroundItemAction(entry.getType()))
		{
			return;
		}

		int itemId = entry.getIdentifier();
		boolean wanted = isGroundItemPickupAllowed(itemId);
		boolean take = matchesOption(entry.getOption(), "Take", "Pick up", "Pickup");

		// A wanted item keeps its Take row at the top so one left click takes
		// it. Everything else, including the wanted item's Examine row, is
		// pushed down so it can never become the default action.
		entry.setDeprioritized(!wanted || !take);
	}

	/**
	 * Picks up the exact ground item stack at the given scene coordinates.
	 * <p>
	 * When this item is in the pickup filter its Take row is the default left
	 * click, so a single click on the tile takes exactly that item and cannot
	 * touch anything else. If the filter is not active for it, the matching
	 * menu entry is invoked directly instead, which also works while the
	 * context menu is already open.
	 */
	public boolean takeGroundItem(int sceneX, int sceneY, int itemId, String itemName)
	{
		MenuEntry entry = findGroundItemMenuEntry(itemId, sceneX, sceneY);
		if (entry != null && (!physicalOnly || client.isMenuOpen()))
		{
			return invokeMenuEntry(entry);
		}

		if (client.isMenuOpen())
		{
			closeMenu();
			return false;
		}

		LocalPoint localPoint = LocalPoint.fromScene(sceneX, sceneY, client.getTopLevelWorldView());
		if (localPoint == null)
		{
			return false;
		}

		Polygon tilePoly = Perspective.getCanvasTilePoly(client, localPoint);
		if (isGroundItemPickupAllowed(itemId))
		{
			Point point = itemPoint(tilePoly);
			return point != null && click(point);
		}

		Point point = randomTilePoint(tilePoly);
		return point != null && click(point, MouseEvent.BUTTON3);
	}

	/*
	 * Ground-item pickup helpers. Every one of these reads or drives the
	 * client, so each must be called from the script tick (the client
	 * thread), never from a Swing timer. The Swing side only replays mouse
	 * events.
	 */

	private Polygon liveTilePoly(int sceneX, int sceneY)
	{
		LocalPoint localPoint = LocalPoint.fromScene(sceneX, sceneY, client.getTopLevelWorldView());
		return localPoint == null ? null : Perspective.getCanvasTilePoly(client, localPoint);
	}

	/**
	 * @return a fresh point on the item's tile (game coordinates), computed
	 * now, or null if the tile is not on screen
	 */
	public Point groundItemPoint(int sceneX, int sceneY)
	{
		return itemPoint(liveTilePoly(sceneX, sceneY));
	}

	/** @return true if the point is inside the item tile's current screen polygon */
	public boolean isOnGroundItemTile(Point gamePoint, int sceneX, int sceneY)
	{
		Polygon tilePoly = liveTilePoly(sceneX, sceneY);
		return gamePoint != null && tilePoly != null && tilePoly.contains(gamePoint);
	}

	/**
	 * Reads the hover menu (what a left click would do right now).
	 *
	 * @return 2 if this item's Take is the top (left-click) option, 1 if it is
	 * under the cursor but something else is on top, 0 if it is not under the
	 * cursor at all
	 */
	public int groundItemHoverState(int itemId, int sceneX, int sceneY)
	{
		if (client.isMenuOpen())
		{
			return 0;
		}

		MenuEntry take = findGroundItemMenuEntry(itemId, sceneX, sceneY);
		if (take == null)
		{
			return 0;
		}

		// The left-click entry, skipping deprioritized rows the client sorts
		// below the rest (the raw last entry can be a deprioritized Examine).
		MenuEntry top = leftClickEntry();
		return take == top ? 2 : 1;
	}

	/**
	 * Clicks this item's exact Take row in the open context menu.
	 *
	 * @return false (and closes the menu) if the menu is not open or has no
	 * such row
	 */
	public boolean clickGroundItemMenuRow(int itemId, int sceneX, int sceneY)
	{
		if (!client.isMenuOpen())
		{
			return false;
		}

		MenuEntry entry = findGroundItemMenuEntry(itemId, sceneX, sceneY);
		if (entry == null)
		{
			closeMenu();
			return false;
		}
		return clickMenuEntry(entry);
	}

	/**
	 * Runs this item's exact Take on its tile without a mouse click. Take is
	 * the third ground-item option.
	 */
	public boolean invokeGroundItemTake(int sceneX, int sceneY, int itemId, String itemName)
	{
		if (physicalOnly)
		{
			return takeGroundItem(sceneX, sceneY, itemId, itemName);
		}
		if (!directActionAllowed("invokeGroundItemTake"))
		{
			return false;
		}
		client.menuAction(sceneX, sceneY, MenuAction.GROUND_ITEM_THIRD_OPTION, itemId, -1, "Take",
			itemName == null ? "" : itemName);
		return true;
	}

	/**
	 * Picks a point on the middle of a tile, where a ground item's model sits.
	 * With only the wanted drops left clickable, a click that misses the model
	 * walks instead of taking the item, so the point stays close to the centre
	 * rather than spread across the whole tile.
	 */
	private Point itemPoint(Polygon tilePoly)
	{
		if (tilePoly == null)
		{
			return null;
		}

		Rectangle bounds = tilePoly.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		int centerX = bounds.x + bounds.width / 2;
		int centerY = bounds.y + bounds.height / 2;

		for (int attempt = 0; attempt < RANDOM_POINT_ATTEMPTS; attempt++)
		{
			int x = centerX + random.nextInt(7) - 3;
			int y = centerY + random.nextInt(7) - 3;
			if (tilePoly.contains(x, y) && isInViewport(x, y) && !isCoveredByInterface(x, y))
			{
				return new Point(x, y);
			}
		}

		return randomPoint(tilePoly);
	}

	/**
	 * Picks a point in the lower part of a tile. Ground item models extend
	 * upwards, so the top of a tile can belong to an item on the tile above;
	 * clicking low on the tile keeps the menu on the intended drop.
	 */
	private Point randomTilePoint(Polygon tilePoly)
	{
		if (tilePoly == null)
		{
			return null;
		}

		Rectangle bounds = tilePoly.getBounds();
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		int minY = bounds.y + (int) Math.round(bounds.height * 0.55);
		int height = Math.max(1, bounds.y + bounds.height - minY);

		for (int attempt = 0; attempt < RANDOM_POINT_ATTEMPTS; attempt++)
		{
			int x = bounds.x + random.nextInt(Math.max(1, bounds.width));
			int y = minY + random.nextInt(height);
			if (tilePoly.contains(x, y) && isInViewport(x, y) && !isCoveredByInterface(x, y))
			{
				return new Point(x, y);
			}
		}

		return randomPoint(tilePoly);
	}

	/**
	 * Physical-input-only mode: every helper that would run a menu action
	 * directly (client.menuAction, no visible mouse click) refuses and logs
	 * instead. Set per script on start by the RoutineManager.
	 */
	public void setPhysicalOnly(boolean physicalOnly)
	{
		this.physicalOnly = physicalOnly;
	}

	public boolean isPhysicalOnly()
	{
		return physicalOnly;
	}

	private boolean directActionAllowed(String caller)
	{
		if (physicalOnly)
		{
			log.info("[GameInput] refused direct menu action from {}(): physical clicks only", caller);
			return false;
		}
		return true;
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
		stopFollowing();
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


	/*
	 * Tracked ground-pile looting.
	 *
	 * Threading: the client thread (script tick and every ClientTick frame
	 * via updatePileTracking) does ALL client reads: the tile polygon, the
	 * hover menu, ground items, inventory, player pose. It publishes an
	 * immutable PileTarget (canvas point + scale) and a click token through
	 * volatile fields. The Swing timer only reads those fields, moves the
	 * cursor toward the published point and presses the button when a token
	 * is pending. It never calls the client.
	 */

	/** Pile tracking status, as seen by the client thread. */
	public enum PileStatus
	{
		IDLE,
		TRACKING,
		OFF_SCREEN,
		EMPTY
	}

	private static final class PileTarget
	{
		private final int canvasX;
		private final int canvasY;
		private final double toGameX;
		private final double toGameY;
		private final int gameX;
		private final int gameY;

		private PileTarget(int canvasX, int canvasY, double toGameX, double toGameY, int gameX, int gameY)
		{
			this.canvasX = canvasX;
			this.canvasY = canvasY;
			this.toGameX = toGameX;
			this.toGameY = toGameY;
			this.gameX = gameX;
			this.gameY = gameY;
		}
	}

	private static final int PILE_STEER_MS = 8;
	private static final int PILE_MAX_STEP_PX = 26;
	/** The mouse thread presses once within this many px of the target. */
	private static final int PILE_CLICK_RADIUS_PX = 6;
	/** The client-thread gate counts the cursor as on the pile within this radius (or inside the tile). */
	private static final int PILE_GATE_RADIUS_PX = 6;
	/** Settled on the tile this long with no Take on top: left-click anyway and verify. */
	private static final long PILE_FORCE_CLICK_MS = 300;
	/** The tile must move more than this before the target is re-aimed. */
	private static final int PILE_REAIM_PX = 3;
	private static final long PILE_TOKEN_MAX_AGE_MS = 1000;
	private static final long PILE_NO_RESULT_MS = 700;
	private static final long PILE_WALK_RESULT_MS = 2500;

	// Shared between threads (volatile only).
	private volatile boolean pileActive;
	private volatile int pileSceneX = -1;
	private volatile int pileSceneY = -1;
	private volatile PileTarget pileTarget;
	private volatile int pileClickToken;
	private volatile long pileClickTokenAt;
	private volatile long pileSteerAfter;
	private volatile PileStatus pileStatus = PileStatus.IDLE;

	// Client thread only.
	private Set<Integer> pileIds = Collections.emptySet();
	private String pileLogPrefix = "[loot]";
	private double pileOffsetX;
	private double pileOffsetY;
	private int pileWantedCount;
	private int pileInventoryCount;
	private boolean pileAwaiting;
	private long pileClickSentAt;
	private long pileNextClickAt;
	private long pileOnTileSince;
	private int pileClicks;
	private int pileSuccesses;
	private int pileFailedClicks;
	private int pileFrames;
	private Point pilePrevMouse;
	private boolean pileForceRepublish;
	private String pileTop = "";

	// Swing thread only.
	private Timer pileTimer;
	private int pileHandledToken;
	private boolean pilePressing;
	private double pileCurveSign = 1.0;

	/**
	 * Starts gluing the mouse to a ground pile and spam-taking our items from
	 * it. Must be called on the client thread.
	 *
	 * @param itemIds the items to take; the top Take entry must be one of these
	 * @param logPrefix prefix for the per-click log lines
	 * @return false if the canvas is unavailable or the input is busy
	 */
	public boolean startPileTracking(int sceneX, int sceneY, Set<Integer> itemIds, String logPrefix)
	{
		Canvas canvas = client.getCanvas();
		if (canvas == null || busy || fastClicking)
		{
			return false;
		}

		pileIds = itemIds == null ? Collections.emptySet() : new java.util.HashSet<>(itemIds);
		pileLogPrefix = logPrefix == null ? "[loot]" : logPrefix;
		// A fixed spot on this pile, chosen once: never the dead centre.
		pileOffsetX = (random.nextDouble() * 2.0 - 1.0) * 0.22;
		pileOffsetY = (random.nextDouble() * 2.0 - 1.0) * 0.22;
		if (Math.abs(pileOffsetX) < 0.05)
		{
			pileOffsetX = pileOffsetX < 0 ? -0.07 : 0.07;
		}
		pileSceneX = sceneX;
		pileSceneY = sceneY;
		pileWantedCount = countPileItems(sceneX, sceneY);
		pileInventoryCount = countInventory();
		pileAwaiting = false;
		pileNextClickAt = 0;
		pileOnTileSince = 0;
		pileClicks = 0;
		pileSuccesses = 0;
		pileFailedClicks = 0;
		pileFrames = 0;
		pilePrevMouse = null;
		pileForceRepublish = false;
		pileTop = "";
		pileTarget = null;
		pileStatus = PileStatus.TRACKING;
		pileSteerAfter = System.currentTimeMillis() + reactionDelayMs();
		pileActive = true;
		publishPileTarget();

		SwingUtilities.invokeLater(() -> startPileTimer(canvas));
		return true;
	}

	/** Stops pile tracking. Safe from any thread. */
	public void stopPileTracking()
	{
		pileActive = false;
		pileStatus = PileStatus.IDLE;
	}

	/** @return true while the mouse is glued to the pile on this scene tile */
	public boolean isPileTracking(int sceneX, int sceneY)
	{
		return pileActive && pileSceneX == sceneX && pileSceneY == sceneY;
	}

	public PileStatus getPileStatus()
	{
		return pileActive ? pileStatus : PileStatus.IDLE;
	}

	public int getPileClicks()
	{
		return pileClicks;
	}

	public int getPileSuccesses()
	{
		return pileSuccesses;
	}

	public int getPileFailedClicks()
	{
		return pileFailedClicks;
	}

	public String getPileTop()
	{
		return pileTop;
	}

	/**
	 * Per-frame update. Call from ClientTick (client thread). Re-aims the
	 * published point at the pile, detects pickups, and decides when to
	 * click.
	 */
	public void updatePileTracking()
	{
		if (!pileActive)
		{
			return;
		}

		try
		{
			updatePileTrackingUnsafe();
		}
		catch (RuntimeException | AssertionError ex)
		{
			log.warn("{} pile tracking failed, stopping it", pileLogPrefix, ex);
			pileActive = false;
			pileStatus = PileStatus.IDLE;
		}
	}

	private void updatePileTrackingUnsafe()
	{
		int sceneX = pileSceneX;
		int sceneY = pileSceneY;
		long now = System.currentTimeMillis();
		++pileFrames;

		Player player = client.getLocalPlayer();
		boolean moving = player != null && player.getPoseAnimation() != player.getIdlePoseAnimation();

		int wanted = countPileItems(sceneX, sceneY);
		int inventory = countInventory();
		if (wanted < pileWantedCount || pileAwaiting && inventory != pileInventoryCount)
		{
			++pileSuccesses;
			pileFailedClicks = 0;
			pileAwaiting = false;
			// Click again after a short human beat, same pile.
			pileNextClickAt = now + 80 + random.nextInt(171);
			log.info("{} pile={},{} picked up (left on tile={}, pickups={})", pileLogPrefix, sceneX, sceneY,
				wanted, pileSuccesses);
		}
		pileWantedCount = wanted;
		pileInventoryCount = inventory;

		if (wanted == 0)
		{
			pileStatus = PileStatus.EMPTY;
			pileActive = false;
			return;
		}

		Polygon poly = publishPileTarget();
		if (poly == null)
		{
			pileStatus = PileStatus.OFF_SCREEN;
			return;
		}
		if (pileStatus == PileStatus.OFF_SCREEN)
		{
			pileStatus = PileStatus.TRACKING;
		}

		if (pileAwaiting)
		{
			// A click was sent: walking toward the pile means it took; wait
			// for the pickup to land.
			long waited = now - pileClickSentAt;
			if (waited < PILE_NO_RESULT_MS || moving && waited < PILE_WALK_RESULT_MS)
			{
				return;
			}
			pileAwaiting = false;
			++pileFailedClicks;
			pileNextClickAt = now + 80 + random.nextInt(171);
			// Only now (after a real failed click) try another point.
			pickAlternatePoint();
			log.info("{} pile={},{} click #{} gave no pickup after {} ms (failed in a row={}), new aim point",
				pileLogPrefix, sceneX, sceneY, pileClicks, waited, pileFailedClicks);
		}

		if (client.isMenuOpen())
		{
			return;
		}

		Point mouse = mouseLocation;
		PileTarget target = pileTarget;
		double distance = mouse == null || target == null ? Double.MAX_VALUE
			: Math.hypot(mouse.x - target.gameX, mouse.y - target.gameY);
		boolean onTile = mouse != null && (poly.contains(mouse) || distance <= PILE_GATE_RADIUS_PX);

		// Settled: the cursor did not move since the last frame.
		boolean settled = mouse != null && mouse.equals(pilePrevMouse);
		pilePrevMouse = mouse == null ? null : new Point(mouse);
		if (!onTile || !settled)
		{
			pileOnTileSince = 0;
		}
		else if (pileOnTileSince == 0)
		{
			pileOnTileSince = now;
		}

		MenuEntry top = leftClickEntry();
		boolean topWanted = isOurTake(top);
		pileTop = describe(top);

		String decision;
		if (!onTile)
		{
			decision = "moving to pile";
		}
		else if (!settled)
		{
			decision = "settling";
		}
		else if (now < pileNextClickAt)
		{
			decision = "human delay";
		}
		else if (topWanted)
		{
			decision = "CLICK (top is our Take)";
		}
		else if (now - pileOnTileSince >= PILE_FORCE_CLICK_MS)
		{
			decision = "CLICK (forced after " + (now - pileOnTileSince) + " ms settled)";
		}
		else
		{
			decision = "waiting for Take";
		}

		if (pileFrames % 5 == 0 || decision.startsWith("CLICK"))
		{
			log.info("{} hover pile={},{} cursor={} target={},{} dist={} top='{}' -> {}", pileLogPrefix, sceneX,
				sceneY, mouse == null ? "none" : mouse.x + "," + mouse.y, target == null ? -1 : target.gameX,
				target == null ? -1 : target.gameY, distance == Double.MAX_VALUE ? "-" : String.format("%.1f", distance),
				pileTop, decision);
		}

		if (decision.startsWith("CLICK"))
		{
			++pileClicks;
			pileAwaiting = true;
			pileClickSentAt = now;
			pileClickTokenAt = now;
			pileClickToken = pileClickToken + 1;
			pileStatus = PileStatus.TRACKING;
			log.info("{} pile={},{} click #{} point={},{} top='{}' {}", pileLogPrefix, sceneX, sceneY, pileClicks,
				mouse.x, mouse.y, pileTop, topWanted ? "" : "(forced)");
		}
	}

	/** Client thread: a different aim point on the tile, used only after a failed click. */
	private void pickAlternatePoint()
	{
		boolean nearCentre = pileFailedClicks % 2 == 1;
		double spread = nearCentre ? 0.08 : 0.25;
		pileOffsetX = (random.nextDouble() * 2.0 - 1.0) * spread;
		pileOffsetY = (random.nextDouble() * 2.0 - 1.0) * spread;
		pileForceRepublish = true;
	}

	/**
	 * The entry a left click runs right now. Deprioritized entries are
	 * sorted below the rest by the client, so the raw last entry can be one
	 * of them (for example Examine, which the pickup filter deprioritizes);
	 * the real top is the last entry that is not deprioritized.
	 */
	private MenuEntry leftClickEntry()
	{
		MenuEntry[] entries = currentMenuEntries();
		// Last non-deprioritized entry is the left click (PR #28). On top of
		// that, an Examine row is never the left click while the same
		// object/NPC/item also has a real option: the raw array can list
		// Examine last before the client's own ordering (seen at the
		// Motherlode hopper and veins: "Examine Hopper" read as the top
		// while the game's left click was "Deposit").
		MenuEntry fallback = null;
		for (int i = entries.length - 1; i >= 0; i--)
		{
			MenuEntry entry = entries[i];
			if (entry == null || entry.isDeprioritized())
			{
				continue;
			}
			if (isExamine(entry) && hasRealOption(entries, entry))
			{
				if (fallback == null)
				{
					fallback = entry;
				}
				continue;
			}
			return entry;
		}
		if (fallback != null)
		{
			return fallback;
		}
		return entries.length == 0 ? null : entries[entries.length - 1];
	}

	private static boolean isExamine(MenuEntry entry)
	{
		MenuAction type = entry.getType();
		return type == MenuAction.EXAMINE_OBJECT || type == MenuAction.EXAMINE_NPC
			|| type == MenuAction.EXAMINE_ITEM_GROUND
			|| "examine".equalsIgnoreCase(stripTags(entry.getOption() == null ? "" : entry.getOption()));
	}

	private static boolean hasRealOption(MenuEntry[] entries, MenuEntry examine)
	{
		for (MenuEntry other : entries)
		{
			if (other == null || other == examine || other.isDeprioritized() || isExamine(other))
			{
				continue;
			}
			MenuAction type = other.getType();
			if (type == MenuAction.CANCEL || type == MenuAction.WALK)
			{
				continue;
			}
			if (other.getIdentifier() == examine.getIdentifier()
				|| stripTags(String.valueOf(other.getTarget())).equalsIgnoreCase(stripTags(String.valueOf(examine.getTarget()))))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return the current menu in array order (index: option target type
	 * id deprioritized), for diagnostics. Client thread only.
	 */
	public String describeMenuOrder()
	{
		MenuEntry[] entries = currentMenuEntries();
		StringBuilder out = new StringBuilder();
		for (int i = 0; i < entries.length; i++)
		{
			MenuEntry e = entries[i];
			if (e == null)
			{
				continue;
			}
			out.append(i).append(":'").append(stripTags(String.valueOf(e.getOption()))).append(' ')
				.append(stripTags(String.valueOf(e.getTarget()))).append("' ").append(e.getType())
				.append(" id=").append(e.getIdentifier()).append(e.isDeprioritized() ? " deprio" : "").append("; ");
		}
		MenuEntry top = leftClickEntry();
		return out.append("-> left click '").append(top == null ? "none"
			: stripTags(String.valueOf(top.getOption())) + " " + stripTags(String.valueOf(top.getTarget()))).append('\'').toString();
	}

	/**
	 * @return the entry a left click runs right now (deprioritized rows
	 * skipped, like the client's own sort), or null. Client thread only.
	 */
	public MenuEntry getLeftClickEntry()
	{
		return client.isMenuOpen() ? null : leftClickEntry();
	}

	/** @return the text with colour/formatting tags removed */
	public static String plainText(String value)
	{
		return value == null ? "" : stripTags(value);
	}

	/** @return true if the entry is a Take of one of the tracked loot items (any tile) */
	private boolean isOurTake(MenuEntry entry)
	{
		if (entry == null || !isGroundItemAction(entry.getType()) || entry.getOption() == null
			|| !stripTags(entry.getOption()).equalsIgnoreCase("Take"))
		{
			return false;
		}
		int id = entry.getIdentifier();
		return pileIds.contains(id) || pileIds.contains(id & 0xFFFF);
	}

	private static String describe(MenuEntry entry)
	{
		if (entry == null)
		{
			return "none";
		}
		return stripTags(String.valueOf(entry.getOption()) + " " + String.valueOf(entry.getTarget()))
			+ " id=" + entry.getIdentifier() + " type=" + entry.getType()
			+ " p=" + entry.getParam0() + "," + entry.getParam1();
	}

	/** Client thread: computes and publishes the pile's current point. */
	private Polygon publishPileTarget()
	{
		Polygon poly = liveTilePoly(pileSceneX, pileSceneY);
		if (poly == null || poly.npoints == 0)
		{
			pileTarget = null;
			return null;
		}

		double cx = 0;
		double cy = 0;
		for (int i = 0; i < poly.npoints; i++)
		{
			cx += poly.xpoints[i];
			cy += poly.ypoints[i];
		}
		cx /= poly.npoints;
		cy /= poly.npoints;
		Rectangle bounds = poly.getBounds();

		double scale = 1.0;
		int x = (int) Math.round(cx);
		int y = (int) Math.round(cy);
		for (int i = 0; i < 4; i++)
		{
			int px = (int) Math.round(cx + pileOffsetX * bounds.width * scale);
			int py = (int) Math.round(cy + pileOffsetY * bounds.height * scale);
			if (poly.contains(px, py))
			{
				x = px;
				y = py;
				break;
			}
			scale *= 0.5;
		}

		if (!isInViewport(x, y) || isCoveredByInterface(x, y))
		{
			pileTarget = null;
			return null;
		}

		// Re-aim only when the tile really moved (walking, camera), so the
		// cursor can settle instead of chasing a point that jitters by a
		// pixel every frame.
		PileTarget current = pileTarget;
		if (!pileForceRepublish && current != null && Math.abs(current.gameX - x) <= PILE_REAIM_PX
			&& Math.abs(current.gameY - y) <= PILE_REAIM_PX && poly.contains(current.gameX, current.gameY))
		{
			return poly;
		}
		pileForceRepublish = false;

		Point game = new Point(x, y);
		Point canvasPoint = toCanvasPoint(game);
		double toGameX = canvasPoint.x == 0 ? 1.0 : (double) x / canvasPoint.x;
		double toGameY = canvasPoint.y == 0 ? 1.0 : (double) y / canvasPoint.y;
		pileTarget = new PileTarget(canvasPoint.x, canvasPoint.y, toGameX, toGameY, x, y);
		return poly;
	}

	/** Client thread: number of our item stacks on the pile tile. */
	private int countPileItems(int sceneX, int sceneY)
	{
		net.runelite.api.WorldView worldView = client.getTopLevelWorldView();
		net.runelite.api.Scene scene = worldView == null ? null : worldView.getScene();
		net.runelite.api.Tile[][][] tiles = scene == null ? null : scene.getTiles();
		int plane = worldView == null ? -1 : worldView.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length || tiles[plane] == null
			|| sceneX < 0 || sceneX >= tiles[plane].length || tiles[plane][sceneX] == null
			|| sceneY < 0 || sceneY >= tiles[plane][sceneX].length)
		{
			return 0;
		}
		net.runelite.api.Tile tile = tiles[plane][sceneX][sceneY];
		List<net.runelite.api.TileItem> items = tile == null ? null : tile.getGroundItems();
		if (items == null)
		{
			return 0;
		}
		int count = 0;
		for (net.runelite.api.TileItem item : items)
		{
			if (item != null && pileIds.contains(item.getId()))
			{
				++count;
			}
		}
		return count;
	}

	/** Client thread: total item quantity in the inventory. */
	private int countInventory()
	{
		ItemContainer container = client.getItemContainer(InventoryID.INVENTORY);
		if (container == null)
		{
			return 0;
		}
		int count = 0;
		for (net.runelite.api.Item item : container.getItems())
		{
			if (item != null && item.getId() != -1)
			{
				count += Math.max(1, item.getQuantity());
			}
		}
		return count;
	}

	/** Swing thread. */
	private void startPileTimer(Canvas canvas)
	{
		stopMovement();
		if (pileTimer != null)
		{
			pileTimer.stop();
		}
		pilePressing = false;
		pileHandledToken = pileClickToken;
		pileCurveSign = random.nextBoolean() ? 1.0 : -1.0;
		if (lastMousePoint == null || !canvas.contains(lastMousePoint))
		{
			Dimension size = canvas.getSize();
			lastMousePoint = new Point(Math.max(1, size.width / 2), Math.max(1, size.height / 2));
		}
		canvas.requestFocusInWindow();

		Timer timer = new Timer(PILE_STEER_MS, null);
		timer.addActionListener(event ->
		{
			try
			{
				pileStep(canvas, timer);
			}
			catch (RuntimeException | Error ex)
			{
				timer.stop();
				pileActive = false;
				throw ex;
			}
			finally
			{
				if (!pileActive)
				{
					// Never leave the timer or a held button behind.
					timer.stop();
					if (pileTimer == timer)
					{
						pileTimer = null;
					}
					if (pilePressing)
					{
						pilePressing = false;
						Point at = lastMousePoint;
						if (at != null)
						{
							dispatch(canvas, MouseEvent.MOUSE_RELEASED, at, MouseEvent.BUTTON1, 1, 0);
						}
					}
				}
			}
		});
		pileTimer = timer;
		timer.start();
	}

	/** Swing thread: one steering step, never touches the client. */
	private void pileStep(Canvas canvas, Timer timer)
	{
		if (!pileActive || pilePressing)
		{
			return;
		}
		PileTarget target = pileTarget;
		long now = System.currentTimeMillis();
		if (target == null || now < pileSteerAfter)
		{
			return;
		}

		Point current = lastMousePoint;
		double dx = target.canvasX - current.x;
		double dy = target.canvasY - current.y;
		double distance = Math.hypot(dx, dy);

		if (distance > 1.0)
		{
			// Ease in on the target: big steps far away (capped), small near
			// it, with a slight sideways arc that fades as it closes in.
			double step = Math.min(distance, Math.min(PILE_MAX_STEP_PX, Math.max(1.5, distance * 0.24)));
			double ux = dx / distance;
			double uy = dy / distance;
			double bend = distance > 12 ? pileCurveSign * Math.min(4.0, distance * 0.05) : 0.0;
			double nx = current.x + ux * step - uy * bend + (random.nextDouble() - 0.5) * 0.8;
			double ny = current.y + uy * step + ux * bend + (random.nextDouble() - 0.5) * 0.8;
			Point next = new Point((int) Math.round(nx), (int) Math.round(ny));
			if (!next.equals(current) && canvas.contains(next))
			{
				dispatch(canvas, MouseEvent.MOUSE_MOVED, next, MouseEvent.NOBUTTON, 0, 0);
				lastMousePoint = next;
				mouseLocation = new Point((int) Math.round(next.x * target.toGameX),
					(int) Math.round(next.y * target.toGameY));
				current = next;
				distance = Math.hypot(target.canvasX - current.x, target.canvasY - current.y);
			}
		}

		int token = pileClickToken;
		if (token == pileHandledToken)
		{
			return;
		}
		if (now - pileClickTokenAt > PILE_TOKEN_MAX_AGE_MS)
		{
			// Stale request (the cursor never got there in time): drop it,
			// the client thread will decide again.
			pileHandledToken = token;
			return;
		}
		if (distance > PILE_CLICK_RADIUS_PX)
		{
			return;
		}

		pileHandledToken = token;
		pilePressing = true;
		Point at = current;
		Point game = new Point((int) Math.round(at.x * target.toGameX), (int) Math.round(at.y * target.toGameY));
		clickLocation = game;
		clickStartedAt = now;
		clickButton = MouseEvent.BUTTON1;
		addClickEffect(game, MouseEvent.BUTTON1, now);
		dispatch(canvas, MouseEvent.MOUSE_PRESSED, at, MouseEvent.BUTTON1, 1, InputEvent.BUTTON1_DOWN_MASK);

		Timer release = new Timer(35 + random.nextInt(45), event ->
		{
			try
			{
				dispatch(canvas, MouseEvent.MOUSE_RELEASED, at, MouseEvent.BUTTON1, 1, 0);
				dispatch(canvas, MouseEvent.MOUSE_CLICKED, at, MouseEvent.BUTTON1, 1, 0);
			}
			finally
			{
				pilePressing = false;
			}
		});
		release.setRepeats(false);
		release.start();
	}

	private void addClickEffect(Point gamePoint, int button, long startedAt)
	{
		clickEffects.add(new ClickEffect(new Point(gamePoint), button, startedAt));

		long cutoff = System.currentTimeMillis() - CLICK_EFFECT_LIFETIME_MS;
		clickEffects.removeIf(effect -> effect.startedAt < cutoff);
	}

	private void beginClick(Canvas canvas, Point target, int button)
	{
		try
		{
			beginClickUnsafe(canvas, target, button);
		}
		catch (RuntimeException | Error ex)
		{
			stopMovement();
			busy = false;
			throw ex;
		}
	}

	private void beginClickUnsafe(Canvas canvas, Point target, int button)
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
		try
		{
			beginMoveUnsafe(canvas, target);
		}
		catch (RuntimeException | Error ex)
		{
			stopMovement();
			busy = false;
			throw ex;
		}
	}

	private void beginMoveUnsafe(Canvas canvas, Point target)
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
		// Any other mouse action takes the hand off a tracked pile.
		pileActive = false;

		Point start = lastMousePoint;
		HardwareInput backend = hardwareInput;
		if (backend != null)
		{
			// Start from where the real cursor actually is, so the first
			// movement does not jump from a stale position.
			Point screen = backend.getPointerLocation();
			Point actual = screen == null ? null : toCanvasPointFromScreen(canvas, screen);
			if (actual != null && canvas.contains(actual))
			{
				start = actual;
				lastMousePoint = actual;
			}
		}

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
			try
			{
				playStep(canvas, steps, startedAt, index, timer);
			}
			catch (RuntimeException | Error ex)
			{
				// Never leave a dead movement holding the busy flag.
				timer.stop();
				if (movementTimer == timer)
				{
					movementTimer = null;
				}
				busy = false;
				throw ex;
			}
		});

		movementTimer = timer;
		timer.start();
	}

	private void playStep(Canvas canvas, List<MouseStep> steps, long startedAt, int[] index, Timer timer)
	{
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
		}
	}

	private List<MouseStep> buildMoveSteps(Point start, Point target)
	{
		if (fastMouse)
		{
			return buildFastMoveSteps(start, target);
		}

		List<MouseStep> steps = new ArrayList<>();

		double dx = target.x - start.x;
		double dy = target.y - start.y;
		double distance = Math.hypot(dx, dy);
		long delay = reactionDelayMs();

		if (distance < 2.0)
		{
			steps.add(new MouseStep(MouseEvent.MOUSE_MOVED, target, MouseEvent.NOBUTTON, 0, 0, delay));
			return steps;
		}

		int moveSteps = Math.max(12, Math.min(50, (int) (distance / 11.0) + 9));
		double length = Math.max(1.0, distance);
		double perpX = -dy / length;
		double perpY = dx / length;

		// The hand does not travel in a straight line: longer movements get a
		// larger Gaussian bend, plus a little control-point noise.
		double maxBend = Math.min(70.0, Math.max(8.0, distance * 0.11));
		double bend = Math.max(-maxBend, Math.min(maxBend, random.nextGaussian() * maxBend * 0.42));
		double control1X = start.x + dx * 0.32 + perpX * bend + random.nextGaussian() * 1.2;
		double control1Y = start.y + dy * 0.32 + perpY * bend + random.nextGaussian() * 1.2;
		double control2X = start.x + dx * 0.68 + perpX * bend * 0.55 + random.nextGaussian() * 1.2;
		double control2Y = start.y + dy * 0.68 + perpY * bend * 0.55 + random.nextGaussian() * 1.2;

		// Fitts-like duration, with Gaussian variance between and within moves.
		double totalMs = 150.0 + distance * 0.62;
		double stepMean = Math.max(5.0, totalMs / moveSteps);
		double jitterScale = Math.min(5.0, Math.max(0.8, distance * 0.012));
		double overshoot = distance > 30.0 && random.nextInt(4) != 0
			? Math.max(-3.0, Math.min(3.0, random.nextGaussian() * 1.4))
			: 0.0;

		for (int i = 1; i <= moveSteps; i++)
		{
			double progress = (double) i / moveSteps;

			// Minimum-jerk velocity profile: slow start, fast middle, slow end,
			// slightly skewed so peak speed comes before the midpoint.
			double t = Math.pow(progress, 0.94);
			double eased = t * t * t * (t * (t * 6.0 - 15.0) + 10.0);

			double x = cubic(start.x, control1X, control2X, target.x, eased);
			double y = cubic(start.y, control1Y, control2Y, target.y, eased);

			if (i == moveSteps)
			{
				// Stop a pixel or two off target, then correct in the next step.
				x = target.x + perpX * overshoot;
				y = target.y + perpY * overshoot;
			}
			else
			{
				double remaining = 1.0 - progress;
				double jitter = remaining * jitterScale;
				double perpendicular = random.nextGaussian() * jitter;
				double along = random.nextGaussian() * jitter * 0.3;
				x += perpX * perpendicular + (dx / length) * along;
				y += perpY * perpendicular + (dy / length) * along;
			}

			delay += Math.max(4,
				Math.round(stepMean + random.nextGaussian() * stepMean * 0.22));
			if (i < moveSteps && random.nextInt(11) == 0)
			{
				delay += Math.max(12, Math.round(55.0 + random.nextGaussian() * 20.0));
			}

			steps.add(new MouseStep(MouseEvent.MOUSE_MOVED,
				new Point((int) Math.round(x), (int) Math.round(y)),
				MouseEvent.NOBUTTON, 0, 0, delay));
		}

		if (overshoot != 0.0)
		{
			delay += Math.max(10, Math.round(28.0 + random.nextGaussian() * 9.0));
			steps.add(new MouseStep(MouseEvent.MOUSE_MOVED,
				target, MouseEvent.NOBUTTON, 0, 0, delay));
		}

		return steps;
	}

	/**
	 * Human reaction time before the hand starts moving: mostly 100-250 ms,
	 * occasionally a little slower, never a long pause.
	 */
	private long reactionDelayMs()
	{
		if (random.nextInt(25) == 0)
		{
			return 250 + random.nextInt(150);
		}

		double gaussian = random.nextGaussian();
		return Math.max(90L, Math.min(300L, Math.round(165.0 + gaussian * 45.0)));
	}

	/**
	 * Fast, mostly straight movement. Duration follows Fitts' law
	 * (about 160 ms for 50 px, 330 ms for 500 px) with a slight arc, a small
	 * overshoot-free ease-out and light jitter.
	 */
	private List<MouseStep> buildFastMoveSteps(Point start, Point target)
	{
		List<MouseStep> steps = new ArrayList<>();
		long delay = reactionDelayMs();

		double dx = target.x - start.x;
		double dy = target.y - start.y;
		double distance = Math.hypot(dx, dy);

		if (distance < 2.0)
		{
			steps.add(new MouseStep(MouseEvent.MOUSE_MOVED, target, MouseEvent.NOBUTTON, 0, 0, delay));
			return steps;
		}

		double log = Math.log(1.0 + distance / 25.0) / Math.log(2.0);
		long duration = Math.round((55.0 + 62.0 * log) * (0.9 + random.nextDouble() * 0.2));
		int moveSteps = (int) Math.max(6, Math.min(30, duration / 11));

		double length = Math.max(1.0, distance);
		double perpX = -dy / length;
		double perpY = dx / length;
		double bend = (random.nextDouble() * 2.0 - 1.0) * Math.min(22.0, distance * 0.05);
		double control1X = start.x + dx * 0.3 + perpX * bend;
		double control1Y = start.y + dy * 0.3 + perpY * bend;
		double control2X = start.x + dx * 0.75 + perpX * bend * 0.4;
		double control2Y = start.y + dy * 0.75 + perpY * bend * 0.4;

		for (int i = 1; i <= moveSteps; i++)
		{
			double progress = (double) i / moveSteps;
			double eased = 1.0 - Math.pow(1.0 - progress, 2.6);

			double x = cubic(start.x, control1X, control2X, target.x, eased);
			double y = cubic(start.y, control1Y, control2Y, target.y, eased);

			if (i < moveSteps)
			{
				double jitter = (1.0 - progress) * Math.min(2.5, Math.max(0.5, distance * 0.006));
				x += (random.nextDouble() * 2.0 - 1.0) * jitter;
				y += (random.nextDouble() * 2.0 - 1.0) * jitter;
			}
			else
			{
				x = target.x;
				y = target.y;
			}

			delay += Math.max(4, duration / moveSteps + random.nextInt(5) - 2);
			steps.add(new MouseStep(MouseEvent.MOUSE_MOVED,
				new Point((int) Math.round(x), (int) Math.round(y)),
				MouseEvent.NOBUTTON, 0, 0, delay));
		}

		return steps;
	}

	private void appendClickSteps(List<MouseStep> steps, Point target, int button)
	{
		if (fastMouse)
		{
			long pressDelay = steps.get(steps.size() - 1).delayMs + 12 + random.nextInt(30);
			int downMask = button == MouseEvent.BUTTON3
				? InputEvent.BUTTON3_DOWN_MASK
				: InputEvent.BUTTON1_DOWN_MASK;

			steps.add(new MouseStep(MouseEvent.MOUSE_PRESSED, target, button, 1, downMask, pressDelay));
			long releaseDelay = pressDelay + 40 + random.nextInt(40);
			steps.add(new MouseStep(MouseEvent.MOUSE_RELEASED, target, button, 1, 0, releaseDelay));
			steps.add(new MouseStep(MouseEvent.MOUSE_CLICKED, target, button, 1, 0,
				releaseDelay + 2 + random.nextInt(8)));
			return;
		}

		long pressDelay = steps.get(steps.size() - 1).delayMs
			+ gaussianDelay(46.0, 15.0, 18, 110);
		int downMask = button == MouseEvent.BUTTON3
			? InputEvent.BUTTON3_DOWN_MASK
			: InputEvent.BUTTON1_DOWN_MASK;

		steps.add(new MouseStep(MouseEvent.MOUSE_PRESSED, target, button, 1, downMask, pressDelay));
		long releaseDelay = pressDelay + gaussianDelay(62.0, 17.0, 30, 130);
		steps.add(new MouseStep(MouseEvent.MOUSE_RELEASED, target, button, 1, 0, releaseDelay));
		steps.add(new MouseStep(MouseEvent.MOUSE_CLICKED, target, button, 1, 0,
			releaseDelay + gaussianDelay(20.0, 6.0, 6, 45)));
	}

	/** Gaussian human timing, clamped so a bad roll cannot stall the input. */
	private long gaussianDelay(double mean, double deviation, long min, long max)
	{
		long value = Math.round(mean + random.nextGaussian() * deviation);
		return Math.max(min, Math.min(max, value));
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
		HardwareInput backend = hardwareInput;
		if (backend == null)
		{
			// Never synthesize AWT events: no OS backend means no input.
			refuseNoBackend("mouse input");
			return;
		}
		try
		{
			Point screen = toScreenPoint(canvas, point);
			switch (id)
			{
				case MouseEvent.MOUSE_MOVED:
					backend.movePointer(screen.x, screen.y);
					break;
				case MouseEvent.MOUSE_PRESSED:
					backend.movePointer(screen.x, screen.y);
					backend.mouseButton(button, true);
					break;
				case MouseEvent.MOUSE_RELEASED:
					backend.mouseButton(button, false);
					break;
				case MouseEvent.MOUSE_CLICKED:
					// The OS generates the click from the press and release
					// above, so there is nothing left to send.
					break;
				default:
					break;
			}

			backend.flush();
		}
		catch (RuntimeException ex)
		{
			backendFailed(ex);
		}
	}

	/**
	 * Converts a game canvas point to absolute screen pixels, which is what
	 * the OS input backends need.
	 */
	private Point toScreenPoint(Canvas canvas, Point canvasPoint)
	{
		if (hardwareInput == null || canvas == null || canvasPoint == null)
		{
			return canvasPoint;
		}

		try
		{
			java.awt.Point origin = canvas.getLocationOnScreen();
			java.awt.GraphicsConfiguration configuration = canvas.getGraphicsConfiguration();
			AffineTransform transform = configuration == null
				? new AffineTransform()
				: configuration.getDefaultTransform();
			Point2D device = transform.transform(
				new Point2D.Double(origin.x + canvasPoint.x, origin.y + canvasPoint.y), null);
			return new Point((int) Math.round(device.getX()), (int) Math.round(device.getY()));
		}
		catch (RuntimeException ex)
		{
			return canvasPoint;
		}
	}

	/**
	 * Converts absolute screen pixels back to game canvas coordinates.
	 */
	private Point toCanvasPointFromScreen(Canvas canvas, Point screenPoint)
	{
		if (canvas == null || screenPoint == null)
		{
			return null;
		}

		try
		{
			java.awt.Point origin = canvas.getLocationOnScreen();
			java.awt.GraphicsConfiguration configuration = canvas.getGraphicsConfiguration();
			AffineTransform transform = configuration == null
				? new AffineTransform()
				: configuration.getDefaultTransform();
			Point2D user = transform.inverseTransform(
				new Point2D.Double(screenPoint.x, screenPoint.y), null);
			return new Point((int) Math.round(user.getX() - origin.x),
				(int) Math.round(user.getY() - origin.y));
		}
		catch (Exception ex)
		{
			return screenPoint;
		}
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
			int x = (int) Math.round(centerX + (random.nextDouble() * 2.0 - 1.0) * bounds.width * 0.15);
			int y = (int) Math.round(centerY + (random.nextDouble() * 2.0 - 1.0) * bounds.height * 0.15);
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

	/**
	 * Picks a point near the middle of the bounds, with a small random offset.
	 * Widgets are clicked in their centre so the cursor lands on the item or
	 * button itself instead of its edge.
	 */
	private Point centerPoint(Rectangle bounds)
	{
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		int spreadX = Math.max(1, bounds.width / 8);
		int spreadY = Math.max(1, bounds.height / 8);
		int x = bounds.x + bounds.width / 2 + random.nextInt(spreadX * 2 + 1) - spreadX;
		int y = bounds.y + bounds.height / 2 + random.nextInt(spreadY * 2 + 1) - spreadY;
		return new Point(x, y);
	}

	private Point randomPoint(Rectangle bounds)
	{
		if (bounds == null || bounds.isEmpty())
		{
			return null;
		}

		// Keep every click near the middle of the widget. Picking uniformly
		// from the inner bounds used to put clicks on the very edge of an
		// item icon, which looks synthetic and occasionally misclicks.
		int spreadX = Math.max(1, bounds.width / 8);
		int spreadY = Math.max(1, bounds.height / 8);
		int x = bounds.x + bounds.width / 2 + random.nextInt(spreadX * 2 + 1) - spreadX;
		int y = bounds.y + bounds.height / 2 + random.nextInt(spreadY * 2 + 1) - spreadY;
		return new Point(x, y);
	}

	/**
	 * Finds the index of the first action matching any candidate. Colour tags
	 * are ignored, because object and NPC actions are stored with embedded
	 * {@code <col=...>} tags while callers use the plain option name.
	 */
	private static int findAction(String[] actions, String... candidates)
	{
		if (actions == null || candidates == null)
		{
			return -1;
		}

		for (int i = 0; i < actions.length; i++)
		{
			String action = actions[i];
			if (action == null)
			{
				continue;
			}

			String plainAction = stripTags(action);
			for (String candidate : candidates)
			{
				if (candidate != null && plainAction.equalsIgnoreCase(stripTags(candidate)))
				{
					return i;
				}
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
