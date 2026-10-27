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
import net.runelite.rsbuddy.input.GameInput;
import net.runelite.rsbuddy.inventory.Inventory;
import net.runelite.rsbuddy.script.Script;
import net.runelite.rsbuddy.script.ScriptContext;
import net.runelite.rsbuddy.script.ScriptLoader;
import net.runelite.rsbuddy.script.ScriptManager;
import net.runelite.rsbuddy.scripts.BarbarianFisherCookerScript;
import net.runelite.rsbuddy.scripts.CombatTrainerScript;
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
		scriptManager.register(new BarbarianFisherCookerScript());
		scriptManager.register(new CombatTrainerScript());
		scriptManager.register(new HillGiantScript());

		String customScriptError = registerCustomScript(config.customScriptClass());

		panel = new TestPluginPanel(scriptManager, config, configManager, this::startScript);
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
			default:
				chat("Usage: ::rsbuddy [status|list|start [name]|stop]");
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

	void startScript(Script script)
	{
		if (script == null || scriptManager == null)
		{
			return;
		}

		Script target = script;

		// Recreate config-backed scripts so changes are picked up when starting from the panel.
		if (script instanceof ObjectInteractScript)
		{
			target = new ObjectInteractScript(
				config.targetObjectName(),
				config.targetAction(),
				config.maxDistance());
		}

		scriptManager.start(target);
		chat("RSBuddy: started " + target.getName());
	}

	private void chat(String message)
	{
		clientThread.invokeLater(() ->
			client.addChatMessage(ChatMessageType.GAMEMESSAGE, "", message, null));
	}
}
