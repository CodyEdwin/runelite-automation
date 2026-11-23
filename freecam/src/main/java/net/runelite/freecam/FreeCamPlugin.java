package net.runelite.freecam;

import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import net.runelite.client.input.KeyListener;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.CollisionData;
import net.runelite.api.CollisionDataFlag;
import net.runelite.api.GameState;
import net.runelite.api.Perspective;
import net.runelite.api.Player;
import net.runelite.api.WorldView;
import net.runelite.api.coords.LocalPoint;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.KeyManager;
import net.runelite.client.input.MouseAdapter;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;

@PluginDescriptor(
	name = "Free Cam",
	description = "Backtick toggles a ground-level explore camera. WASD to walk with collision, Shift to run, mouse to look.",
	tags = {"camera", "fps", "explore", "fly"}
)
public class FreeCamPlugin extends Plugin implements KeyListener
{
	private static final int EYE_HEIGHT = 180;
	private static final int RADIUS = 24;
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
	private int lastMouseX = -1, lastMouseY;

	@Inject
	private MouseManager mouseManager;

	private static final double LOOK = 3.0; // camera units per pixel

	private final MouseAdapter look = new MouseAdapter()
	{
		@Override
		public MouseEvent mouseMoved(MouseEvent e)
		{
			turn(e);
			return e;
		}

		@Override
		public MouseEvent mouseDragged(MouseEvent e)
		{
			turn(e);
			return e;
		}
	};

	/** Mouse-look: moving right turns right, moving up looks up. */
	private void turn(MouseEvent e)
	{
		if (!active)
		{
			return;
		}
		int mx = e.getX(), my = e.getY();
		int dx = mx - lastMouseX, dy = my - lastMouseY;
		// In FPS mode the app puts the hidden cursor back at 45%/50% of the screen near the edges;
		// landing exactly there is a recenter, not a turn. Huge jumps are touch taps.
		java.awt.Dimension screen = java.awt.Toolkit.getDefaultToolkit().getScreenSize();
		boolean home = e.getXOnScreen() == Math.round(screen.width * 0.45f) && e.getYOnScreen() == Math.round(screen.height * 0.5f);
		boolean jump = lastMouseX < 0 || home || Math.abs(dx) > 300 || Math.abs(dy) > 300;
		lastMouseX = mx;
		lastMouseY = my;
		if (jump || (dx == 0 && dy == 0))
		{
			return;
		}
		clientThread.invokeLater(() ->
		{
			int yaw = (client.getCameraYawTarget() - (int) (dx * LOOK)) & 2047;
			int pitch = Math.max(128, Math.min(383, client.getCameraPitchTarget() - (int) (dy * LOOK)));
			client.setCameraYawTarget(yaw);
			client.setCameraPitchTarget(pitch);
		});
	}

	@Override
	protected void startUp()
	{
		keyManager.registerKeyListener(this);
		mouseManager.registerMouseListener(look);
	}

	@Override
	protected void shutDown()
	{
		keyManager.unregisterKeyListener(this);
		mouseManager.unregisterMouseListener(look);
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
		// Heights are negative and scene coordinates positive, so the smaller one is the height axis.
		heightIsY = fy < fz;
		x = lp.getX();
		y = lp.getY();
		lastNanos = System.nanoTime();
		lastMouseX = -1;
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
			// Forward is from the camera to the point it looks at, so it always matches the view.
			double fx = x - client.getCameraFpX(), fy = y - client.getCameraFpY();
			double fl = Math.hypot(fx, fy);
			if (fl < 1)
			{
				double yaw = client.getCameraFpYaw();
				fx = -Math.sin(yaw);
				fy = Math.cos(yaw);
				fl = 1;
			}
			fx /= fl;
			fy /= fl;
			double sx = fy, sy = -fx;                       // right
			double len = Math.hypot(f, s), step = (run ? RUN : WALK) * dt / len;
			WorldView wv0 = client.getTopLevelWorldView();
			double dx = (fx * f + sx * s) * step, dy = (fy * f + sy * s) * step;
			if (canMove(wv0, x, y, x + dx + Math.signum(dx) * RADIUS, y))
			{
				x += dx;
			}
			if (canMove(wv0, x, y, x, y + dy + Math.signum(dy) * RADIUS))
			{
				y += dy;
			}
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

	/** Same rules as walking: blocked tiles and walls between tiles stop you. */
	private static boolean canMove(WorldView wv, double x0, double y0, double x1, double y1)
	{
		int ax = (int) x0 >> 7, ay = (int) y0 >> 7, bx = (int) x1 >> 7, by = (int) y1 >> 7;
		if (ax == bx && ay == by)
		{
			return true;
		}
		CollisionData[] maps = wv.getCollisionMaps();
		if (maps == null || maps[wv.getPlane()] == null)
		{
			return true;
		}
		int[][] fl = maps[wv.getPlane()].getFlags();
		if (bx < 0 || by < 0 || bx >= fl.length || by >= fl[0].length || ax < 0 || ay < 0 || ax >= fl.length || ay >= fl[0].length)
		{
			return false;
		}
		int a = fl[ax][ay], b = fl[bx][by];
		if ((b & CollisionDataFlag.BLOCK_MOVEMENT_FULL) != 0)
		{
			return false;
		}
		if (bx > ax && ((a & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0 || (b & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0))
		{
			return false;
		}
		if (bx < ax && ((a & CollisionDataFlag.BLOCK_MOVEMENT_WEST) != 0 || (b & CollisionDataFlag.BLOCK_MOVEMENT_EAST) != 0))
		{
			return false;
		}
		if (by > ay && ((a & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0 || (b & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0))
		{
			return false;
		}
		return !(by < ay && ((a & CollisionDataFlag.BLOCK_MOVEMENT_SOUTH) != 0 || (b & CollisionDataFlag.BLOCK_MOVEMENT_NORTH) != 0));
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
