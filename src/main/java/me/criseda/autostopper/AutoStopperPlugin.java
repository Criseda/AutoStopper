package me.criseda.autostopper;

import com.velocitypowered.api.command.CommandMeta;
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

@Plugin(id = "autostopper", name = "AutoStopper", version = "1.0.1", authors = { "criseda" })
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
	
		// Load configuration first
		this.config = createConfig();
		config.loadConfig(); // Make sure config is loaded before using it
	
		// Initialize server management
		this.serverManager = createServerManager(config);
		
		// Initialize activity tracking but DON'T start the inactivity check yet
		this.activityTracker = createActivityTracker(config, serverManager);
	
		// Register event listeners
		server.getEventManager().register(this, new ConnectionListener(activityTracker));
	
		// Register commands with the new non-deprecated method
		registerCommands();
		
		// NOW start the inactivity check AFTER all registration is complete
		activityTracker.startInactivityCheck();
	
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
        return new ActivityTracker(server, logger, config, serverManager, this);
    }

	private void registerCommands() {
		logger.info("Registering AutoStopper commands...");
		
		// Use a more direct registration method
		CommandMeta autoStopperMeta = server.getCommandManager().metaBuilder("autostopper")
			.aliases("as")
			.plugin(this)
			.build();
			
		server.getCommandManager().register(autoStopperMeta,
			new AutoStopperCommand(config, serverManager, activityTracker));
		logger.info("Registered command: /autostopper");
		
		// Register server command interceptor
		try {
			server.getCommandManager().unregister("server");
			logger.info("Unregistered original server command");
			
			CommandMeta serverMeta = server.getCommandManager().metaBuilder("server")
				.aliases("join", "s")
				.plugin(this)
				.build();
				
			server.getCommandManager().register(serverMeta,
				new ServerCommandInterceptor.ServerCommand(server, this, serverManager, activityTracker));
			logger.info("Registered command: /server");
		} catch (Exception e) {
			logger.error("Failed to register server command", e);
		}
		
		logger.info("AutoStopper commands registered successfully!");
	}
}