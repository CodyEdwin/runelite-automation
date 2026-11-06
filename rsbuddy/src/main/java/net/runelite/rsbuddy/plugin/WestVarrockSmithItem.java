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
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rsbuddy.plugin;

/**
 * Which item the West Varrock smither makes. Names match its routine Item.
 */
public enum WestVarrockSmithItem
{
	DAGGER("Dagger"),
	SWORD("Sword"),
	LONGSWORD("Longsword"),
	TWO_HANDED_SWORD("2h sword"),
	SCIMITAR("Scimitar"),
	MACE("Mace"),
	WARHAMMER("Warhammer"),
	AXE("Axe"),
	BATTLEAXE("Battleaxe"),
	CLAWS("Claws"),
	CHAINBODY("Chainbody"),
	PLATELEGS("Platelegs"),
	PLATESKIRT("Plateskirt"),
	PLATEBODY("Platebody"),
	MED_HELM("Med helm"),
	FULL_HELM("Full helm"),
	SQ_SHIELD("Sq shield"),
	KITESHIELD("Kiteshield"),
	KEEL_PARTS("Keel parts"),
	LARGE_KEEL_PARTS("Large keel parts");

	private final String label;

	WestVarrockSmithItem(String label)
	{
		this.label = label;
	}

	@Override
	public String toString()
	{
		return label;
	}
}
