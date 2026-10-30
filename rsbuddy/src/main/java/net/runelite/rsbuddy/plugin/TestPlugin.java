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
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import javax.inject.Inject;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.CommandExecuted;
import net.runelite.api.events.GameTick;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
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
import net.runelite.rsbuddy.script.ReloadingClassLoader;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;
import net.runelite.rsbuddy.script.ScriptLoader;
import net.runelite.rsbuddy.script.ScriptManager;
import net.runelite.rsbuddy.scripts.ArdougneBakerStallStealerScript;
import net.runelite.rsbuddy.scripts.BarbarianFisherCookerScript;
import net.runelite.rsbuddy.scripts.CatherbySharkFisherScript;
import net.runelite.rsbuddy.scripts.CombatTrainerScript;
import net.runelite.rsbuddy.scripts.FruitStallStealerScript;
import net.runelite.rsbuddy.scripts.GemCrabKillerScript;
import net.runelite.rsbuddy.scripts.GrandExchangeFlipperScript;
import net.runelite.rsbuddy.scripts.HillGiantScript;
import net.runelite.rsbuddy.scripts.ObjectInteractScript;
import net.runelite.rsbuddy.target.TargetLocator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@PluginDescriptor(
	name = "Test Plugin",
	description = "RSBuddy script development scaffold with a script panel",
	tags = {"test", "example", "dev", "script", "rsbuddy"},
	developerPlugin = true
)
public class TestPlugin extends Plugin
{
	private static final Logger log = LoggerFactory.getLogger(TestPlugin.class);

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private TestConfig config;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OverlayManager overlayManager;

	@Inject
	private MouseManager mouseManager;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private TestOverlay overlay;

	@Inject
	private XpProgressOverlay xpProgressOverlay;

	@Inject
	private XpLoggerOverlay xpLoggerOverlay;

	@Inject
	private MouseOverlay mouseOverlay;

	private volatile GameInput input;
	private volatile ScriptManager scriptManager;
	private volatile WidgetWatcher watcher;
	private volatile GeProbe probe;
	private TestPluginPanel panel;
	private NavigationButton navButton;

	@Provides
	TestConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(TestConfig.class);
	}

	@Override
	protected void startUp()
	{
		input = new GameInput(client);
		ScriptContext scriptContext = new ScriptContext(client, input, new TargetLocator(client), new Inventory(client, input));
		scriptManager = new ScriptManager(scriptContext);
		scriptManager.register(new ObjectInteractScript(
			config.targetObjectName(),
			config.targetAction(),
			config.maxDistance()));
		scriptManager.register(new ArdougneBakerStallStealerScript());
		scriptManager.register(new BarbarianFisherCookerScript());
		scriptManager.register(new CatherbySharkFisherScript());
		scriptManager.register(new CombatTrainerScript());
		scriptManager.register(new FruitStallStealerScript(
			config.fruitStallStayAtOneStall(),
			config.fruitStallKeepPapaya(),
			config.fruitStallKeepGolovanova(),
			config.fruitStallKeepStrangeFruit(),
			config.fruitStallKeepJangerberries()));
		scriptManager.register(new HillGiantScript());
		scriptManager.register(new GemCrabKillerScript());
		scriptManager.register(new GrandExchangeFlipperScript());

		String customScriptError = registerCustomScript(config.customScriptClass());

		panel = new TestPluginPanel(scriptManager, config, configManager, this::startScript, this::reloadScript);
		panel.setCustomScriptError(customScriptError);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "rsbuddy_icon.png");
		navButton = NavigationButton.builder()
			.tooltip("RSBuddy Scripts")
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
		if (scriptManager != null)
		{
			scriptManager.stop();
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

		if (scriptManager != null)
		{
			scriptManager.onGameTick();
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
			Script active = scriptManager == null ? null : scriptManager.getActiveScript();
			if (active == null)
			{
				chat("RSBuddy: no script running");
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
				startScriptByName(args.length > 1 ? args[1] : null);
				break;
			case "stop":
				scriptManager.stop();
				chat("RSBuddy: stopped");
				break;
			case "list":
				chat("RSBuddy scripts: " + scriptManager.getScripts().stream()
					.map(Script::getName)
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

	ScriptManager getScriptManager()
	{
		return scriptManager;
	}

	GameInput getInput()
	{
		return input;
	}

	private String registerCustomScript(String className)
	{
		if (className == null || className.trim().isEmpty())
		{
			return null;
		}

		try
		{
			Script script = ScriptLoader.load(className, getClass().getClassLoader());
			scriptManager.register(script);
			return null;
		}
		catch (ScriptLoader.ScriptLoadException ex)
		{
			log.warn("Unable to load custom script {}", className, ex);
			return ex.getMessage();
		}
	}

	private void startScriptByName(String name)
	{
		Optional<Script> script = scriptManager.getScripts().stream()
			.filter(candidate -> name == null
				|| candidate.getName().equalsIgnoreCase(name)
				|| candidate.getClass().getSimpleName().equalsIgnoreCase(name))
			.findFirst();

		if (script.isEmpty())
		{
			chat("RSBuddy: script not found");
			return;
		}

		startScript(script.get());
	}

	Script startScript(Script script)
	{
		if (script == null || scriptManager == null)
		{
			return script;
		}

		Script target = script;

		// Recreate the script with the current config. Reloaded scripts are
		// created through their own class loader so the new code is kept.
		try
		{
			target = createScript(script.getClass().getName(), script.getClass().getClassLoader());
		}
		catch (ScriptLoader.ScriptLoadException ex)
		{
			log.warn("Unable to recreate script {}, keeping the existing instance",
				script.getClass().getName(), ex);
			chat("RSBuddy: could not recreate " + script.getName() + ": " + ex.getMessage());
		}

		scriptManager.replace(script, target);
		scriptManager.start(target);
		chat("RSBuddy: started " + target.getName());
		return target;
	}

	/**
	 * Loads the selected script again from the newest compiled classes and
	 * replaces the registered instance. The running script is stopped if it is
	 * the one being reloaded.
	 */
	Script reloadScript(Script script)
	{
		if (script == null || scriptManager == null)
		{
			return script;
		}

		// When the client is running from the source tree, rebuild the module
		// first so the reload always picks up the latest edits.
		File projectDirectory = resolveProjectDirectory();
		if (projectDirectory != null)
		{
			rebuildScripts(projectDirectory);
		}

		File classesRoot = resolveScriptClassesDirectory();
		if (classesRoot == null || !classesRoot.isDirectory())
		{
			throw new IllegalStateException("Compiled script classes not found. Build the rsbuddy module first.");
		}

		String className = script.getClass().getName();
		File classFile = new File(classesRoot, className.replace('.', '/') + ".class");
		if (!classFile.isFile())
		{
			throw new IllegalStateException("No compiled class for " + className + " in " + classesRoot);
		}

		Script replacement;
		try
		{
			ClassLoader loader = new ReloadingClassLoader(classesRoot, getClass().getClassLoader());
			replacement = createScript(className, loader);
		}
		catch (MalformedURLException ex)
		{
			throw new IllegalStateException("Invalid script classes directory: " + classesRoot, ex);
		}
		catch (ScriptLoader.ScriptLoadException ex)
		{
			throw new IllegalStateException(ex.getMessage(), ex);
		}

		scriptManager.replace(script, replacement);
		log.info("Reloaded script {} from {}", className, classesRoot);
		return replacement;
	}

	private Script createScript(String className, ClassLoader classLoader)
		throws ScriptLoader.ScriptLoadException
	{
		if (ObjectInteractScript.class.getName().equals(className))
		{
			return ScriptLoader.load(className, classLoader,
				config.targetObjectName(), config.targetAction(), config.maxDistance());
		}

		if (FruitStallStealerScript.class.getName().equals(className))
		{
			return ScriptLoader.load(className, classLoader,
				config.fruitStallStayAtOneStall(),
				config.fruitStallKeepPapaya(),
				config.fruitStallKeepGolovanova(),
				config.fruitStallKeepStrangeFruit(),
				config.fruitStallKeepJangerberries());
		}

		return ScriptLoader.load(className, classLoader);
	}

	private File resolveProjectDirectory()
	{
		File workingDirectory = new File(System.getProperty("user.dir", "."));
		for (File directory = workingDirectory; directory != null; directory = directory.getParentFile())
		{
			if (new File(directory, "gradlew").isFile()
				&& new File(directory, "rsbuddy/build.gradle.kts").isFile())
			{
				return directory;
			}
		}

		return null;
	}

	private void rebuildScripts(File projectDirectory)
	{
		File wrapper = new File(projectDirectory, "gradlew");
		if (!wrapper.isFile())
		{
			return;
		}

		List<String> command = new ArrayList<>();
		if (wrapper.canExecute())
		{
			command.add(wrapper.getAbsolutePath());
		}
		else
		{
			command.add("sh");
			command.add(wrapper.getAbsolutePath());
		}
		command.add(":rsbuddy:classes");
		command.add("--console=plain");

		ProcessBuilder builder = new ProcessBuilder(command);
		builder.directory(projectDirectory);
		builder.redirectErrorStream(true);

		try
		{
			Process process = builder.start();
			StringBuilder output = new StringBuilder();
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8)))
			{
				String line;
				while ((line = reader.readLine()) != null)
				{
					output.append(line).append('\n');
				}
			}

			int exitCode = process.waitFor();
			if (exitCode != 0)
			{
				log.warn("Script rebuild failed:\n{}", output);
				throw new IllegalStateException("Build failed: " + lastLines(output.toString(), 4));
			}

			log.info("Rebuilt scripts in {}", projectDirectory);
		}
		catch (IOException ex)
		{
			throw new IllegalStateException("Unable to run Gradle: " + ex.getMessage(), ex);
		}
		catch (InterruptedException ex)
		{
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Build interrupted", ex);
		}
	}

	private static String lastLines(String output, int count)
	{
		if (output == null || output.trim().isEmpty())
		{
			return "(no output)";
		}

		String[] lines = output.trim().split("\\R");
		int start = Math.max(0, lines.length - count);
		return String.join(" | ", java.util.Arrays.copyOfRange(lines, start, lines.length));
	}

	private File resolveScriptClassesDirectory()
	{
		String configured = config.scriptClassesDirectory();
		if (configured != null && !configured.trim().isEmpty())
		{
			return resolveAgainstWorkingDirectory(configured.trim());
		}

		String property = System.getProperty("rsbuddy.scriptClasses");
		if (property != null && !property.trim().isEmpty())
		{
			return resolveAgainstWorkingDirectory(property.trim());
		}

		File workingDirectory = new File(System.getProperty("user.dir", "."));
		for (File directory = workingDirectory; directory != null; directory = directory.getParentFile())
		{
			File candidate = new File(directory, "rsbuddy/build/classes/java/main");
			if (isScriptClassesDirectory(candidate))
			{
				return candidate;
			}

			if ("rsbuddy".equals(directory.getName()))
			{
				candidate = new File(directory, "build/classes/java/main");
				if (isScriptClassesDirectory(candidate))
				{
					return candidate;
				}
			}
		}

		try
		{
			File codeSource = new File(getClass().getProtectionDomain().getCodeSource()
				.getLocation().toURI());
			if (codeSource.isDirectory() && isScriptClassesDirectory(codeSource))
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
					if (isScriptClassesDirectory(candidate))
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

	private static boolean isScriptClassesDirectory(File directory)
	{
		return directory != null
			&& new File(directory, "net/runelite/rsbuddy/scripts").isDirectory();
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
