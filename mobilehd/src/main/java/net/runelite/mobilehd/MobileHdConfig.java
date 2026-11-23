package net.runelite.mobilehd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(MobileHdConfig.GROUP)
public interface MobileHdConfig extends Config
{
	String GROUP = "mobilehd";

	@ConfigItem(keyName = "colorGrading", name = "Color grading", description = "Apply saturation, contrast and warmth", position = 0)
	default boolean colorGrading()
	{
		return true;
	}

	@Range(min = 50, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "saturation", name = "Saturation", description = "Color saturation", position = 1)
	default int saturation()
	{
		return 120;
	}

	@Range(min = 50, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "contrast", name = "Contrast", description = "Image contrast", position = 2)
	default int contrast()
	{
		return 108;
	}

	@Range(min = -100, max = 100)
	@ConfigItem(keyName = "warmth", name = "Warmth", description = "Negative is cooler, positive is warmer", position = 3)
	default int warmth()
	{
		return 20;
	}

	@ConfigItem(keyName = "fxaa", name = "FXAA", description = "Smooth jagged edges", position = 4)
	default boolean fxaa()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "sharpen", name = "Sharpen", description = "Sharpen strength, 0 is off", position = 5)
	default int sharpen()
	{
		return 35;
	}

	@ConfigItem(keyName = "smoothFog", name = "Smooth fog", description = "Softer fog falloff with distance haze (recompiles GPU shaders)", position = 6)
	default boolean smoothFog()
	{
		return true;
	}
}
