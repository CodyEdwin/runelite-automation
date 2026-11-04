package net.runelite.cache;

import java.io.File;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import org.junit.Test;

public class TempHeightDumpTest
{
	@Test
	public void dumpHeights() throws Exception
	{
		try (Store store = new Store(new File("/home/reich/.runelite/jagexcache/oldschool/LIVE")))
		{
			store.load();
			RegionLoader loader = new RegionLoader(store, null);
			Region region = loader.loadRegionFromArchive(14936);
			int baseX = region.getBaseX();
			int baseY = region.getBaseY();
			for (int y = 5670; y <= 5678; y++)
			{
				StringBuilder line = new StringBuilder();
				for (int x = 3752; x <= 3763; x++)
				{
					int lx = x - baseX;
					int ly = y - baseY;
					int z0 = region.getTileHeight(0, lx, ly);
					int z1 = region.getTileHeight(1, lx, ly);
					int s1 = region.getTileSetting(1, lx, ly);
					boolean bridge = (s1 & 2) == 2;
					int effective = bridge ? z1 : z0;
					line.append(String.format("%4d", effective));
				}
				System.out.println("ROW y=" + y + line);
			}
		}
	}
}
