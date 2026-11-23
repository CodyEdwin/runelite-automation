package net.runelite.mobilehd;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(MobileHdConfig.GROUP)
public interface MobileHdConfig extends Config
{
	String GROUP = "mobilehd";

	@ConfigSection(name = "Scene", description = "Lighting, ground and fog (changing these recompiles GPU shaders)", position = 10)
	String scene = "scene";

	@ConfigSection(name = "Screen", description = "Effects applied to the finished frame", position = 20)
	String screen = "screen";

	@ConfigItem(keyName = "detailLevel", name = "Detail level", description = "Quality of ground detail and bloom. Lower is faster", position = 0)
	default DetailLevel detailLevel()
	{
		return DetailLevel.MEDIUM;
	}

	@ConfigItem(keyName = "sunLighting", name = "Sun lighting", description = "Directional sun shading with highlights on models", position = 11, section = scene)
	default boolean sunLighting()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "sunStrength", name = "Sun strength", description = "How strong the sun shading is", position = 12, section = scene)
	default int sunStrength()
	{
		return 60;
	}

	@ConfigItem(keyName = "groundTextures", name = "Ground textures", description = "Grass, dirt, sand, rock, gravel and snow textures on ground, picked from the tile color", position = 13, section = scene)
	default boolean groundTextures()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "textureStrength", name = "Texture strength", description = "How strongly ground textures show", position = 14, section = scene)
	default int textureStrength()
	{
		return 70;
	}

	@ConfigItem(keyName = "groundGrain", name = "Ground grain", description = "Generated noise grain on ground, cheaper than textures", position = 15, section = scene)
	default boolean groundDetail()
	{
		return false;
	}

	@ConfigItem(keyName = "smoothFog", name = "Smooth fog", description = "Softer fog falloff with distance haze", position = 16, section = scene)
	default boolean smoothFog()
	{
		return true;
	}

	@ConfigItem(keyName = "colorGrading", name = "Color grading", description = "Apply saturation, contrast and warmth", position = 21, section = screen)
	default boolean colorGrading()
	{
		return true;
	}

	@Range(min = 50, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "saturation", name = "Saturation", description = "Color saturation", position = 22, section = screen)
	default int saturation()
	{
		return 120;
	}

	@Range(min = 50, max = 200)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "contrast", name = "Contrast", description = "Image contrast", position = 23, section = screen)
	default int contrast()
	{
		return 108;
	}

	@Range(min = -100, max = 100)
	@ConfigItem(keyName = "warmth", name = "Warmth", description = "Negative is cooler, positive is warmer", position = 24, section = screen)
	default int warmth()
	{
		return 20;
	}

	@ConfigItem(keyName = "bloom", name = "Bloom", description = "Soft glow around bright areas", position = 25, section = screen)
	default boolean bloom()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "bloomStrength", name = "Bloom strength", description = "How strong the glow is", position = 26, section = screen)
	default int bloomStrength()
	{
		return 40;
	}

	@ConfigItem(keyName = "vignette", name = "Vignette", description = "Darken the screen edges", position = 27, section = screen)
	default boolean vignette()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "vignetteStrength", name = "Vignette strength", description = "How dark the edges get", position = 28, section = screen)
	default int vignetteStrength()
	{
		return 35;
	}

	@ConfigItem(keyName = "fxaa", name = "FXAA", description = "Smooth jagged edges", position = 29, section = screen)
	default boolean fxaa()
	{
		return true;
	}

	@Range(max = 100)
	@Units(Units.PERCENT)
	@ConfigItem(keyName = "sharpen", name = "Sharpen", description = "Sharpen strength, 0 is off", position = 30, section = screen)
	default int sharpen()
	{
		return 35;
	}
}
