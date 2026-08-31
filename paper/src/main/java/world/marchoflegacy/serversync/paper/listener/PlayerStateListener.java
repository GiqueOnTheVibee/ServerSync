package world.marchoflegacy.serversync.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import world.marchoflegacy.serversync.paper.ServerSyncPaperPlugin;
import world.marchoflegacy.serversync.paper.zone.SharedZoneManager;

public final class PlayerStateListener implements Listener {
    private final ServerSyncPaperPlugin plugin;
    private final SharedZoneManager zones;

    public PlayerStateListener(ServerSyncPaperPlugin plugin, SharedZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.getLogger().fine(() -> "Join " + event.getPlayer().getUniqueId() + " zone=" + zones.zoneFor(event.getPlayer()));
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        if (event.getTo() == null || event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }
        String zone = zones.zoneFor(event.getPlayer());
        if (zones.isShared(event.getPlayer())) {
            // TODO publish compact ghost state over the real-time transport.
            plugin.getLogger().finest(() -> "Ghost state update zone=" + zone);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // TODO publish ghost despawn / ownership release.
    }
}
