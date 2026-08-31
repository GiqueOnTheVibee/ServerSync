package dev.serversync.paper.zone;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SharedZoneManager {
    private final String nodeId;
    private final int radius;
    private final Map<UUID, String> playerZones = new ConcurrentHashMap<>();

    public SharedZoneManager(String nodeId, int radius) {
        this.nodeId = nodeId;
        this.radius = radius;
    }

    public String nodeId() {
        return nodeId;
    }

    public String zoneFor(Player player) {
        return playerZones.compute(player.getUniqueId(), (uuid, ignored) -> zoneFor(player.getLocation()));
    }

    public String zoneFor(Location location) {
        int size = Math.max(16, radius);
        int zx = Math.floorDiv(location.getBlockX(), size);
        int zz = Math.floorDiv(location.getBlockZ(), size);
        return location.getWorld().getName() + ":" + zx + ":" + zz;
    }

    public boolean isShared(Player player) {
        // Coordinator will replace this prototype decision.
        return false;
    }
}
