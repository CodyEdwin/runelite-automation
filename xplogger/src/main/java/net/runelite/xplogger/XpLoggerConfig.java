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
package net.runelite.xplogger;

import java.awt.Color;
import net.runelite.client.config.Alpha;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.ui.overlay.OverlayPosition;

@ConfigGroup(XpLoggerConfig.GROUP)
public interface XpLoggerConfig extends Config
{
	String GROUP = "xplogger";

	@ConfigItem(keyName = "show", name = "Show overlay", description = "Draw the XP Logger overlay", position = 0)
	default boolean show()
	{
		return true;
	}

	@ConfigItem(keyName = "movable", name = "Movable", description = "Allow Shift-drag and Alt-drag", position = 1)
	default boolean movable()
	{
		return true;
	}

	@ConfigItem(keyName = "position", name = "Position", description = "Corner the overlay anchors to", position = 2)
	default OverlayPosition position()
	{
		return OverlayPosition.BOTTOM_RIGHT;
	}

	@ConfigItem(keyName = "title", name = "Title", description = "Title text", position = 3)
	default String title()
	{
		return "XP Logger";
	}

	@ConfigItem(keyName = "showTitle", name = "Show title", description = "Show the title", position = 4)
	default boolean showTitle()
	{
		return true;
	}

	@Alpha
	@ConfigItem(keyName = "background", name = "Background", description = "Background color and opacity", position = 5)
	default Color background()
	{
		return new Color(56, 43, 28, 220);
	}

	@Alpha
	@ConfigItem(keyName = "border", name = "Border", description = "Border color and opacity", position = 6)
	default Color border()
	{
		return new Color(120, 95, 62, 255);
	}

	@ConfigItem(keyName = "text", name = "Text", description = "Primary text color", position = 7)
	default Color text()
	{
		return new Color(235, 235, 235);
	}

	@ConfigItem(keyName = "muted", name = "Muted text", description = "Secondary text color", position = 8)
	default Color muted()
	{
		return new Color(178, 178, 178);
	}

	@ConfigItem(keyName = "gain", name = "Gain text", description = "XP gained color", position = 9)
	default Color gain()
	{
		return new Color(139, 195, 74);
	}

	@ConfigItem(keyName = "rate", name = "Rate text", description = "XP per hour color", position = 10)
	default Color rate()
	{
		return new Color(255, 213, 79);
	}

	@ConfigItem(keyName = "titleColor", name = "Title color", description = "Title color", position = 11)
	default Color titleColor()
	{
		return new Color(255, 222, 170);
	}

	@ConfigItem(keyName = "overlayFont", name = "Overlay font", description = "Font used for overlay text", position = 12)
	default String overlayFont()
	{
		return XpFonts.FAMILY;
	}

	@Range(min = 10, max = 28)
	@ConfigItem(keyName = "fontSize", name = "Font size", description = "Overlay font size", position = 13)
	default int fontSize()
	{
		return 16;
	}

	@Range(min = 0, max = 32)
	@ConfigItem(keyName = "padding", name = "Padding", description = "Inner padding in pixels", position = 13)
	default int padding()
	{
		return 8;
	}

	@Range(min = 0, max = 40)
	@ConfigItem(keyName = "rowGap", name = "Row spacing", description = "Space between skill rows", position = 14)
	default int rowGap()
	{
		return 9;
	}

	@Range(min = 0, max = 32)
	@ConfigItem(keyName = "iconSize", name = "Icon size", description = "Skill icon size, 0 hides icons", position = 15)
	default int iconSize()
	{
		return 0;
	}

	@Range(min = 160, max = 480)
	@ConfigItem(keyName = "width", name = "Width", description = "Overlay width", position = 16)
	default int width()
	{
		return 262;
	}

	@Range(min = 1, max = 23)
	@ConfigItem(keyName = "maxRows", name = "Max rows", description = "How many skills to list", position = 17)
	default int maxRows()
	{
		return 6;
	}

	@Range(min = 100, max = 400)
	@ConfigItem(keyName = "barThickness", name = "Bar thickness %", description = "Bar thickness as a percent of the original", position = 18)
	default int barThickness()
	{
		return 250;
	}

	@ConfigItem(keyName = "animate", name = "Animations", description = "Stripes, sheen, embers, sparks and the XP flash", position = 19)
	default boolean animate()
	{
		return true;
	}

	@Range(min = 0, max = 200)
	@ConfigItem(keyName = "spark", name = "Spark intensity %", description = "Spark amount, 0 is none and 100 is default", position = 20)
	default int spark()
	{
		return 100;
	}

	@ConfigItem(keyName = "climb", name = "Drops climb", description = "XP labels climb to the top of the screen", position = 21)
	default boolean climb()
	{
		return true;
	}

	@ConfigItem(keyName = "skillColors", name = "Skill bar colors", description = "Use each skill's built-in color instead of the bar fill", position = 22)
	default boolean skillColors()
	{
		return false;
	}

	@Alpha
	@ConfigItem(keyName = "barTrack", name = "Bar background", description = "Empty part of the bar", position = 23)
	default Color barTrack()
	{
		return new Color(8, 10, 18, 238);
	}

	@ConfigItem(keyName = "barFill", name = "Bar fill", description = "XP bar fill color", position = 24)
	default Color barFill()
	{
		return new Color(0, 230, 255);
	}

	@ConfigItem(keyName = "barFlash", name = "Bar flash", description = "Sheen, flash and edge glow color on an XP drop", position = 25)
	default Color barFlash()
	{
		return Color.WHITE;
	}

	@ConfigItem(keyName = "lightStrands", name = "Light strands", description = "Strands of light swirling around each bar", position = 26)
	default boolean lightStrands()
	{
		return true;
	}

	@ConfigItem(keyName = "strandColor", name = "Strand color", description = "Color of the light strands", position = 27)
	default Color strandColor()
	{
		return Color.WHITE;
	}

	@Range(min = 0, max = 200)
	@ConfigItem(keyName = "strandIntensity", name = "Strand intensity %", description = "Strand brightness, 0 is none and 100 is default", position = 28)
	default int strandIntensity()
	{
		return 100;
	}

	@ConfigItem(keyName = "showSession", name = "Session time", description = "Show how long this session has run", position = 29)
	default boolean showSession()
	{
		return true;
	}

	@ConfigItem(keyName = "showXpHr", name = "XP per hour", description = "Show XP per hour", position = 30)
	default boolean showXpHr()
	{
		return true;
	}

	@ConfigItem(keyName = "showGained", name = "XP gained", description = "Show XP gained this session", position = 31)
	default boolean showGained()
	{
		return true;
	}

	@ConfigItem(keyName = "showLeft", name = "XP left", description = "Show XP left and time to level", position = 32)
	default boolean showLeft()
	{
		return true;
	}

	@ConfigItem(keyName = "showActions", name = "Actions to level", description = "Show actions left from the XP of the last drop", position = 33)
	default boolean showActions()
	{
		return true;
	}

	@ConfigItem(keyName = "showAvg", name = "Average XP", description = "Show average XP per action", position = 34)
	default boolean showAvg()
	{
		return true;
	}

	@ConfigItem(keyName = "showLevels", name = "Levels gained", description = "Show levels gained this session", position = 35)
	default boolean showLevels()
	{
		return false;
	}

	@ConfigItem(keyName = "showPercent", name = "Bar percent", description = "Show percent on thick bars", position = 36)
	default boolean showPercent()
	{
		return true;
	}

	@ConfigItem(keyName = "showDrops", name = "XP drop labels", description = "Fly a +XP label off the bar", position = 37)
	default boolean showDrops()
	{
		return true;
	}

	@ConfigItem(keyName = "onlyGained", name = "Only gained skills", description = "Limit the list to skills that gained XP this session", position = 38)
	default boolean onlyGained()
	{
		return true;
	}

	@ConfigItem(keyName = "skills", name = "Skills", description = "Comma-separated skill names to show, or empty for every skill", position = 39)
	default String skills()
	{
		return "";
	}
}
