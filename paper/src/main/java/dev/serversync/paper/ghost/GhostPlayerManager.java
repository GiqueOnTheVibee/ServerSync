package dev.serversync.paper.ghost;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.npc.NPC;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.UserProfile;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoRemove;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerPlayerInfoUpdate;
import dev.serversync.common.protocol.GhostPlayerState;
import dev.serversync.common.protocol.GhostTextureProperty;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public final class GhostPlayerManager implements AutoCloseable {
    private final String localNodeId;
    private final Logger logger;
    private final boolean debug;
    private final boolean tabListEnabled;
    private final String tabListFormat;
    private final int tabListPing;
    private final AtomicInteger entityIds = new AtomicInteger(1_900_000_000);
    private final Map<GhostKey, RemoteGhost> ghosts = new HashMap<>();

    public GhostPlayerManager(String localNodeId, Logger logger, boolean debug,
                              boolean tabListEnabled, String tabListFormat, int tabListPing) {
        this.localNodeId = localNodeId;
        this.logger = logger;
        this.debug = debug;
        this.tabListEnabled = tabListEnabled;
        this.tabListFormat = tabListFormat == null || tabListFormat.isBlank() ? "%player%" : tabListFormat;
        this.tabListPing = Math.max(0, Math.min(9999, tabListPing));
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
        existing.x = state.x();
        existing.y = state.y();
        existing.z = state.z();
        existing.yaw = state.yaw();
        existing.pitch = state.pitch();
        existing.npc.updateLocation(packetLocation(state));
        syncViewers(existing);
    }

    public void remove(String sourceNodeId, UUID playerId, long sequence) {
        GhostKey key = new GhostKey(sourceNodeId, playerId);
        RemoteGhost ghost = ghosts.get(key);
        if (ghost == null || sequence < ghost.sequence) return;
        ghosts.remove(key);
        destroyGhost(ghost);
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
            ghost.tabChannels.remove(channel);
        }
    }

    public void expireOlderThan(long cutoffMillis) {
        List<GhostKey> expired = new ArrayList<>();
        for (Map.Entry<GhostKey, RemoteGhost> entry : ghosts.entrySet()) {
            if (entry.getValue().lastSeenMillis < cutoffMillis) expired.add(entry.getKey());
        }
        for (GhostKey key : expired) {
            RemoteGhost ghost = ghosts.remove(key);
            if (ghost != null) destroyGhost(ghost);
        }
    }

    public int size() {
        return ghosts.size();
    }

    public List<GhostTarget> targets() {
        return ghosts.entrySet().stream()
                .map(entry -> target(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(GhostTarget::playerName, String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(GhostTarget::sourceNodeId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<GhostTarget> findTargets(String query) {
        if (query == null || query.isBlank()) return List.of();
        String trimmed = query.trim();
        int at = trimmed.lastIndexOf('@');
        String requestedName = at > 0 ? trimmed.substring(0, at) : trimmed;
        String requestedNode = at > 0 && at + 1 < trimmed.length() ? trimmed.substring(at + 1) : null;

        String nameLower = requestedName.toLowerCase(Locale.ROOT);
        String nodeLower = requestedNode == null ? null : requestedNode.toLowerCase(Locale.ROOT);
        return ghosts.entrySet().stream()
                .filter(entry -> entry.getValue().name.toLowerCase(Locale.ROOT).equals(nameLower))
                .filter(entry -> nodeLower == null || entry.getKey().sourceNodeId.toLowerCase(Locale.ROOT).equals(nodeLower))
                .map(entry -> target(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(GhostTarget::sourceNodeId, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public List<String> targetSuggestions() {
        List<GhostTarget> targets = targets();
        Map<String, Integer> nameCounts = new HashMap<>();
        for (GhostTarget target : targets) {
            nameCounts.merge(target.playerName().toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        List<String> suggestions = new ArrayList<>();
        for (GhostTarget target : targets) {
            if (nameCounts.getOrDefault(target.playerName().toLowerCase(Locale.ROOT), 0) == 1) {
                suggestions.add(target.playerName());
            } else {
                suggestions.add(target.key());
            }
        }
        return suggestions;
    }

    private RemoteGhost createGhost(GhostKey key, GhostPlayerState state) {
        UUID ghostUuid = UUID.nameUUIDFromBytes(
                ("serversync-ghost:" + key.sourceNodeId + ':' + key.playerId).getBytes(StandardCharsets.UTF_8));
        String name = state.playerName().length() <= 16 ? state.playerName() : state.playerName().substring(0, 16);
        List<TextureProperty> textures = state.textureProperties().stream()
                .map(GhostPlayerManager::toPacketEventsTexture)
                .toList();
        UserProfile profile = new UserProfile(ghostUuid, name, textures);
        String tabText = tabListFormat
                .replace("%player%", name)
                .replace("%node%", key.sourceNodeId);
        NPC npc = new NPC(profile, entityIds.getAndDecrement(), Component.text(tabText));
        npc.setDisplayPing(tabListPing);
        npc.setLocation(packetLocation(state));
        RemoteGhost ghost = new RemoteGhost(npc, name, state.world(), state.sequence(), System.currentTimeMillis(),
                state.x(), state.y(), state.z(), state.yaw(), state.pitch());
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
            if (tabListEnabled) {
                ghost.tabChannels.add(channel);
            } else {
                removeTabEntry(ghost, channel);
            }
        } else if (!sameWorld && spawned) {
            ghost.npc.despawn(channel);
        }

        if (tabListEnabled && !ghost.tabChannels.contains(channel)) {
            addTabEntry(ghost, channel);
        } else if (!tabListEnabled && ghost.tabChannels.remove(channel)) {
            removeTabEntry(ghost, channel);
        }
    }

    private void addTabEntry(RemoteGhost ghost, Object channel) {
        try {
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel,
                    new WrapperPlayServerPlayerInfoUpdate(
                            WrapperPlayServerPlayerInfoUpdate.Action.ADD_PLAYER,
                            ghost.npc.getModernPlayerInfoData()));
            ghost.tabChannels.add(channel);
        } catch (Exception ex) {
            if (debug) logger.warning("Could not add ghost " + ghost.name + " to tab list: " + ex.getMessage());
        }
    }

    private void removeTabEntry(RemoteGhost ghost, Object channel) {
        try {
            PacketEvents.getAPI().getProtocolManager().sendPacket(channel,
                    new WrapperPlayServerPlayerInfoRemove(ghost.npc.getProfile().getUUID()));
            ghost.tabChannels.remove(channel);
        } catch (Exception ex) {
            if (debug) logger.warning("Could not remove ghost " + ghost.name + " from tab list: " + ex.getMessage());
        }
    }

    private void destroyGhost(RemoteGhost ghost) {
        ghost.npc.despawnAll();
        for (Object channel : new ArrayList<>(ghost.tabChannels)) {
            removeTabEntry(ghost, channel);
        }
        ghost.tabChannels.clear();
    }

    private Object channel(Player viewer) {
        try {
            return PacketEvents.getAPI().getPlayerManager().getChannel(viewer);
        } catch (Exception ex) {
            if (debug) logger.warning("Could not get PacketEvents channel for " + viewer.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private GhostTarget target(GhostKey key, RemoteGhost ghost) {
        return new GhostTarget(ghost.name, key.sourceNodeId, key.playerId, ghost.world,
                ghost.x, ghost.y, ghost.z, ghost.yaw, ghost.pitch, ghost.lastSeenMillis);
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
        for (RemoteGhost ghost : ghosts.values()) destroyGhost(ghost);
        ghosts.clear();
    }

    public record GhostTarget(String playerName, String sourceNodeId, UUID playerId, String world,
                              double x, double y, double z, float yaw, float pitch, long lastSeenMillis) {
        public String key() {
            return playerName + '@' + sourceNodeId;
        }
    }

    private record GhostKey(String sourceNodeId, UUID playerId) {
    }

    private static final class RemoteGhost {
        private final NPC npc;
        private final String name;
        private final Set<Object> tabChannels = new HashSet<>();
        private String world;
        private long sequence;
        private long lastSeenMillis;
        private double x;
        private double y;
        private double z;
        private float yaw;
        private float pitch;

        private RemoteGhost(NPC npc, String name, String world, long sequence, long lastSeenMillis,
                            double x, double y, double z, float yaw, float pitch) {
            this.npc = npc;
            this.name = name;
            this.world = world;
            this.sequence = sequence;
            this.lastSeenMillis = lastSeenMillis;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }
}
