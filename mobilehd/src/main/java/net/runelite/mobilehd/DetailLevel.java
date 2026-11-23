package net.runelite.mobilehd;

public enum DetailLevel
{
	LOW("Low", 1, 1280, 8, 1),
	MEDIUM("Medium", 2, 2560, 4, 1),
	HIGH("High", 3, 3840, 4, 2),
	ULTRA("Ultra", 4, 5760, 2, 2);

	private final String name;
	/** Noise layers in the ground detail. */
	final int octaves;
	/** Distance in world units (128 per tile) where ground detail fades out. */
	final int detailDistance;
	/** Bloom buffer is 1/n of the screen size. */
	final int bloomDivisor;
	final int bloomBlurPasses;

	DetailLevel(String name, int octaves, int detailDistance, int bloomDivisor, int bloomBlurPasses)
	{
		this.name = name;
		this.octaves = octaves;
		this.detailDistance = detailDistance;
		this.bloomDivisor = bloomDivisor;
		this.bloomBlurPasses = bloomBlurPasses;
	}

	@Override
	public String toString()
	{
		return name;
	}
}
