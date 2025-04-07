package me.criseda.autostopper.listeners;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerConnectedEvent;

import me.criseda.autostopper.server.ActivityTracker;

public class ConnectionListener {
    private final ActivityTracker activityTracker;

    public ConnectionListener(ActivityTracker activityTracker) {
        this.activityTracker = activityTracker;
    }

    @Subscribe
    public void onServerConnected(ServerConnectedEvent event) {
        String serverName = event.getServer().getServerInfo().getName();
        activityTracker.updateActivity(serverName);
    }
}