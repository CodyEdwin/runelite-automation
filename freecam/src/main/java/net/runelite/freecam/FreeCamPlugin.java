package net.runelite.freecam;

import java.awt.event.KeyEvent;
import net.runelite.client.input.KeyListener;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Free Cam",
	description = "Backtick toggles a ground-level explore camera. WASD to walk, Shift to run, drag/arrows to look.",
	tags = {"camera", "fps", "explore", "fly"}
)
public class FreeCamPlugin extends Plugin implements KeyListener
{
	private static final int EYE_HEIGHT = 180;
	private static final double WALK = 900, RUN = 2400; // local units per second

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private KeyManager keyManager;

	private volatile boolean active, fwd, back, left, right, run;
	private boolean heightIsY;
	private double x, y;
	private long lastNanos;

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(this);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		clientThread.invokeLater(this::disable);
	}

	private void enable()
	{
		Player p = client.getLocalPlayer();
		if (client.getGameState() != GameState.LOGGED_IN || p == null)
		{
			return;
		}
		LocalPoint lp = p.getLocalLocation();
		client.setCameraMode(1);
		client.setFreeCameraSpeed(0);
		float fx = client.getCameraFocalPointX(), fy = client.getCameraFocalPointY(), fz = client.getCameraFocalPointZ();
		// Work out which focal axis is height: the horizontal pair is the one nearest the player.
		heightIsY = Math.abs(fx - lp.getX()) + Math.abs(fz - lp.getY()) <= Math.abs(fx - lp.getX()) + Math.abs(fy - lp.getY());
		x = lp.getX();
		y = lp.getY();
		lastNanos = System.nanoTime();
		active = true;
	}

	private void disable()
	{
		if (active)
		{
			active = false;
			client.setFreeCameraSpeed(12);
			client.setCameraMode(0);
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e)
	{
		if (!active)
		{
			return;
		}
		if (client.getGameState() != GameState.LOGGED_IN || client.getCameraMode() != 1)
		{
			active = false;
			return;
		}
		long now = System.nanoTime();
		double dt = Math.min((now - lastNanos) / 1e9, 0.1);
		lastNanos = now;

		int f = (fwd ? 1 : 0) - (back ? 1 : 0), s = (right ? 1 : 0) - (left ? 1 : 0);
		if (f != 0 || s != 0)
		{
			double yaw = client.getCameraYaw() * Perspective.UNIT;
			double fx = -Math.sin(yaw), fy = Math.cos(yaw); // forward on the ground
			double sx = fy, sy = -fx;                       // right
			double len = Math.hypot(f, s), step = (run ? RUN : WALK) * dt / len;
			x += (fx * f + sx * s) * step;
			y += (fy * f + sy * s) * step;
		}

		WorldView wv = client.getTopLevelWorldView();
		int max = wv.getSizeX() * Perspective.LOCAL_TILE_SIZE - 1;
		int maxY = wv.getSizeY() * Perspective.LOCAL_TILE_SIZE - 1;
		x = Math.max(0, Math.min(max, x));
		y = Math.max(0, Math.min(maxY, y));
		int ground = Perspective.getTileHeight(client, new LocalPoint((int) x, (int) y, wv), wv.getPlane());
		float h = ground - EYE_HEIGHT;

		client.setCameraFocalPointX((float) x);
		if (heightIsY)
		{
			client.setCameraFocalPointY(h);
			client.setCameraFocalPointZ((float) y);
		}
		else
		{
			client.setCameraFocalPointY((float) y);
			client.setCameraFocalPointZ(h);
		}
	}

	@Override
	public void keyTyped(KeyEvent e)
	{
		if (active && e.getKeyChar() != '`' && "wasdWASD".indexOf(e.getKeyChar()) >= 0)
		{
			e.consume();
		}
	}

	@Override
	public void keyPressed(KeyEvent e)
	{
		if (e.getKeyCode() == KeyEvent.VK_BACK_QUOTE)
		{
			e.consume();
			clientThread.invokeLater(() ->
			{
				if (active)
				{
					disable();
				}
				else
				{
					enable();
				}
			});
			return;
		}
		if (active && setKey(e.getKeyCode(), true))
		{
			e.consume();
		}
		run = e.isShiftDown();
	}

	@Override
	public void keyReleased(KeyEvent e)
	{
		if (active && setKey(e.getKeyCode(), false))
		{
			e.consume();
		}
		run = e.isShiftDown();
	}

	private boolean setKey(int code, boolean down)
	{
		switch (code)
		{
			case KeyEvent.VK_W: fwd = down; return true;
			case KeyEvent.VK_S: back = down; return true;
			case KeyEvent.VK_A: left = down; return true;
			case KeyEvent.VK_D: right = down; return true;
			default: return false;
		}
	}
}
