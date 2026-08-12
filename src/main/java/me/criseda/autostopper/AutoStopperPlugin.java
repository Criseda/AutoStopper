package me.criseda.autostopper;

import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;

import me.criseda.autostopper.commands.AutoStopperCommand;
import me.criseda.autostopper.config.AutoStopperConfig;
import me.criseda.autostopper.config.ConfigLoadResult;
import me.criseda.autostopper.config.ConfigSnapshot;
import me.criseda.autostopper.docker.DockerManager;
import me.criseda.autostopper.docker.ProcessCommandRunner;
import me.criseda.autostopper.executor.AutoStopperExecutor;
import me.criseda.autostopper.listeners.ConnectionListener;
import me.criseda.autostopper.listeners.ServerPreConnectListener;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.operational.OperationalStatusService;
import me.criseda.autostopper.server.ActivityTracker;
import me.criseda.autostopper.server.ServerManager;

import org.slf4j.Logger;

import com.google.inject.Inject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Plugin(id = "autostopper", name = "AutoStopper", version = "1.1.2", authors = { "criseda" })
public class AutoStopperPlugin {
    private final ProxyServer server;
    private final Logger logger;
    private final Path dataDirectory;
    private final PluginContainer pluginContainer;

    private AutoStopperConfig config;
    private ServerManager serverManager;
    private ActivityTracker activityTracker;
    private ServerLifecycleCoordinator lifecycleCoordinator;
    private AutoStopperExecutor executor;
    private OperationalStatusService operationalStatus;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    @Inject
    public AutoStopperPlugin(ProxyServer server, Logger logger, @DataDirectory Path dataDirectory, PluginContainer pluginContainer) {
        this.server = server;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
        this.pluginContainer = pluginContainer;
    }

	@Subscribe
	public void onProxyInitialize(ProxyInitializeEvent event) {
		logger.info("AutoStopper plugin initializing...");
	
		// Load configuration first
		this.config = createConfig();
		ConfigLoadResult initialConfig = config.loadConfig();
		if (!initialConfig.successful()) {
			logger.error("AutoStopper initialization aborted: {}", initialConfig.errorSummary());
			return;
		}
	
		// Initialize server management
		this.executor = createExecutor();
		this.serverManager = createServerManager(config, executor);
		this.lifecycleCoordinator = createLifecycleCoordinator(serverManager);
		this.operationalStatus = createOperationalStatusService(serverManager, lifecycleCoordinator);
		
		// Initialize activity tracking but DON'T start the inactivity check yet
		this.activityTracker = createActivityTracker(config, serverManager, executor, lifecycleCoordinator);
	
		// Register event listeners
		server.getEventManager().register(this, new ConnectionListener(activityTracker, lifecycleCoordinator));
		server.getEventManager().register(this,
				new ServerPreConnectListener(serverManager, lifecycleCoordinator, activityTracker));
	
		// Register commands with the new non-deprecated method
		registerCommands();
		
		// NOW start the inactivity check AFTER all registration is complete
		activityTracker.startInactivityCheck();
		operationalStatus.runPreflight(initialConfig.snapshot(), "startup");
	
		logger.info("AutoStopper plugin initialized!");
	}

	@Subscribe
	public void onProxyShutdown(ProxyShutdownEvent event) {
		if (!shutdown.compareAndSet(false, true)) {
			return;
		}
		if (activityTracker != null) {
			activityTracker.shutdown();
		}
		if (lifecycleCoordinator != null) {
			lifecycleCoordinator.shutdown();
		}
		if (operationalStatus != null) {
			operationalStatus.shutdown();
		}
		if (executor != null) {
            int timeoutSeconds = config == null
                    ? ConfigSnapshot.DEFAULT_SHUTDOWN_TIMEOUT_SECONDS
                    : config.snapshot().shutdownTimeoutSeconds();
			boolean terminated = executor.shutdown(Duration.ofSeconds(timeoutSeconds));
            if (terminated) {
                logger.info("AutoStopper shutdown completed within {} seconds.", timeoutSeconds);
            } else {
                logger.warn("AutoStopper shutdown deadline of {} seconds expired; remaining worker threads are daemon threads.",
                        timeoutSeconds);
            }
		}
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
        return new AutoStopperConfig(dataDirectory, logger, name -> server.getServer(name).isPresent());
    }

    protected AutoStopperExecutor createExecutor() {
        return new AutoStopperExecutor();
    }

    protected ServerManager createServerManager(AutoStopperConfig config, AutoStopperExecutor executor) {
        DockerManager dockerManager = new DockerManager(logger, new ProcessCommandRunner());
        return new ServerManager(server, logger, config, dockerManager, executor);
    }

    protected ActivityTracker createActivityTracker(AutoStopperConfig config, ServerManager serverManager,
            AutoStopperExecutor executor, ServerLifecycleCoordinator lifecycleCoordinator) {
        return new ActivityTracker(server, logger, config, serverManager, executor, this, lifecycleCoordinator);
    }

    protected ServerLifecycleCoordinator createLifecycleCoordinator(ServerManager serverManager) {
        return new ServerLifecycleCoordinator(logger, serverManager);
    }

    protected OperationalStatusService createOperationalStatusService(ServerManager serverManager,
            ServerLifecycleCoordinator lifecycleCoordinator) {
        return new OperationalStatusService(logger, serverManager, lifecycleCoordinator);
    }

	private void registerCommands() {
		logger.info("Registering AutoStopper commands...");
		
		// Use a more direct registration method
		CommandMeta autoStopperMeta = server.getCommandManager().metaBuilder("autostopper")
			.aliases("as")
			.plugin(this)
			.build();
			
		server.getCommandManager().register(autoStopperMeta,
			new AutoStopperCommand(config, activityTracker,
					lifecycleCoordinator, operationalStatus, pluginContainer));
		logger.info("Registered command: /autostopper");
		
		logger.info("AutoStopper commands registered successfully!");
	}
}
