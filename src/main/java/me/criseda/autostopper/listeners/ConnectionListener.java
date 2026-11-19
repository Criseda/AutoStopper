package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

import me.criseda.autostopper.lifecycle.ServerLifecycleCoordinator;
import me.criseda.autostopper.server.ActivityTracker;

public class ConnectionListener {
    private final ActivityTracker activityTracker;
    private final ServerLifecycleCoordinator lifecycleCoordinator;

    public ConnectionListener(ActivityTracker activityTracker, ServerLifecycleCoordinator lifecycleCoordinator) {
        this.activityTracker = activityTracker;
        this.lifecycleCoordinator = lifecycleCoordinator;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String serverName = event.getServer().getServerInfo().getName();
        lifecycleCoordinator.markReady(serverName);
        activityTracker.updateActivity(serverName);
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(connection ->
                activityTracker.updateActivity(connection.getServerInfo().getName()));
        lifecycleCoordinator.discardPlayer(event.getPlayer());
    }
}
