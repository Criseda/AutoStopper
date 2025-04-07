package me.criseda.autostopper;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import me.criseda.autostopper.commands.AutoStopperCommand;
import me.criseda.autostopper.commands.ServerCommandInterceptor;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.listeners.ConnectionListener;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;

import org.slf4j.Logger;

import javax.inject.Inject;
import java.nio.file.Path;

@Plugin(id = "autostopper", name = "AutoStopper", version = "1.0", authors = { "criseda" })
public class AutoStopperPlugin {
	private final ProxyServer server;
	private final Logger logger;
	private final Path dataDirectory;

	private AutoStopperConfig config;
	private ServerManager serverManager;
	private ActivityTracker activityTracker;

	@Inject
	public AutoStopperPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory) {
		this.server = server;
		this.logger = logger;
		this.dataDirectory = dataDirectory;
	}

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		logger.info("AutoStopper plugin initializing...");

		// Load configuration
		this.config = createConfig();

		// Initialize server management and activity tracking
		this.serverManager = createServerManager(config);
		this.activityTracker = createActivityTracker(config, serverManager);

		// Start monitoring
		activityTracker.startInactivityCheck();

		// Register event listeners
		server.getEventManager().register(this, new ConnectionListener(activityTracker));

		// Register commands
		server.getCommandManager().register(
				server.getCommandManager().metaBuilder("autostopper")
						.aliases("as")
						.plugin(this)
						.build(),
				new AutoStopperCommand(config, serverManager, activityTracker));

		// Register server command interceptor
		new ServerCommandInterceptor(server, this, serverManager, activityTracker);

		logger.info("AutoStopper plugin initialized!");
	}

	public ProxyServer getServer() {
		return server;
	}

	public Logger getLogger() {
		return logger;
	}

	public AutoStopperConfig getConfig() {
		return config;
	}

	public ServerManager getServerManager() {
		return serverManager;
	}

	public ActivityTracker getActivityTracker() {
		return activityTracker;
	}

	protected AutoStopperConfig createConfig() {
		return new AutoStopperConfig(dataDirectory, logger);
	}

	protected ServerManager createServerManager(AutoStopperConfig config) {
		return new ServerManager(server, logger, config);
	}

	protected ActivityTracker createActivityTracker(AutoStopperConfig config, ServerManager serverManager) {
		return new ActivityTracker(server, logger, config, serverManager);
	}
}