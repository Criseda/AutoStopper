package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import me.criseda.autostopper.config.ServerMapping;
import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.server.ActivityTrackerService;
import me.criseda.autostopper.server.ServerManager;

import java.util.Optional;

public class ServerPreConnectListener {
    private final ServerManager serverManager;
    private final ServerLifecycleCoordinator lifecycleCoordinator;
    private final ActivityTrackerService activityTracker;

    public ServerPreConnectListener(ServerManager serverManager,
            ServerLifecycleCoordinator lifecycleCoordinator, ActivityTrackerService activityTracker) {
        this.serverManager = serverManager;
        this.lifecycleCoordinator = lifecycleCoordinator;
        this.activityTracker = activityTracker;
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        Optional<RegisteredServer> target = event.getResult().getServer();
        // The connection attempt was denied (by this or another plugin) - never start a container.
        if (target.isEmpty()) {
            return;
        }
        RegisteredServer targetServer = target.get();
        String serverName = targetServer.getServerInfo().getName();

        if (lifecycleCoordinator.consumeReconnectPermit(player, serverName)) {
            return;
        }

        Optional<ServerMapping> mapping = serverManager.getServerMapping(serverName);
        if (mapping.isEmpty()) {
            return;
        }

        event.setResult(ServerPreConnectEvent.ServerResult.denied());
        lifecycleCoordinator.requestConnection(player, targetServer, mapping.get())
                .thenAccept(outcome -> {
                    if (outcome.isSuccessful()) {
                        activityTracker.updateActivity(serverName);
                    }
                });
    }
}
