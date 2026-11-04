package net.runelite.cache;

import java.io.File;
import net.runelite.cache.definitions.ObjectDefinition;
import net.runelite.cache.fs.Store;
import net.runelite.cache.region.Location;
import net.runelite.cache.region.Region;
import net.runelite.cache.region.RegionLoader;
import org.junit.Test;

public class TempAreaDumpTest
{
	@Test
	public void dumpArea() throws Exception
	{
		try (Store store = new Store(new File("/home/reich/.runelite/jagexcache/oldschool/LIVE")))
		{
			store.load();
			ObjectManager manager = new ObjectManager(store);
			manager.load();
			RegionLoader loader = new RegionLoader(store, null);
			Region region = loader.loadRegionFromArchive(14936);
			for (Location loc : region.getLocations())
			{
				int x = loc.getPosition().getX();
				int y = loc.getPosition().getY();
				if (x < 3755 || x > 3765 || y < 5668 || y > 5680)
				{
					continue;
				}
				int id = loc.getId();
				ObjectDefinition def = manager.getObject(id);
				String name = def == null ? "?" : def.getName();
				if (name == null || !(name.contains("vein") || name.contains("Vein") || name.contains("Hopper")))
				{
					continue;
				}
				System.out.println("AREA z=" + loc.getPosition().getZ()
					+ " id=" + id + " name=" + name + " x=" + x + " y=" + y);
			}
		}
	}
}
