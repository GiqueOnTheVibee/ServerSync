package dev.serversync.paper.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import dev.serversync.paper.ServerSyncPaperPlugin;
import dev.serversync.paper.zone.SharedZoneManager;

public final class BlockIntentListener implements Listener {
    private final ServerSyncPaperPlugin plugin;
    private final SharedZoneManager zones;

    public BlockIntentListener(ServerSyncPaperPlugin plugin, SharedZoneManager zones) {
        this.plugin = plugin;
        this.zones = zones;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        String zone = zones.zoneFor(event.getPlayer());
        if (!zones.isShared(event.getPlayer())) {
            return;
        }

        // In shared mode the final implementation will publish a BLOCK_INTENT
        // before committing state that can produce a cross-region conflict.
        plugin.getLogger().finest(() -> "Shared block break intent zone=" + zone
                + " block=" + event.getBlock().getX() + "," + event.getBlock().getY() + "," + event.getBlock().getZ());
    }
}
