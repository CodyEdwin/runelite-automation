package net.runelite.mobilehd;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.GraphicsConfiguration;
import java.awt.geom.AffineTransform;
import java.io.File;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.hooks.DrawCallbacks;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.api.events.BeforeRender;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.gpu.GpuPlugin;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Mobile HD",
	description = "Lightweight graphics boost for the GPU plugin: color grading, FXAA, sharpening and smoother fog",
	tags = {"hd", "graphics", "gpu", "fxaa", "sharpen", "color", "fog", "mobile"}
)
public class MobileHdPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(MobileHdPlugin.class);

	private static final String FOG_LINE = "vec3 mixedColor = mix(c.rgb, fogColor.rgb, fFogAmount);";
	private static final String SMOOTH_FOG = "float mhdFog = smoothstep(0.0, 1.0, fFogAmount);\n"
		+ "  c.rgb = mix(c.rgb, vec3(dot(c.rgb, vec3(0.299, 0.587, 0.114))), mhdFog * 0.3);\n"
		+ "  vec3 mixedColor = mix(c.rgb, fogColor.rgb, mhdFog);";

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private MobileHdConfig config;

	private final PostProcess post = new PostProcess();
	private HookedCallbacks hook;
	private Field fboScene;
	private Field fboWidth;
	private Field fboHeight;
	private boolean fogApplied;

	private volatile boolean grading;
	private volatile boolean fxaa;
	private volatile float sharpen;
	private volatile float saturation;
	private volatile float contrast;
	private volatile float warmth;

	@Provides
	MobileHdConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MobileHdConfig.class);
	}

	@Override
	protected void startUp() throws Exception
	{
		fboScene = gpuField("fboScene");
		fboWidth = gpuField("lastStretchedCanvasWidth");
		fboHeight = gpuField("lastStretchedCanvasHeight");
		loadConfig();
		clientThread.invokeLater(() ->
		{
			attach();
			applyFog(config.smoothFog());
		});
	}

	@Override
	protected void shutDown()
	{
		clientThread.invoke(() ->
		{
			if (client.getDrawCallbacks() == hook && hook != null)
			{
				client.setDrawCallbacks(hook.gpu);
			}
			hook = null;
			post.free();
			applyFog(false);
		});
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!MobileHdConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		loadConfig();
		if ("smoothFog".equals(e.getKey()))
		{
			clientThread.invokeLater(() -> applyFog(config.smoothFog()));
		}
	}

	@Subscribe
	public void onBeforeRender(BeforeRender e)
	{
		attach();
	}

	private void loadConfig()
	{
		grading = config.colorGrading();
		fxaa = config.fxaa();
		sharpen = config.sharpen() / 100f * 0.5f;
		saturation = config.saturation() / 100f;
		contrast = config.contrast() / 100f;
		warmth = config.warmth() / 100f * 0.06f;
	}

	/** Wraps the GPU plugin's draw callbacks; re-wraps if the GPU plugin restarts. */
	private void attach()
	{
		DrawCallbacks cb = client.getDrawCallbacks();
		if (cb instanceof GpuPlugin)
		{
			hook = new HookedCallbacks(cb, this::postPass);
			client.setDrawCallbacks(hook);
			log.info("Mobile HD: attached to GPU plugin");
			if (config.smoothFog() && !fogApplied)
			{
				applyFog(true);
			}
		}
		else if (cb != hook)
		{
			hook = null;
			fogApplied = false;
		}
	}

	private void postPass()
	{
		boolean grade = grading;
		if (!grade && !fxaa && sharpen <= 0 || post.failed() || hook == null)
		{
			return;
		}
		try
		{
			Object gpu = hook.gpu;
			int fbo = fboScene.getInt(gpu);
			int w = fboWidth.getInt(gpu);
			int h = fboHeight.getInt(gpu);
			if (fbo <= 0 || w <= 0 || h <= 0)
			{
				return;
			}
			Canvas canvas = client.getCanvas();
			GraphicsConfiguration gc = canvas == null ? null : canvas.getGraphicsConfiguration();
			if (gc != null)
			{
				AffineTransform t = gc.getDefaultTransform();
				w = (int) (w * t.getScaleX());
				h = (int) (h * t.getScaleY());
			}
			post.run(fbo, w, h, fxaa, sharpen,
				grade ? saturation : 1f, grade ? contrast : 1f, grade ? warmth : 0f);
		}
		catch (Throwable t)
		{
			log.warn("Mobile HD: post pass failed, disabling", t);
			hook = null;
		}
	}

	/**
	 * The GPU plugin loads shaders from the classpath, and on Android a writable directory sits ahead of
	 * RuneLite's jar. Write a fog-patched frag.glsl there and have the GPU plugin recompile.
	 */
	private void applyFog(boolean on)
	{
		String dir = System.getProperty("runelite.android.gpuDir");
		if (dir == null || on == fogApplied)
		{
			return;
		}
		try
		{
			String src;
			try (InputStream in = GpuPlugin.class.getResourceAsStream("frag.glsl"))
			{
				if (in == null)
				{
					return;
				}
				src = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace(SMOOTH_FOG, FOG_LINE);
			}
			if (on)
			{
				if (!src.contains(FOG_LINE))
				{
					log.warn("Mobile HD: fog line not found in frag.glsl, smooth fog unavailable");
					return;
				}
				src = src.replace(FOG_LINE, SMOOTH_FOG);
			}
			File out = new File(dir, "net/runelite/client/plugins/gpu/frag.glsl");
			out.getParentFile().mkdirs();
			Files.write(out.toPath(), src.getBytes(StandardCharsets.UTF_8));
			fogApplied = on;

			DrawCallbacks cb = client.getDrawCallbacks();
			Object gpu = cb instanceof HookedCallbacks ? ((HookedCallbacks) cb).gpu : cb;
			if (gpu instanceof GpuPlugin)
			{
				Method shutdown = GpuPlugin.class.getDeclaredMethod("shutdownProgram");
				Method init = GpuPlugin.class.getDeclaredMethod("initProgram");
				shutdown.setAccessible(true);
				init.setAccessible(true);
				shutdown.invoke(gpu);
				init.invoke(gpu);
				log.info("Mobile HD: smooth fog {}", on ? "on" : "off");
			}
		}
		catch (Throwable t)
		{
			log.warn("Mobile HD: fog patch failed", t);
		}
	}

	private static Field gpuField(String name) throws NoSuchFieldException
	{
		Field f = GpuPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}
