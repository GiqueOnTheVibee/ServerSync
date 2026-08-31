package dev.serversync.paper.ghost;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.npc.NPC;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import dev.serversync.common.protocol.GhostPlayerState;
import dev.serversync.common.protocol.GhostTextureProperty;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class GhostPlayerManager implements AutoCloseable {
    private final String localNodeId;
    private final Logger logger;
    private final boolean debug;
    private final AtomicInteger entityIds = new AtomicInteger(1_900_000_000);
    private final Map<GhostKey, RemoteGhost> ghosts = new HashMap<>();

    public GhostPlayerManager(String localNodeId, Logger logger, boolean debug) {
        this.localNodeId = localNodeId;
        this.logger = logger;
        this.debug = debug;
    }

    public void apply(GhostPlayerState state) {
        if (state.sourceNodeId().equals(localNodeId)) return;
        GhostKey key = new GhostKey(state.sourceNodeId(), state.playerId());
        RemoteGhost existing = ghosts.get(key);
        if (existing != null && state.sequence() <= existing.sequence) return;

        if (existing == null) {
            existing = createGhost(key, state);
            ghosts.put(key, existing);
            if (debug) logger.info("Created ghost " + state.playerName() + " from " + state.sourceNodeId());
        }

        existing.sequence = state.sequence();
        existing.lastSeenMillis = System.currentTimeMillis();
        existing.world = state.world();
        existing.npc.updateLocation(packetLocation(state));
        syncViewers(existing);
    }

    public void remove(String sourceNodeId, UUID playerId, long sequence) {
        GhostKey key = new GhostKey(sourceNodeId, playerId);
        RemoteGhost ghost = ghosts.get(key);
        if (ghost == null || sequence < ghost.sequence) return;
        ghosts.remove(key);
        ghost.npc.despawnAll();
        if (debug) logger.info("Removed ghost " + ghost.name + " from " + sourceNodeId);
    }

    public void onViewerJoin(Player viewer) {
        for (RemoteGhost ghost : ghosts.values()) syncViewer(ghost, viewer);
    }

    public void onViewerQuit(Player viewer) {
        Object channel = channel(viewer);
        if (channel == null) return;
        for (RemoteGhost ghost : ghosts.values()) {
            if (ghost.npc.hasSpawned(channel)) ghost.npc.despawn(channel);
        }
    }

    public void expireOlderThan(long cutoffMillis) {
        List<GhostKey> expired = new ArrayList<>();
        for (Map.Entry<GhostKey, RemoteGhost> entry : ghosts.entrySet()) {
            if (entry.getValue().lastSeenMillis < cutoffMillis) expired.add(entry.getKey());
        }
        for (GhostKey key : expired) {
            RemoteGhost ghost = ghosts.remove(key);
            if (ghost != null) ghost.npc.despawnAll();
        }
    }

    public int size() {
        return ghosts.size();
    }

    private RemoteGhost createGhost(GhostKey key, GhostPlayerState state) {
        UUID ghostUuid = UUID.nameUUIDFromBytes(
                ("serversync-ghost:" + key.sourceNodeId + ':' + key.playerId).getBytes(StandardCharsets.UTF_8));
        String name = state.playerName().length() <= 16 ? state.playerName() : state.playerName().substring(0, 16);
        List<TextureProperty> textures = state.textureProperties().stream()
                .map(GhostPlayerManager::toPacketEventsTexture)
                .toList();
        UserProfile profile = new UserProfile(ghostUuid, name, textures);
        NPC npc = new NPC(profile, entityIds.getAndDecrement());
        npc.setLocation(packetLocation(state));
        RemoteGhost ghost = new RemoteGhost(npc, name, state.world(), state.sequence(), System.currentTimeMillis());
        syncViewers(ghost);
        return ghost;
    }

    private void syncViewers(RemoteGhost ghost) {
        for (Player viewer : Bukkit.getOnlinePlayers()) syncViewer(ghost, viewer);
    }

    private void syncViewer(RemoteGhost ghost, Player viewer) {
        Object channel = channel(viewer);
        if (channel == null) return;
        boolean sameWorld = viewer.getWorld().getName().equals(ghost.world);
        boolean spawned = ghost.npc.hasSpawned(channel);
        if (sameWorld && !spawned) {
            ghost.npc.spawn(channel);
        } else if (!sameWorld && spawned) {
            ghost.npc.despawn(channel);
        }
    }

    private Object channel(Player viewer) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
        } catch (Exception ex) {
            if (debug) logger.warning("Could not get PacketEvents channel for " + viewer.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private static com.github.retrooper.packetevents.protocol.world.Location packetLocation(GhostPlayerState state) {
        return new com.github.retrooper.packetevents.protocol.world.Location(
                state.x(), state.y(), state.z(), state.yaw(), state.pitch());
    }

    private static TextureProperty toPacketEventsTexture(GhostTextureProperty property) {
        return new TextureProperty(property.name(), property.value(), property.signature());
    }

    @Override
    public void close() {
        for (RemoteGhost ghost : ghosts.values()) ghost.npc.despawnAll();
        ghosts.clear();
    }

    private record GhostKey(String sourceNodeId, UUID playerId) {
    }

    private static final class RemoteGhost {
        private final NPC npc;
        private final String name;
        private String world;
        private long sequence;
        private long lastSeenMillis;

        private RemoteGhost(NPC npc, String name, String world, long sequence, long lastSeenMillis) {
            this.npc = npc;
            this.name = name;
            this.world = world;
            this.sequence = sequence;
            this.lastSeenMillis = lastSeenMillis;
        }
    }
}
