/*
 * Copyright (c) 2026, reich
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package net.runelite.rsbuddy.plugin;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ClientTick;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.MenuEntryAdded;
import net.runelite.client.callback.ClientThread;
import javax.swing.SwingUtilities;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.input.MouseManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.rsbuddy.debug.GeProbe;
import net.runelite.rsbuddy.debug.WidgetDumper;
import net.runelite.rsbuddy.debug.WidgetWatcher;
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.routine.JarRoutineLoader;
import net.runelite.rsbuddy.routine.ReloadingClassLoader;
import net.runelite.rsbuddy.routine.RoutineJarWriter;
import net.runelite.rsbuddy.routine.Routine;
import net.runelite.rsbuddy.routine.RoutineCompiler;
import net.runelite.rsbuddy.routine.RoutineContext;
import net.runelite.rsbuddy.routine.RoutineLoader;
import net.runelite.rsbuddy.routine.RoutineManager;
import net.runelite.rsbuddy.target.TargetLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "RSBuddy",
	configName = "testplugin",
	description = "Routine library for RuneLite",
	tags = {"routine", "rsbuddy"},
	developerPlugin = true
)
public class RSBuddyPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(RSBuddyPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private RSBuddyConfig config;

	private static final List<String> ANIMATED_SUIT_KEYS = List.of(
		"animatedSuitBlack", "animatedSuitMithril", "animatedSuitAdamant", "animatedSuitRune");

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private RSBuddyOverlay overlay;

	@Inject
	private XpProgressOverlay xpProgressOverlay;

	@Inject
	private XpLoggerOverlay xpLoggerOverlay;

	@Inject
	private MouseOverlay mouseOverlay;

	private volatile GameInput input;
	private volatile RoutineManager routineManager;
	private volatile RoutineRegistry registry;
	private volatile WidgetWatcher watcher;
	private volatile GeProbe probe;
	private RoutineListPanel panel;
	private JarRoutineLoader jarLoader;
	private final JarRoutineLoader.Listener jarListener = new JarRoutineLoader.Listener()
	{
		@Override
		public void removed(JarRoutineLoader.LoadedJar jar)
		{
			unregisterJar(jar);
		}

		@Override
		public void added(JarRoutineLoader.LoadedJar jar)
		{
			registerJar(jar);
		}
	};
	private NavigationButton navButton;

	@Provides
	RSBuddyConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(RSBuddyConfig.class);
	}

	@Override
	protected void startUp()
	{
		migrateConfig();
		input = new GameInput(client);
		input.setBackendPreference(config.inputBackend());
		RoutineContext routineContext = new RoutineContext(client, input, new TargetLocator(client), new Inventory(client, input));
		routineContext.setSettingsSource(key -> configManager.getConfiguration(RSBuddyConfig.GROUP, key));
		routineManager = new RoutineManager(routineContext);
		registry = RoutineLibrary.create(config);

		registerCustomRoutine(config.customRoutineClass());
		String customError = null;
		List<String> failed = new ArrayList<>();
		for (RoutineDefinition definition : new ArrayList<>(registry.getDefinitions()))
		{
			try
			{
				Routine routine = definition.create(getClass().getClassLoader());
				definition.setInstance(routine);
				routineManager.register(routine);
			}
			catch (RoutineLoader.RoutineLoadException ex)
			{
				log.warn("Unable to create routine {}", definition.getId(), ex);
				failed.add(definition.getId());
				if (customError == null)
				{
					customError = ex.getMessage();
				}
			}
		}
		for (String id : failed)
		{
			registry.remove(id);
		}

		panel = new RoutineListPanel(routineManager, registry, config, new ConfigRoutinePreferences(configManager),
			this::startRoutine, this::reloadRoutine, this::loadCustomRoutine);
		panel.setInputStatusSupplier(input::getBackendStatus);
		jarLoader = new JarRoutineLoader(getClass().getClassLoader());
		panel.setJarActions(this::rescanJars, this::exportRoutineJar);
		panel.rescan();
		panel.setNotice(customError);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "rsbuddy_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("RSBuddy")
			.icon(icon)
			.priority(1)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);
		overlayManager.add(overlay);
		overlayManager.add(xpProgressOverlay);
		overlayManager.add(xpLoggerOverlay);
		overlayManager.add(mouseOverlay);
		mouseManager.registerMouseListener(mouseOverlay);
		log.info("RSBuddy started");
	}

	@Override
	protected void shutDown()
	{
		if (routineManager != null)
		{
			routineManager.stop();
		}

		if (jarLoader != null)
		{
			jarLoader.closeAll(jarListener);
			jarLoader = null;
		}

		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}

		if (panel != null)
		{
			panel.shutdown();
		}

		overlayManager.remove(overlay);
		overlayManager.remove(xpProgressOverlay);
		overlayManager.remove(xpLoggerOverlay);
		overlayManager.remove(mouseOverlay);
		mouseManager.unregisterMouseListener(mouseOverlay);
		if (input != null)
		{
			input.reset();
			input.shutdown();
		}
		input = null;
		log.info("RSBuddy stopped");
	}

	@Subscribe
	public void onGameTick(GameTick event)
	{
		WidgetWatcher current = watcher;
		if (current != null)
		{
			if (current.isExpired())
			{
				watcher = null;
				String error = current.getLastError();
				chat("RSBuddy: watch finished after " + current.getSnapshots() + " snapshot(s)"
					+ (error == null || error.isEmpty() ? "" : " (last error: " + error + ")"));
			}
			else
			{
				current.tick(client);
			}
		}

		GeProbe currentProbe = probe;
		if (currentProbe != null)
		{
			if (currentProbe.isFinished())
			{
				probe = null;
			}
			else
			{
				currentProbe.tick();
			}
		}

		if (routineManager != null)
		{
			routineManager.onGameTick();
		}
	}

	/**
	 * The four Animated suit armour checkboxes act like radio buttons:
	 * ticking one unticks the other three. Unticking the last one is allowed;
	 * the routine then refuses to run until exactly one is ticked.
	 */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (RSBuddyConfig.GROUP.equals(event.getGroup()) && "inputBackend".equals(event.getKey()) && input != null)
		{
			input.setBackendPreference(config.inputBackend());
		}

		if (RSBuddyConfig.GROUP.equals(event.getGroup()) && panel != null)
		{
			// Keep the side panel's routine settings in sync with the config
			// (Swing work on the EDT).
			final RoutineListPanel target = panel;
			final String changedKey = event.getKey();
			SwingUtilities.invokeLater(() -> target.configChanged(changedKey));
		}

		if (!RSBuddyConfig.GROUP.equals(event.getGroup()) || !"true".equals(event.getNewValue()))
		{
			return;
		}

		String key = event.getKey();
		if (!ANIMATED_SUIT_KEYS.contains(key))
		{
			return;
		}

		for (String other : ANIMATED_SUIT_KEYS)
		{
			if (!other.equals(key))
			{
				configManager.setConfiguration(RSBuddyConfig.GROUP, other, false);
			}
		}
	}

	/**
	 * Per-frame (client thread) update for tracked ground-pile looting: the
	 * pile's screen point and hover check are recomputed here and published
	 * to the mouse thread, which never reads the client itself.
	 */
	@Subscribe
	public void onClientTick(ClientTick event)
	{
		GameInput current = input;
		if (current != null)
		{
			current.updatePileTracking();
		}
		if (routineManager != null)
		{
			routineManager.onClientTick();
		}
	}

	@Subscribe
	public void onMenuEntryAdded(MenuEntryAdded event)
	{
		GameInput current = input;
		if (current != null)
		{
			current.filterGroundItemMenuEntry(event.getMenuEntry());
		}
	}

	@Subscribe
	public void onCommandExecuted(CommandExecuted event)
	{
		if (!"rsbuddy".equalsIgnoreCase(event.getCommand()))
		{
			return;
		}

		String[] args = event.getArguments();
		if (args.length == 0 || "status".equalsIgnoreCase(args[0]))
		{
			Routine active = routineManager == null ? null : routineManager.getActiveRoutine();
			if (active == null)
			{
				chat("RSBuddy: no routine running");
			}
			else
			{
				chat("RSBuddy: " + active.getName() + " - " + active.getStatus());
			}
			return;
		}

		switch (args[0].toLowerCase(Locale.ROOT))
		{
			case "start":
				startRoutineByName(args.length > 1 ? args[1] : null);
				break;
			case "stop":
				routineManager.stop();
				chat("RSBuddy: stopped");
				break;
			case "list":
				chat("RSBuddy routines: " + routineManager.getRoutines().stream()
					.map(Routine::getName)
					.reduce((left, right) -> left + ", " + right)
					.orElse("none"));
				break;
			case "widgets":
				dumpWidgets(String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length)));
				break;
			case "watch":
				startWatch(args.length > 1 ? args[1] : null);
				break;
			case "probe":
				startProbe();
				break;
			default:
				chat("Usage: ::rsbuddy [status|list|start [name]|stop|widgets [label]|watch [seconds]]");
				break;
		}
	}

	RoutineManager getRoutineManager()
	{
		return routineManager;
	}

	GameInput getInput()
	{
		return input;
	}

	private void registerCustomRoutine(String className)
	{
		className = normalizeRoutineClass(className);
		if (className == null || className.isEmpty() || registry.find(className) != null)
		{
			return;
		}

		final String id = className;
		registry.register(id, loader -> RoutineLoader.load(id, loader), null);
	}

	private String loadCustomRoutine(String className)
	{
		className = normalizeRoutineClass(className);
		if (className == null || className.isEmpty())
		{
			return "Enter a routine class name";
		}

		configManager.setConfiguration(RSBuddyConfig.GROUP, "customRoutineClass", className);
		if (registry.find(className) != null)
		{
			return null;
		}

		final String id = className;
		try
		{
			Routine routine = RoutineLoader.load(id, getClass().getClassLoader());
			RoutineDefinition definition = registry.register(id, loader -> RoutineLoader.load(id, loader), null);
			definition.setInstance(routine);
			routineManager.register(routine);
			return null;
		}
		catch (RoutineLoader.RoutineLoadException ex)
		{
			log.warn("Unable to load custom routine {}", className, ex);
			return ex.getMessage();
		}
	}

	private void startRoutineByName(String name)
	{
		Optional<Routine> routine = routineManager.getRoutines().stream()
			.filter(candidate -> name == null
				|| candidate.getName().equalsIgnoreCase(name)
				|| candidate.getClass().getSimpleName().equalsIgnoreCase(name))
			.findFirst();

		if (routine.isEmpty())
		{
			chat("RSBuddy: routine not found");
			return;
		}

		startRoutine(routine.get());
	}

	Routine startRoutine(Routine routine)
	{
		if (routine == null || routineManager == null || registry == null)
		{
			return routine;
		}

		Routine target = routine;

		// Recreate the routine with the current config. Reloaded routines are
		// created through their own class loader so the new code is kept.
		try
		{
			// Look the entry up by instance first so a jar routine is recreated
			// from its own jar even when a built-in has the same class name.
			RoutineDefinition definition = registry.findByInstance(routine);
			target = definition != null
				? definition.create(routine.getClass().getClassLoader())
				: registry.create(routine.getClass().getName(), routine.getClass().getClassLoader());
		}
		catch (RoutineLoader.RoutineLoadException ex)
		{
			log.warn("Unable to recreate routine {}, keeping the existing instance",
				routine.getClass().getName(), ex);
			chat("RSBuddy: could not recreate " + routine.getName() + ": " + ex.getMessage());
		}

		registry.replaceInstance(routine, target);
		routineManager.replace(routine, target);
		routineManager.start(target);
		chat("RSBuddy: started " + target.getName());
		return target;
	}

	/**
	 * Loads the selected routine again from the newest compiled classes and
	 * replaces the registered instance. The running routine is stopped if it is
	 * the one being reloaded.
	 */
	Routine reloadRoutine(Routine routine)
	{
		if (routine == null || routineManager == null || registry == null)
		{
			return routine;
		}

		File classesRoot = compileRoutineClasses("[Reload]");

		String className = routine.getClass().getName();
		File classFile = new File(classesRoot, className.replace('.', '/') + ".class");
		if (!classFile.isFile())
		{
			throw new IllegalStateException("No compiled class for " + className + " in " + classesRoot);
		}

		Routine replacement;
		try
		{
			ClassLoader loader = new ReloadingClassLoader(classesRoot, getClass().getClassLoader(),
				Client.class.getClassLoader(), net.runelite.api.EquipmentInventorySlot.class.getClassLoader(),
				ConfigManager.class.getClassLoader());
			replacement = registry.create(className, loader);
		}
		catch (MalformedURLException ex)
		{
			throw new IllegalStateException("Invalid routine classes directory: " + classesRoot, ex);
		}
		catch (RoutineLoader.RoutineLoadException ex)
		{
			throw new IllegalStateException(ex.getMessage(), ex);
		}

		registry.replaceInstance(routine, replacement);
		routineManager.replace(routine, replacement);
		log.info("[Reload] reloaded {} from {}", className, classesRoot);
		return replacement;
	}

	/**
	 * "Export as jar": compiles the routine sources in-process (no Gradle) and
	 * writes the selected routine, its nested classes and the services file
	 * to {@code <routines folder>/<SimpleName>.jar}. Runs off the Swing thread;
	 * the panel rescans afterwards so the jar shows up marked (jar).
	 */
	File exportRoutineJar(Routine routine)
	{
		if (routine == null)
		{
			throw new IllegalStateException("[JarRoutines] no routine selected");
		}
		File classesRoot = compileRoutineClasses("[JarRoutines]");
		String className = routine.getClass().getName();
		File jar = new File(resolveRoutineJarDirectory(), routine.getClass().getSimpleName() + ".jar");
		try
		{
			int classes = RoutineJarWriter.write(classesRoot, className, jar);
			log.info("[JarRoutines] exported {} ({} class files) to {}", className, classes, jar);
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("[JarRoutines] export failed: " + ex.getMessage(), ex);
		}
		return jar;
	}

	/** The routine jar folder: the panel/config setting, else ~/.runelite/rsbuddy/routines. */
	private File resolveRoutineJarDirectory()
	{
		String configured = config.routineJarDirectory();
		if (configured != null && !configured.trim().isEmpty())
		{
			return resolveAgainstWorkingDirectory(configured.trim());
		}
		return new File(System.getProperty("user.home"), ".runelite/rsbuddy/routines");
	}

	/**
	 * Syncs the registry with the jar folder. Called on the Swing thread at
	 * startup, on Reload and on Rescan.
	 *
	 * @return a short summary for the panel status line
	 */
	String rescanJars()
	{
		if (jarLoader == null)
		{
			return null;
		}
		File folder = resolveRoutineJarDirectory();
		if (!folder.isDirectory() && !folder.mkdirs())
		{
			log.warn("[JarRoutines] cannot create routine folder {}", folder);
		}
		jarLoader.rescan(folder, jarListener);
		int routines = 0;
		for (JarRoutineLoader.LoadedJar jar : jarLoader.getLoaded())
		{
			routines += jar.getRoutines().size();
		}
		log.info("[JarRoutines] {} jar(s), {} routine(s) in {}", jarLoader.getLoaded().size(), routines, folder);
		return routines + " jar routine(s) in " + folder.getName() + ".";
	}

	private static String jarId(JarRoutineLoader.LoadedJar jar, Class<?> type)
	{
		return "jar:" + jar.getFile().getName() + "!" + type.getName();
	}

	private void registerJar(JarRoutineLoader.LoadedJar jar)
	{
		for (Class<? extends Routine> type : jar.getRoutines())
		{
			String id = jarId(jar, type);
			try
			{
				// Same path as built-ins: RoutineManager applies the
				// physical-click lock on start, and the panel draws the
				// routine's declared RoutineSettings (no settings builder).
				Routine routine = jar.create(type);
				RoutineDefinition definition = registry.register(id, loader -> jar.create(type), null);
				definition.setJarFile(jar.getFile());
				definition.setInstance(routine);
				routineManager.register(routine);
			}
			catch (RoutineLoader.RoutineLoadException | LinkageError ex)
			{
				log.warn("[JarRoutines] skipping {} in {}: {}", type.getName(), jar.getFile().getName(), ex.getMessage());
				registry.remove(id);
			}
		}
	}

	private void unregisterJar(JarRoutineLoader.LoadedJar jar)
	{
		for (Class<? extends Routine> type : jar.getRoutines())
		{
			RoutineDefinition definition = registry.find(jarId(jar, type));
			if (definition == null)
			{
				continue;
			}
			if (definition.getInstance() != null && definition.getInstance() == routineManager.getActiveRoutine())
			{
				log.info("[JarRoutines] stopping {}: its jar was removed or changed", definition.getDisplayName());
			}
			routineManager.unregister(definition.getInstance());
			registry.remove(definition.getId());
		}
	}

	/**
	 * Compiles the routine sources into a fresh temp folder (or falls back to
	 * prebuilt classes) and returns it.
	 */
	private File compileRoutineClasses(String prefix)
	{
		// Never run Gradle here: it cannot rebuild while the client holds the
		// build output. Compile the routine sources in-process into a fresh
		// temp directory instead and load them with a child-first loader.
		File sourceRoot = resolveRoutineSourceDirectory();
		File classesRoot;
		if (sourceRoot != null)
		{
			log.info(prefix + " compiling routines from {}", sourceRoot);
			long started = System.currentTimeMillis();
			try
			{
				classesRoot = RoutineCompiler.compile(sourceRoot, getClass().getClassLoader(),
					getClass(), Routine.class, Client.class, net.runelite.api.EquipmentInventorySlot.class,
					ConfigManager.class, Logger.class);
			}
			catch (IllegalStateException ex)
			{
				log.warn(ex.getMessage());
				throw ex;
			}
			log.info(prefix + " compiled routines in {}ms into {}", System.currentTimeMillis() - started, classesRoot);
		}
		else
		{
			classesRoot = resolveRoutineClassesDirectory();
			log.info(prefix + " routine sources not found, loading prebuilt classes from {}", classesRoot);
		}
		if (classesRoot == null || !classesRoot.isDirectory())
		{
			throw new IllegalStateException("[Reload] routine sources not found. Set 'Routine source directory'"
				+ " in the panel to your rsbuddy/src/main/java folder.");
		}
		return classesRoot;
	}

	/**
	 * Finds the src/main/java root that holds the routine sources: the panel
	 * setting, then -Drsbuddy.routineSources (or the previous -Drsbuddy.scriptSources),
	 * then the working directory and its parents, then next to the plugin's build output.
	 */
	private File resolveRoutineSourceDirectory()
	{
		List<File> candidates = new ArrayList<>();
		String configured = config.routineSourceDirectory();
		if (configured != null && !configured.trim().isEmpty())
		{
			File dir = resolveAgainstWorkingDirectory(configured.trim());
			if (isRoutineSourceDirectory(dir))
			{
				return dir;
			}
			// Accept the repo root or the rsbuddy module too.
			candidates.add(new File(dir, "rsbuddy/src/main/java"));
			candidates.add(new File(dir, "src/main/java"));
			for (File candidate : candidates)
			{
				if (isRoutineSourceDirectory(candidate))
				{
					return candidate;
				}
			}
			throw new IllegalStateException("[Reload] 'Routine source directory' " + dir
				+ " has no " + RoutineCompiler.ROUTINES_PATH + " folder");
		}

		String property = firstProperty("rsbuddy.routineSources", "rsbuddy.scriptSources");
		if (property != null)
		{
			candidates.add(resolveAgainstWorkingDirectory(property));
		}

		for (File directory = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
			directory != null; directory = directory.getParentFile())
		{
			candidates.add(new File(directory, "rsbuddy/src/main/java"));
			candidates.add(new File(directory, "src/main/java"));
		}

		try
		{
			File codeSource = new File(getClass().getProtectionDomain().getCodeSource().getLocation().toURI());
			// build/libs/rsbuddy.jar or build/classes/java/main -> module root
			for (File directory = codeSource; directory != null; directory = directory.getParentFile())
			{
				if ("build".equals(directory.getName()) && directory.getParentFile() != null)
				{
					candidates.add(new File(directory.getParentFile(), "src/main/java"));
					break;
				}
			}
		}
		catch (Exception ex)
		{
			log.debug("Unable to inspect plugin code source", ex);
		}

		for (File candidate : candidates)
		{
			if (isRoutineSourceDirectory(candidate))
			{
				return candidate;
			}
		}
		return null;
	}

	private static boolean isRoutineSourceDirectory(File root)
	{
		return root != null && new File(root, RoutineCompiler.ROUTINES_PATH).isDirectory();
	}

	private File resolveRoutineClassesDirectory()
	{
		String configured = config.routineClassesDirectory();
		if (configured != null && !configured.trim().isEmpty())
		{
			return resolveAgainstWorkingDirectory(configured.trim());
		}

		String property = firstProperty("rsbuddy.routineClasses", "rsbuddy.scriptClasses");
		if (property != null)
		{
			return resolveAgainstWorkingDirectory(property);
		}

		File workingDirectory = new File(System.getProperty("user.dir", "."));
		for (File directory = workingDirectory; directory != null; directory = directory.getParentFile())
		{
			File candidate = new File(directory, "rsbuddy/build/classes/java/main");
			if (isRoutineClassesDirectory(candidate))
			{
				return candidate;
			}

			if ("rsbuddy".equals(directory.getName()))
			{
				candidate = new File(directory, "build/classes/java/main");
				if (isRoutineClassesDirectory(candidate))
				{
					return candidate;
				}
			}
		}

		try
		{
			File codeSource = new File(getClass().getProtectionDomain().getCodeSource()
				.getLocation().toURI());
			if (codeSource.isDirectory() && isRoutineClassesDirectory(codeSource))
			{
				return codeSource;
			}

			if (codeSource.isFile())
			{
				File buildDirectory = codeSource.getParentFile() == null
					? null
					: codeSource.getParentFile().getParentFile();
				if (buildDirectory != null)
				{
					File candidate = new File(buildDirectory, "classes/java/main");
					if (isRoutineClassesDirectory(candidate))
					{
						return candidate;
					}
				}
			}
		}
		catch (Exception ex)
		{
			log.debug("Unable to inspect plugin code source", ex);
		}

		return null;
	}

	private static File resolveAgainstWorkingDirectory(String path)
	{
		File file = new File(path);
		return file.isAbsolute()
			? file
			: new File(System.getProperty("user.dir", "."), path);
	}

	private static boolean isRoutineClassesDirectory(File directory)
	{
		return directory != null
			&& new File(directory, "net/runelite/rsbuddy/routines").isDirectory();
	}

	private void migrateConfig()
	{
		moveConfig("customScriptClass", "customRoutineClass");
		moveConfig("scriptClassesDirectory", "routineClassesDirectory");
		moveConfig("scriptSourceDirectory", "routineSourceDirectory");

		String custom = configManager.getConfiguration(RSBuddyConfig.GROUP, "customRoutineClass");
		String normalized = normalizeRoutineClass(custom);
		if (normalized != null && custom != null && !normalized.equals(custom))
		{
			configManager.setConfiguration(RSBuddyConfig.GROUP, "customRoutineClass", normalized);
		}
	}

	private void moveConfig(String oldKey, String newKey)
	{
		String current = configManager.getConfiguration(RSBuddyConfig.GROUP, newKey);
		if (current != null && !current.isEmpty())
		{
			return;
		}

		String old = configManager.getConfiguration(RSBuddyConfig.GROUP, oldKey);
		if (old != null && !old.isEmpty())
		{
			configManager.setConfiguration(RSBuddyConfig.GROUP, newKey, old);
		}
	}

	private static String normalizeRoutineClass(String className)
	{
		if (className == null)
		{
			return null;
		}

		String trimmed = className.trim();
		String previous = "net.runelite.rsbuddy.scripts.";
		if (trimmed.startsWith(previous))
		{
			return "net.runelite.rsbuddy.routines." + trimmed.substring(previous.length());
		}

		return trimmed;
	}

	private static String firstProperty(String preferred, String fallback)
	{
		String property = System.getProperty(preferred);
		if (property == null || property.trim().isEmpty())
		{
			property = System.getProperty(fallback);
		}

		if (property == null || property.trim().isEmpty())
		{
			return null;
		}

		return property.trim();
	}

	private void chat(String message)
	{
		clientThread.invokeLater(() ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}

	/**
	 * Starts a timed widget watch. Snapshots are taken automatically whenever
	 * the visible interface changes, so interfaces that grab keyboard focus
	 * (like the GE item search) can be captured without typing a command.
	 */
	private void startWatch(String secondsArg)
	{
		int seconds = 60;
		if (secondsArg != null && !secondsArg.isEmpty())
		{
			try
			{
				seconds = Integer.parseInt(secondsArg.trim());
			}
			catch (NumberFormatException ignored)
			{
				// Keep the default.
			}
		}

		seconds = Math.max(5, Math.min(300, seconds));
		watcher = new WidgetWatcher(seconds);

		chat("RSBuddy: watching widgets for " + seconds + "s. Just click through the screens now.");
		chat("RSBuddy: snapshots append to " + WidgetDumper.getLogFile());
	}

	/**
	 * Runs the sell-screen probe: it opens a sell offer, hovers an inventory
	 * item, records the menu and setup widgets, then backs out without
	 * confirming anything. Used to capture interface details automatically.
	 */
	private void startProbe()
	{
		GameInput currentInput = input;
		if (currentInput == null)
		{
			chat("RSBuddy: input is not ready yet.");
			return;
		}

		probe = new GeProbe(client, currentInput, new Inventory(client, currentInput),
			message -> chat("RSBuddy: " + message));
		chat("RSBuddy: probing the GE sell screen. Do not click until it says it is done.");
	}

	/**
	 * Appends a snapshot of the visible interface to the widget dump file so
	 * interface ids and actions can be read off disk instead of hovering each
	 * element in RuneLite's widget inspector.
	 */
	private void dumpWidgets(String label)
	{
		if (label == null || label.isEmpty())
		{
			label = "manual";
		}

		try
		{
			int count = WidgetDumper.snapshot(client, label);
			chat("RSBuddy: widget snapshot '" + label + "' (" + count + " widgets) -> "
				+ WidgetDumper.getLogFile());
		}
		catch (Exception ex)
		{
			log.warn("Unable to write widget snapshot", ex);
			chat("RSBuddy: widget snapshot failed: " + ex.getClass().getSimpleName()
				+ ": " + ex.getMessage());
		}
	}
}
