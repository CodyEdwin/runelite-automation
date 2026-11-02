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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.NPC;
import net.runelite.api.NPCComposition;
import net.runelite.api.ObjectComposition;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.coords.WorldPoint;

/**
 * Finds the bank to open: booths (including the Grand Exchange booths, which
 * are named "Grand Exchange booth", not "Bank booth"), bank chests and
 * bankers. Scans every tile-object layer and resolves impostors. Client
 * thread only.
 */
public final class BankFinder
{
	private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(BankFinder.class);

	/** The Grand Exchange "Banker" NPC Cody banks with. */
	private static final int GE_BANKER_ID = net.runelite.api.gameval.NpcID.BANKER1; // 1613

	/** A bank object or banker plus the option that opens it. */
	public static final class BankTarget
	{
		private final TileObject object;
		private final NPC npc;
		private final String action;
		private final String name;
		private final WorldPoint location;

		private BankTarget(TileObject object, NPC npc, String action, String name, WorldPoint location)
		{
			this.object = object;
			this.npc = npc;
			this.action = action;
			this.name = name;
			this.location = location;
		}

		public TileObject getObject()
		{
			return object;
		}

		public NPC getNpc()
		{
			return npc;
		}

		public String getAction()
		{
			return action;
		}

		public String getName()
		{
			return name;
		}

		public WorldPoint getLocation()
		{
			return location;
		}

		@Override
		public String toString()
		{
			return name + " (" + action + ") at " + location;
		}
	}

	private BankFinder()
	{
	}

	/**
	 * @param preferBooth true: the closest booth/chest, falling back to a
	 * banker only if there is none; false: whichever of banker, chest or
	 * booth is closest
	 * @param logPrefix prefix for the log lines
	 * @return the target, or null (after logging the nearby candidates)
	 */
	public static BankTarget find(Client client, TargetLocator targets, WorldPoint player, int radius,
		boolean preferBooth, String logPrefix)
	{
		if (player == null)
		{
			return null;
		}

		BankTarget booth = null;
		for (TileObject object : targets.getTileObjects(player, radius))
		{
			ObjectComposition composition = composition(client, object);
			String action = bankAction(composition);
			if (action == null || object.getWorldLocation() == null)
			{
				continue;
			}
			if (booth == null || object.getWorldLocation().distanceTo(player) < booth.location.distanceTo(player))
			{
				booth = new BankTarget(object, null, action, composition.getName(), object.getWorldLocation());
			}
		}

		BankTarget banker = findBanker(client, player, radius);
		BankTarget result;
		if (preferBooth)
		{
			// Closest bank of any kind: booth, chest or banker NPC (the Grand
			// Exchange is often banked through its bankers).
			result = booth == null ? banker
				: banker == null ? booth
				: banker.location.distanceTo(player) < booth.location.distanceTo(player) ? banker : booth;
		}
		else
		{
			// Prefer a banker NPC, then a booth or chest.
			result = banker != null ? banker : booth;
		}
		log.info("{} bank candidates: booth/chest={} banker={} -> {}", logPrefix, booth, banker, result);

		if (result == null)
		{
			logCandidates(client, targets, player, radius, logPrefix);
		}
		return result;
	}

	/**
	 * @return "Bank" for any object with a Bank option (bank booths, Grand
	 * Exchange booths, bank chests, counters), "Use" for a bank chest whose
	 * only option is Use, otherwise null
	 */
	public static String bankAction(ObjectComposition composition)
	{
		if (composition == null || composition.getActions() == null)
		{
			return null;
		}
		String name = composition.getName() == null ? "" : composition.getName().toLowerCase();
		if (name.contains("deposit box"))
		{
			return null;
		}
		String[] actions = composition.getActions();
		for (String action : actions)
		{
			if ("Bank".equalsIgnoreCase(action))
			{
				return "Bank";
			}
		}
		if (name.contains("bank chest") || name.contains("bank booth") || name.contains("grand exchange booth"))
		{
			for (String action : actions)
			{
				if ("Use".equalsIgnoreCase(action))
				{
					return "Use";
				}
			}
		}
		return null;
	}

	/** @return the object's composition, with its impostor (varbit state) resolved */
	public static ObjectComposition composition(Client client, TileObject object)
	{
		ObjectComposition composition = client.getObjectDefinition(object.getId());
		if (composition != null && composition.getImpostorIds() != null)
		{
			ObjectComposition impostor = composition.getImpostor();
			if (impostor != null)
			{
				composition = impostor;
			}
		}
		return composition;
	}

	private static BankTarget findBanker(Client client, WorldPoint player, int radius)
	{
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView == null)
		{
			return null;
		}
		BankTarget best = null;
		for (NPC npc : worldView.npcs())
		{
			NPCComposition composition = npc == null ? null : npc.getTransformedComposition();
			WorldPoint location = npc == null ? null : npc.getWorldLocation();
			if (composition == null || location == null
				|| location.distanceTo(player) > radius)
			{
				continue;
			}
			// Grand Exchange banker (1613), anything named Banker, or any NPC
			// with a Bank option.
			boolean bank = npc.getId() == GE_BANKER_ID || "Banker".equalsIgnoreCase(composition.getName())
				|| composition.getActions() != null && Arrays.stream(composition.getActions()).anyMatch(a -> "Bank".equalsIgnoreCase(a));
			if (bank && (best == null || location.distanceTo(player) < best.location.distanceTo(player)))
			{
				best = new BankTarget(null, npc, "Bank", composition.getName(), location);
			}
		}
		return best;
	}

	private static void logCandidates(Client client, TargetLocator targets, WorldPoint player, int radius,
		String logPrefix)
	{
		Set<String> related = new LinkedHashSet<>();
		Set<String> other = new LinkedHashSet<>();
		for (TileObject object : targets.getTileObjects(player, radius))
		{
			ObjectComposition composition = composition(client, object);
			if (composition == null || composition.getName() == null || "null".equals(composition.getName()))
			{
				continue;
			}
			String line = composition.getName() + "#" + object.getId() + " " + actionsOf(composition.getActions())
				+ " d=" + (object.getWorldLocation() == null ? "?" : object.getWorldLocation().distanceTo(player));
			String name = composition.getName().toLowerCase();
			if (name.contains("bank") || name.contains("booth") || name.contains("chest"))
			{
				related.add(line);
			}
			else if (other.size() < 15 && composition.getActions() != null
				&& Arrays.stream(composition.getActions()).anyMatch(a -> a != null))
			{
				other.add(line);
			}
		}
		List<String> npcs = new ArrayList<>();
		WorldView worldView = client.getTopLevelWorldView();
		if (worldView != null)
		{
			for (NPC npc : worldView.npcs())
			{
				NPCComposition composition = npc == null ? null : npc.getTransformedComposition();
				if (composition != null && npc.getWorldLocation() != null
					&& npc.getWorldLocation().distanceTo(player) <= radius && npcs.size() < 15)
				{
					npcs.add(composition.getName() + " " + actionsOf(composition.getActions()));
				}
			}
		}
		log.info("{} no bank found within {} tiles of {}. bank-like objects: {} | other objects: {} | npcs: {}",
			logPrefix, radius, player, related.isEmpty() ? "none" : related, other.isEmpty() ? "none" : other,
			npcs.isEmpty() ? "none" : npcs);
	}

	private static String actionsOf(String[] actions)
	{
		if (actions == null)
		{
			return "[]";
		}
		List<String> list = new ArrayList<>();
		for (String action : actions)
		{
			if (action != null)
			{
				list.add(action);
			}
		}
		return list.toString();
	}
}
