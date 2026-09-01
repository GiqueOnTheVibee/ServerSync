package dev.serversync.paper.listener;

import dev.serversync.paper.world.WorldSyncService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BlockIntentListener implements Listener {
    private final WorldSyncService worldSync;
    private final Map<UUID, Long> lastUnavailableWarning = new ConcurrentHashMap<>();

    public BlockIntentListener(WorldSyncService worldSync) {
        this.worldSync = worldSync;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardBreak(BlockBreakEvent event) {
        if (worldSync.syncBreak() && worldSync.shouldFailClosed() && !worldSync.hasCommitPath()) {
            event.setCancelled(true);
            warnUnavailable(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void publishBreak(BlockBreakEvent event) {
        if (worldSync.syncBreak()) worldSync.publishBreak(event.getPlayer(), event.getBlock());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardPlace(BlockPlaceEvent event) {
        if (worldSync.syncPlace() && worldSync.shouldFailClosed() && !worldSync.hasCommitPath()) {
            event.setCancelled(true);
            warnUnavailable(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void publishPlace(BlockPlaceEvent event) {
        if (!worldSync.syncPlace()) return;
        worldSync.publishPlace(
                event.getPlayer(),
                event.getBlockPlaced(),
                event.getBlockReplacedState().getBlockData(),
                event.getBlockPlaced().getBlockData()
        );
    }

    private void warnUnavailable(Player player) {
        long now = System.currentTimeMillis();
        long previous = lastUnavailableWarning.getOrDefault(player.getUniqueId(), 0L);
        if (now - previous < 3000L) return;
        lastUnavailableWarning.put(player.getUniqueId(), now);
        player.sendMessage("ServerSync: world changes are temporarily locked because the authoritative relay is unavailable.");
    }
}
