package net.runelite.mobilehd;

import java.util.Set;
import net.runelite.api.GameObject;
import net.runelite.api.Model;
import net.runelite.api.Projection;
import net.runelite.api.Renderable;
import net.runelite.api.Scene;
import net.runelite.api.SceneTileModel;
import net.runelite.api.SceneTilePaint;
import net.runelite.api.Texture;
import net.runelite.api.TileObject;
import net.runelite.api.WorldView;
import net.runelite.api.hooks.DrawCallbacks;

/** Forwards every call to the GPU plugin and runs the post pass after the scene is drawn. */
final class HookedCallbacks implements DrawCallbacks
{
	final DrawCallbacks gpu;
	private final Runnable postPass;
	private boolean sceneDrawn;

	HookedCallbacks(DrawCallbacks gpu, Runnable postPass)
	{
		this.gpu = gpu;
		this.postPass = postPass;
	}

	@Override
	public void draw(int overlayColor)
	{
		if (sceneDrawn)
		{
			sceneDrawn = false;
			postPass.run();
		}
		gpu.draw(overlayColor);
	}

	@Override
	public void postSceneDraw(Scene scene)
	{
		gpu.postSceneDraw(scene);
		if (scene.getWorldViewId() == WorldView.TOPLEVEL)
		{
			sceneDrawn = true;
		}
	}

	@Override
	public void draw(Projection projection, Scene scene, Renderable renderable, int orientation, int x, int y, int z, long hash)
	{
		gpu.draw(projection, scene, renderable, orientation, x, y, z, hash);
	}

	@Override
	public void drawScenePaint(Scene scene, SceneTilePaint paint, int plane, int tileX, int tileZ)
	{
		gpu.drawScenePaint(scene, paint, plane, tileX, tileZ);
	}

	@Override
	public void drawSceneTileModel(Scene scene, SceneTileModel model, int tileX, int tileZ)
	{
		gpu.drawSceneTileModel(scene, model, tileX, tileZ);
	}

	@Override
	public void drawScene(double cameraX, double cameraY, double cameraZ, double cameraPitch, double cameraYaw, int plane)
	{
		gpu.drawScene(cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, plane);
	}

	@Override
	public void postDrawScene()
	{
		gpu.postDrawScene();
	}

	@Override
	public void animate(Texture texture, int diff)
	{
		gpu.animate(texture, diff);
	}

	@Override
	public void loadScene(Scene scene)
	{
		gpu.loadScene(scene);
	}

	@Override
	public void swapScene(Scene scene)
	{
		gpu.swapScene(scene);
	}

	@Override
	public boolean tileInFrustum(Scene scene, float pitchSin, float pitchCos, float yawSin, float yawCos, int cameraX, int cameraY, int cameraZ, int plane, int msx, int msy)
	{
		return gpu.tileInFrustum(scene, pitchSin, pitchCos, yawSin, yawCos, cameraX, cameraY, cameraZ, plane, msx, msy);
	}

	@Override
	public boolean zoneInFrustum(int zoneX, int zoneZ, int maxY, int minY)
	{
		return gpu.zoneInFrustum(zoneX, zoneZ, maxY, minY);
	}

	@Override
	public void loadScene(WorldView worldView, Scene scene)
	{
		gpu.loadScene(worldView, scene);
	}

	@Override
	public void despawnWorldView(WorldView worldView)
	{
		gpu.despawnWorldView(worldView);
	}

	@Override
	public void preSceneDraw(Scene scene, Projection entityProjection, float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		gpu.preSceneDraw(scene, entityProjection, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	@SuppressWarnings("deprecation")
	public void preSceneDraw(Scene scene, float cameraX, float cameraY, float cameraZ, float cameraPitch, float cameraYaw,
		int minLevel, int level, int maxLevel, Set<Integer> hideRoofIds)
	{
		gpu.preSceneDraw(scene, cameraX, cameraY, cameraZ, cameraPitch, cameraYaw, minLevel, level, maxLevel, hideRoofIds);
	}

	@Override
	public void drawPass(Projection entityProjection, Scene scene, int pass)
	{
		gpu.drawPass(entityProjection, scene, pass);
	}

	@Override
	public void drawZoneOpaque(Projection entityProjection, Scene scene, int zx, int zz)
	{
		gpu.drawZoneOpaque(entityProjection, scene, zx, zz);
	}

	@Override
	public void drawZoneAlpha(Projection entityProjection, Scene scene, int level, int zx, int zz)
	{
		gpu.drawZoneAlpha(entityProjection, scene, level, zx, zz);
	}

	@Override
	public void drawDynamic(Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		gpu.drawDynamic(worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawDynamic(int renderThreadId, Projection worldProjection, Scene scene, TileObject tileObject, Renderable r, Model m, int orient, int x, int y, int z)
	{
		gpu.drawDynamic(renderThreadId, worldProjection, scene, tileObject, r, m, orient, x, y, z);
	}

	@Override
	public void drawTemp(Projection worldProjection, Scene scene, GameObject gameObject, Model m, int orient, int x, int y, int z)
	{
		gpu.drawTemp(worldProjection, scene, gameObject, m, orient, x, y, z);
	}

	@Override
	public void invalidateZone(Scene scene, int zx, int zz)
	{
		gpu.invalidateZone(scene, zx, zz);
	}
}
