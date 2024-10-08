/*
 * Copyright (C) filoghost and contributors
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package me.filoghost.holographicdisplays.plugin.bridge.bungeecord;

import com.github.Anon8281.universalScheduler.scheduling.tasks.MyScheduledTask;
import me.filoghost.fcommons.logging.Log;
import me.filoghost.fcommons.ping.MinecraftServerPinger;
import me.filoghost.fcommons.ping.PingParseException;
import me.filoghost.fcommons.ping.PingResponse;
import me.filoghost.holographicdisplays.common.DebugLogger;
import me.filoghost.holographicdisplays.plugin.HolographicDisplays;
import me.filoghost.holographicdisplays.plugin.config.ServerAddress;
import me.filoghost.holographicdisplays.plugin.config.Settings;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

public class BungeeServerTracker {

    private static final long UNTRACK_AFTER_TIME_WITHOUT_REQUESTS = TimeUnit.MINUTES.toMillis(10);

    private final Plugin plugin;
    private final ConcurrentMap<String, TrackedServer> trackedServers;
    private final BungeeMessenger bungeeMessenger;

    private MyScheduledTask taskID = null;

    public BungeeServerTracker(Plugin plugin) {
        this.plugin = plugin;
        this.trackedServers = new ConcurrentHashMap<>();
        this.bungeeMessenger = BungeeMessenger.registerNew(plugin, this::updateServerInfoFromBungee);
    }

    public void restart(int updateInterval, TimeUnit timeUnit) {
        trackedServers.clear();

        if (taskID != null) {
            taskID.cancel();
        }

        taskID = HolographicDisplays.getScheduler().scheduleSyncRepeatingTask(
                this::runPeriodicUpdateTask,
                1,
                timeUnit.toSeconds(updateInterval) * 20L);
    }

    public ServerInfo getCurrentServerInfo(@NotNull String serverName) {
        // If it wasn't already tracked, send an update request instantly
        if (!Settings.pingerEnabled && !trackedServers.containsKey(serverName)) {
            bungeeMessenger.sendPlayerCountRequest(serverName);
        }

        TrackedServer trackedServer = trackedServers.computeIfAbsent(serverName, TrackedServer::new);
        trackedServer.updateLastRequest();
        return trackedServer.serverInfo;
    }

    private void runPeriodicUpdateTask() {
        removeUnusedServers();

        if (Settings.pingerEnabled) {
            HolographicDisplays.getScheduler().runTaskAsynchronously(() -> {
                for (TrackedServer trackedServer : trackedServers.values()) {
                    updateServerInfoWithPinger(trackedServer);
                }
            });
        } else {
            for (String serverName : trackedServers.keySet()) {
                bungeeMessenger.sendPlayerCountRequest(serverName);
            }
        }
    }

    private void updateServerInfoWithPinger(TrackedServer trackedServer) {
        ServerAddress serverAddress = Settings.pingerServerAddresses.get(trackedServer.serverName);

        if (serverAddress != null) {
            trackedServer.serverInfo = pingServer(serverAddress);
        } else {
            trackedServer.serverInfo = ServerInfo.offline("[Unknown server: " + trackedServer.serverName + "]");
        }
    }

    private void updateServerInfoFromBungee(String serverName, int onlinePlayers) {
        TrackedServer trackedServer = trackedServers.get(serverName);
        if (trackedServer != null) {
            trackedServer.serverInfo = ServerInfo.online(onlinePlayers, 0, "");
        }
    }

    private ServerInfo pingServer(ServerAddress serverAddress) {
        try {
            PingResponse data = MinecraftServerPinger.ping(serverAddress.getAddress(), serverAddress.getPort(), Settings.pingerTimeout);
            return ServerInfo.online(data.getOnlinePlayers(), data.getMaxPlayers(), data.getMotd());

        } catch (PingParseException e) {
            DebugLogger.warning("Received invalid JSON response from IP \"" + serverAddress + "\": " + e.getJsonString());
            return ServerInfo.online(0, 0, "Invalid ping response (" + e.getMessage() + ")");

        } catch (IOException e) {
            if (e instanceof SocketTimeoutException || e instanceof ConnectException) {
                // Common error, only log when debugging
                DebugLogger.warning("Couldn't fetch data from " + serverAddress + ".", e);
            } else if (e instanceof UnknownHostException) {
                Log.warning("Couldn't fetch data from " + serverAddress + ": unknown host address.");
            } else {
                Log.warning("Couldn't fetch data from " + serverAddress + ".", e);
            }
            return ServerInfo.offline(Settings.pingerOfflineMotd);
        }
    }

    private void removeUnusedServers() {
        long now = System.currentTimeMillis();

        trackedServers.values().removeIf(trackedServer -> {
            if (now - trackedServer.lastRequest > UNTRACK_AFTER_TIME_WITHOUT_REQUESTS) {
                DebugLogger.info("Untracked unused server \"" + trackedServer.serverName + "\".");
                return true;
            } else {
                return false;
            }
        });
    }

    private static class TrackedServer {

        private final String serverName;
        private volatile ServerInfo serverInfo;
        private volatile long lastRequest;

        private TrackedServer(String serverName) {
            this.serverName = serverName;
            this.serverInfo = ServerInfo.offline(Settings.pingerOfflineMotd);
        }

        private void updateLastRequest() {
            this.lastRequest = System.currentTimeMillis();
        }

    }

}
