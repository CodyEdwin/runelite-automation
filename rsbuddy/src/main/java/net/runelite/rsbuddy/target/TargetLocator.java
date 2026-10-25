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
package net.runelite.rsbuddy.target;

import java.awt.Polygon;
import java.awt.Rectangle;
import java.awt.Shape;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.runelite.api.Client;
import net.runelite.api.GameObject;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.Player;
import net.runelite.api.Scene;
import net.runelite.api.Tile;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.widgets.Widget;

/**
 * Finds nearby game objects, NPCs, and widgets by name or id.
 */
public final class TargetLocator
{
	private static final Pattern TAGS = Pattern.compile("<[^>]*>");

	private final Client client;

	public TargetLocator(Client client)
	{
		this.client = client;
	}

	public Optional<GameObject> findObject(String name, int maxDistance)
	{
		return findObject(name, null, maxDistance);
	}

	public Optional<GameObject> findObject(String name, String action, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return objects()
			.filter(object -> matchesName(object, name))
			.filter(object -> action == null || action.isEmpty() || hasAction(object, action))
			.filter(object -> withinDistance(object.getWorldLocation(), playerLocation, maxDistance))
			.min(Comparator.comparingInt(object -> distance(object.getWorldLocation(), playerLocation)));
	}

	public Optional<GameObject> findObject(int objectId, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return objects()
			.filter(object -> object.getId() == objectId)
			.filter(object -> withinDistance(object.getWorldLocation(), playerLocation, maxDistance))
			.min(Comparator.comparingInt(object -> distance(object.getWorldLocation(), playerLocation)));
	}

	/**
	 * Finds the nearest matching object that is currently visible on screen.
	 */
	public Optional<GameObject> findVisibleObject(int objectId, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return objects()
			.filter(object -> object.getId() == objectId)
			.filter(object -> withinDistance(object.getWorldLocation(), playerLocation, maxDistance))
			.filter(this::isVisible)
			.min(Comparator.comparingInt(object -> distance(object.getWorldLocation(), playerLocation)));
	}

	/**
	 * Finds the nearest matching object of any tile-object type. This includes
	 * game objects, ground objects such as fires, wall objects, and decorative
	 * objects.
	 */
	public Optional<TileObject> findTileObject(int objectId, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return tileObjects()
			.filter(object -> object.getId() == objectId)
			.filter(object -> withinDistance(object.getWorldLocation(), playerLocation, maxDistance))
			.min(Comparator.comparingInt(object -> distance(object.getWorldLocation(), playerLocation)));
	}

	/**
	 * Finds the nearest matching tile object that is currently visible on
	 * screen.
	 */
	public Optional<TileObject> findVisibleTileObject(int objectId, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return tileObjects()
			.filter(object -> object.getId() == objectId)
			.filter(object -> withinDistance(object.getWorldLocation(), playerLocation, maxDistance))
			.filter(this::isVisible)
			.min(Comparator.comparingInt(object -> distance(object.getWorldLocation(), playerLocation)));
	}

	public List<GameObject> getObjects(String name)
	{
		return objects().filter(object -> matchesName(object, name)).collect(Collectors.toList());
	}

	public Optional<NPC> findNpc(String name, int maxDistance)
	{
		return findNpc(name, null, maxDistance);
	}

	public Optional<NPC> findNpc(String name, String action, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return npcs()
			.filter(npc -> matchesName(npc, name))
			.filter(npc -> action == null || action.isEmpty() || hasAction(npc, action))
			.filter(npc -> withinDistance(npc.getWorldLocation(), playerLocation, maxDistance))
			.min(Comparator.comparingInt(npc -> distance(npc.getWorldLocation(), playerLocation)));
	}

	public Optional<NPC> findNpc(int npcId, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return npcs()
			.filter(npc -> npc.getId() == npcId)
			.filter(npc -> withinDistance(npc.getWorldLocation(), playerLocation, maxDistance))
			.min(Comparator.comparingInt(npc -> distance(npc.getWorldLocation(), playerLocation)));
	}

	/**
	 * Finds the nearest matching NPC that is currently visible on screen.
	 */
	public Optional<NPC> findVisibleNpc(int npcId, int maxDistance)
	{
		Player player = client.getLocalPlayer();
		WorldPoint playerLocation = player == null ? null : player.getWorldLocation();

		return npcs()
			.filter(npc -> npc.getId() == npcId)
			.filter(npc -> withinDistance(npc.getWorldLocation(), playerLocation, maxDistance))
			.filter(this::isVisible)
			.min(Comparator.comparingInt(npc -> distance(npc.getWorldLocation(), playerLocation)));
	}

	public Optional<Widget> findWidget(int componentId)
	{
		return Optional.ofNullable(client.getWidget(componentId))
			.filter(widget -> !widget.isHidden());
	}

	public Optional<Widget> findWidget(int interfaceId, int childId)
	{
		return Optional.ofNullable(client.getWidget(interfaceId, childId))
			.filter(widget -> !widget.isHidden());
	}

	private Stream<GameObject> objects()
	{
		return tiles().flatMap(tile ->
		{
			GameObject[] gameObjects = tile.getGameObjects();
			return gameObjects == null ? Stream.empty() : Arrays.stream(gameObjects);
		})
			.filter(Objects::nonNull);
	}

	private Stream<TileObject> tileObjects()
	{
		return tiles().flatMap(tile ->
		{
			List<TileObject> objects = new ArrayList<>();
			GameObject[] gameObjects = tile.getGameObjects();
			if (gameObjects != null)
			{
				Collections.addAll(objects, gameObjects);
			}
			if (tile.getGroundObject() != null)
			{
				objects.add(tile.getGroundObject());
			}
			if (tile.getWallObject() != null)
			{
				objects.add(tile.getWallObject());
			}
			if (tile.getDecorativeObject() != null)
			{
				objects.add(tile.getDecorativeObject());
			}

			return objects.stream().filter(Objects::nonNull);
		});
	}

	private Stream<Tile> tiles()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return Stream.empty();
		}

		Scene scene = worldView.getScene();
		if (scene == null)
		{
			return Stream.empty();
		}

		Tile[][][] tiles = scene.getTiles();
		int plane = worldView.getPlane();
		if (tiles == null || plane < 0 || plane >= tiles.length)
		{
			return Stream.empty();
		}

		Tile[][] planeTiles = tiles[plane];
		if (planeTiles == null)
		{
			return Stream.empty();
		}

		return Arrays.stream(planeTiles)
			.filter(Objects::nonNull)
			.flatMap(row -> Arrays.stream(row))
			.filter(Objects::nonNull);
	}

	private Stream<NPC> npcs()
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return Stream.empty();
		}

		return worldView.npcs().stream()
			.map(npc -> (NPC) npc)
			.filter(npc -> !npc.isDead());
	}

	private boolean matchesName(GameObject object, String name)
	{
		if (name == null || name.isEmpty())
		{
			return true;
		}

		ObjectComposition composition = client.getObjectDefinition(object.getId());
		return composition != null && clean(composition.getName()).equalsIgnoreCase(clean(name));
	}

	private boolean matchesName(NPC npc, String name)
	{
		if (name == null || name.isEmpty())
		{
			return true;
		}

		NPCComposition composition = npc.getComposition();
		String npcName = composition == null ? npc.getName() : composition.getName();
		return clean(npcName).equalsIgnoreCase(clean(name));
	}

	private boolean hasAction(GameObject object, String action)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		return composition != null && hasAction(composition.getActions(), action);
	}

	private boolean hasAction(NPC npc, String action)
	{
		NPCComposition composition = npc.getComposition();
		return composition != null && hasAction(composition.getActions(), action);
	}

	private static boolean hasAction(String[] actions, String action)
	{
		return actions != null && Arrays.stream(actions).filter(Objects::nonNull)
			.anyMatch(candidate -> clean(candidate).equalsIgnoreCase(clean(action)));
	}

	private boolean isVisible(TileObject object)
	{
		Shape clickbox = object.getClickbox();
		if (clickbox != null && isInViewport(clickbox))
		{
			return true;
		}

		Polygon tilePoly = object.getCanvasTilePoly();
		return tilePoly != null && isInViewport(tilePoly);
	}

	private boolean isVisible(NPC npc)
	{
		Shape hull = npc.getConvexHull();
		if (hull != null && isInViewport(hull))
		{
			return true;
		}

		Polygon tilePoly = npc.getCanvasTilePoly();
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

		// Shape.intersects is unreliable here: RuneLite's SimplePolygon only
		// returns true when the viewport border crosses the polygon, not when
		// the whole polygon is inside the viewport. Compare bounds instead.
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

	private static boolean withinDistance(WorldPoint point, WorldPoint origin, int maxDistance)
	{
		return maxDistance <= 0 || origin == null || distance(point, origin) <= maxDistance;
	}

	private static int distance(WorldPoint point, WorldPoint origin)
	{
		return point == null || origin == null ? Integer.MAX_VALUE : point.distanceTo(origin);
	}

	private static String clean(String value)
	{
		return value == null ? "" : TAGS.matcher(value).replaceAll("").trim();
	}
}
