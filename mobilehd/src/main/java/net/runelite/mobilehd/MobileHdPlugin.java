package net.runelite.mobilehd;

import com.google.inject.Provides;
import java.awt.Canvas;
import java.awt.GraphicsConfiguration;
import java.awt.geom.AffineTransform;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
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
	description = "Lightweight graphics boost for the GPU plugin: ground textures, sun lighting, bloom, color grading, FXAA and sharpening",
	tags = {"hd", "graphics", "gpu", "fxaa", "sharpen", "color", "fog", "textures", "mobile"}
)
public class MobileHdPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(MobileHdPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private MobileHdConfig config;

	private final PostProcess post = new PostProcess();
	private final GroundTextures ground = new GroundTextures();
	private HookedCallbacks hook;
	private Field fboScene;
	private Field fboWidth;
	private Field fboHeight;
	private String appliedScene;

	private volatile boolean grading;
	private volatile boolean fxaa;
	private volatile float sharpen;
	private volatile float saturation;
	private volatile float contrast;
	private volatile float warmth;
	private volatile float bloom;
	private volatile float vignette;
	private volatile DetailLevel detail = DetailLevel.MEDIUM;

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
			applyScene();
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
			restoreScene();
			ground.free();
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
		clientThread.invokeLater(this::applyScene);
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
		bloom = config.bloom() ? config.bloomStrength() / 100f * 0.8f : 0f;
		vignette = config.vignette() ? config.vignetteStrength() / 100f * 0.6f : 0f;
		detail = config.detailLevel();
	}

	/** Wraps the GPU plugin's draw callbacks; re-wraps if the GPU plugin restarts. */
	private void attach()
	{
		DrawCallbacks cb = client.getDrawCallbacks();
		if (cb instanceof GpuPlugin)
		{
			hook = new HookedCallbacks(cb, this::postPass, this::preScene);
			client.setDrawCallbacks(hook);
			log.info("Mobile HD: attached to GPU plugin");
			// A (re)started GPU plugin has a new GL context and compiled whatever shaders were on disk.
			ground.forget();
			appliedScene = null;
			clientThread.invokeLater(this::applyScene);
		}
		else if (cb != hook)
		{
			hook = null;
		}
	}

	private void postPass()
	{
		boolean grade = grading;
		if (!grade && !fxaa && sharpen <= 0 && bloom <= 0 && vignette <= 0 || post.failed() || hook == null)
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
				grade ? saturation : 1f, grade ? contrast : 1f, grade ? warmth : 0f, bloom, detail, vignette);
		}
		catch (Throwable t)
		{
			log.warn("Mobile HD: post pass failed, disabling", t);
			hook = null;
		}
	}

	private void preScene()
	{
		try
		{
			ground.bind();
		}
		catch (Throwable t)
		{
			log.warn("Mobile HD: ground texture bind failed", t);
			ground.free();
		}
	}

	/** Writes scene shaders for the current settings and has the GPU plugin recompile if they changed. */
	private void applyScene()
	{
		if (!SceneShaders.available() || hook == null)
		{
			return;
		}
		boolean textures = false;
		if (config.groundTextures())
		{
			try
			{
				textures = ground.ensure();
			}
			catch (Throwable t)
			{
				log.warn("Mobile HD: ground textures unavailable", t);
			}
		}
		String sig = SceneShaders.signature(config, textures);
		if (sig.equals(appliedScene))
		{
			return;
		}
		try
		{
			if (!SceneShaders.write(config, textures))
			{
				log.warn("Mobile HD: GPU shaders changed, scene effects unavailable");
				return;
			}
			appliedScene = sig;
			recompile();
		}
		catch (Throwable t)
		{
			log.warn("Mobile HD: scene shader patch failed", t);
		}
	}

	private void restoreScene()
	{
		if (!SceneShaders.available() || appliedScene == null)
		{
			return;
		}
		try
		{
			SceneShaders.writeStock();
			appliedScene = null;
			recompile();
		}
		catch (Throwable t)
		{
			log.warn("Mobile HD: scene shader restore failed", t);
		}
	}

	private void recompile() throws ReflectiveOperationException
	{
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
			log.info("Mobile HD: GPU shaders recompiled ({})", appliedScene);
		}
	}

	private static Field gpuField(String name) throws NoSuchFieldException
	{
		Field f = GpuPlugin.class.getDeclaredField(name);
		f.setAccessible(true);
		return f;
	}
}
