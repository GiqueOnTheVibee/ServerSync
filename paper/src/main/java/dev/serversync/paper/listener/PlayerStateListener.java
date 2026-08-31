package dev.serversync.paper.listener;

import dev.serversync.paper.ServerSyncPaperPlugin;
import dev.serversync.paper.ghost.GhostSyncService;
import dev.serversync.paper.zone.SharedZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public final class PlayerStateListener implements Listener {
    private final ServerSyncPaperPlugin plugin;
    private final SharedZoneManager zones;

    public PlayerStateListener(ServerSyncPaperPlugin plugin, SharedZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        zones.zoneFor(event.getPlayer());
        GhostSyncService ghosts = plugin.ghostSyncService();
        if (ghosts == null) return;
        ghosts.onViewerJoin(event.getPlayer());
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (event.getPlayer().isOnline()) ghosts.publishNow(event.getPlayer());
        }, 1L);
    }

    @EventHandler
    public void onChangedWorld(PlayerChangedWorldEvent event) {
        zones.zoneFor(event.getPlayer());
        GhostSyncService ghosts = plugin.ghostSyncService();
        if (ghosts != null) {
            ghosts.onViewerJoin(event.getPlayer());
            ghosts.publishNow(event.getPlayer());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        GhostSyncService ghosts = plugin.ghostSyncService();
        if (ghosts != null) {
            ghosts.publishRemove(event.getPlayer());
            ghosts.onViewerQuit(event.getPlayer());
        }
    }
}
