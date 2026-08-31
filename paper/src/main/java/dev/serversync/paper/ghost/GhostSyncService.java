package dev.serversync.paper.ghost;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.protocol.player.TextureProperty;
import com.github.retrooper.packetevents.protocol.player.User;
import dev.serversync.common.protocol.GhostMessage;
import dev.serversync.common.protocol.GhostPlayerRemove;
import dev.serversync.common.protocol.GhostPlayerState;
import dev.serversync.common.protocol.GhostProtocol;
import dev.serversync.common.protocol.GhostTextureProperty;
import dev.serversync.paper.ServerSyncPaperPlugin;
import dev.serversync.paper.network.RelayPoolManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

public final class GhostSyncService implements AutoCloseable {
    private final ServerSyncPaperPlugin plugin;
    private final String nodeId;
    private final RelayPoolManager relayPool;
    private final GhostPlayerManager ghostManager;
    private final Map<UUID, Long> sequences = new HashMap<>();
    private final ExecutorService outbound = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("serversync-ghost-outbound-", 0).factory());
    private final int publishIntervalTicks;
    private final long timeoutMillis;
    private final boolean debug;
    private final boolean localMirrorEnabled;
    private final double localMirrorOffsetX;
    private final double localMirrorOffsetZ;

    private BukkitTask publisherTask;
    private BukkitTask cleanupTask;

    public GhostSyncService(ServerSyncPaperPlugin plugin, String nodeId, RelayPoolManager relayPool) {
        this.plugin = plugin;
        this.nodeId = nodeId;
        this.relayPool = relayPool;
        this.publishIntervalTicks = Math.max(1, plugin.getConfig().getInt("ghosts.publish-interval-ticks", 2));
        this.timeoutMillis = Math.max(2L, plugin.getConfig().getLong("ghosts.timeout-seconds", 10L)) * 1000L;
        this.debug = plugin.getConfig().getBoolean("ghosts.debug", false);
        this.localMirrorEnabled = plugin.getConfig().getBoolean("ghosts.debug-local-mirror.enabled", false);
        this.localMirrorOffsetX = plugin.getConfig().getDouble("ghosts.debug-local-mirror.offset-x", 2.0);
        this.localMirrorOffsetZ = plugin.getConfig().getDouble("ghosts.debug-local-mirror.offset-z", 0.0);
        this.ghostManager = new GhostPlayerManager(
                nodeId,
                plugin.getLogger(),
                debug,
                plugin.getConfig().getBoolean("ghosts.tab-list.enabled", true),
                plugin.getConfig().getString("ghosts.tab-list.format", "%player%"),
                plugin.getConfig().getInt("ghosts.tab-list.ping", 0));
    }

    public void start() {
        publisherTask = Bukkit.getScheduler().runTaskTimer(plugin, this::publishAll, 1L, publishIntervalTicks);
        cleanupTask = Bukkit.getScheduler().runTaskTimer(plugin,
                () -> ghostManager.expireOlderThan(System.currentTimeMillis() - timeoutMillis), 20L, 20L);
        plugin.getLogger().info("Ghost sync enabled at " + (20.0 / publishIntervalTicks) + " updates/sec.");
        if (plugin.getConfig().getBoolean("ghosts.tab-list.enabled", true)) {
            plugin.getLogger().info("Remote ghost players are enabled in the client tab list.");
        }
        if (localMirrorEnabled) {
            plugin.getLogger().warning("ghosts.debug-local-mirror is enabled. Local players will see a test mirror ghost.");
        }
    }

    public void onRelayData(String sourceNodeId, byte[] payload) {
        if (sourceNodeId == null || sourceNodeId.equals(nodeId) || !GhostProtocol.looksLikeGhostMessage(payload)) return;
        final GhostMessage message;
        try {
            message = GhostProtocol.decode(payload);
        } catch (IOException ex) {
            if (debug) plugin.getLogger().warning("Invalid ghost frame from " + sourceNodeId + ": " + ex.getMessage());
            return;
        }

        if (message instanceof GhostPlayerState state && !state.sourceNodeId().equals(sourceNodeId)) {
            plugin.getLogger().warning("Dropped ghost state with spoofed source node " + state.sourceNodeId()
                    + " received through authenticated node " + sourceNodeId + '.');
            return;
        }
        if (message instanceof GhostPlayerRemove remove && !remove.sourceNodeId().equals(sourceNodeId)) {
            plugin.getLogger().warning("Dropped ghost remove with spoofed source node " + remove.sourceNodeId()
                    + " received through authenticated node " + sourceNodeId + '.');
            return;
        }

        Bukkit.getScheduler().runTask(plugin, () -> apply(message));
    }

    public void publishNow(Player player) {
        GhostPlayerState state = capture(player, nodeId, 0.0, 0.0);
        send(state);
        applyLocalMirror(player);
    }

    public void publishRemove(Player player) {
        GhostPlayerRemove remove = new GhostPlayerRemove(player.getUniqueId(), nodeId, nextSequence(player.getUniqueId()));
        send(remove);
        if (localMirrorEnabled) {
            ghostManager.remove(mirrorNodeId(), player.getUniqueId(), remove.sequence());
        }
    }

    public void onViewerJoin(Player viewer) {
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (viewer.isOnline()) ghostManager.onViewerJoin(viewer);
        }, 1L);
    }

    public void onViewerQuit(Player viewer) {
        ghostManager.onViewerQuit(viewer);
    }

    public int ghostCount() {
        return ghostManager.size();
    }

    public List<GhostPlayerManager.GhostTarget> ghostTargets() {
        return ghostManager.targets();
    }

    public List<GhostPlayerManager.GhostTarget> findGhostTargets(String query) {
        return ghostManager.findTargets(query);
    }

    public List<String> ghostTargetSuggestions() {
        return ghostManager.targetSuggestions();
    }

    private void publishAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            GhostPlayerState state = capture(player, nodeId, 0.0, 0.0);
            send(state);
            applyLocalMirror(player);
        }
    }

    private void applyLocalMirror(Player player) {
        if (!localMirrorEnabled) return;
        GhostPlayerState mirror = capture(player, mirrorNodeId(), localMirrorOffsetX, localMirrorOffsetZ);
        ghostManager.apply(mirror);
    }

    private GhostPlayerState capture(Player player, String sourceNodeId, double offsetX, double offsetZ) {
        Location location = player.getLocation();
        return new GhostPlayerState(
                player.getUniqueId(),
                player.getName(),
                sourceNodeId,
                player.getWorld().getName(),
                location.getX() + offsetX,
                location.getY(),
                location.getZ() + offsetZ,
                location.getYaw(),
                location.getPitch(),
                player.isOnGround(),
                player.isSneaking(),
                player.isSprinting(),
                nextSequence(player.getUniqueId()),
                System.currentTimeMillis(),
                textureProperties(player)
        );
    }

    private List<GhostTextureProperty> textureProperties(Player player) {
        try {
            User user = PacketEvents.getAPI().getPlayerManager().getUser(player);
            if (user == null || user.getProfile() == null) return List.of();
            List<GhostTextureProperty> result = new ArrayList<>();
            for (TextureProperty property : user.getProfile().getTextureProperties()) {
                result.add(new GhostTextureProperty(property.getName(), property.getValue(), property.getSignature()));
            }
            return result;
        } catch (Exception ex) {
            if (debug) plugin.getLogger().warning("Could not read skin for " + player.getName() + ": " + ex.getMessage());
            return List.of();
        }
    }

    private long nextSequence(UUID playerId) {
        long next = sequences.getOrDefault(playerId, 0L) + 1L;
        sequences.put(playerId, next);
        return next;
    }

    private void send(GhostMessage message) {
        if (relayPool == null || outbound.isShutdown()) return;
        final byte[] payload = encode(message);
        if (payload == null) return;
        outbound.execute(() -> relayPool.broadcast(payload));
    }

    private void sendImmediate(GhostMessage message) {
        if (relayPool == null) return;
        byte[] payload = encode(message);
        if (payload != null) relayPool.broadcast(payload);
    }

    private byte[] encode(GhostMessage message) {
        try {
            return GhostProtocol.encode(message);
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not encode ghost message", ex);
            return null;
        }
    }

    private void apply(GhostMessage message) {
        if (message instanceof GhostPlayerState state) {
            ghostManager.apply(state);
        } else if (message instanceof GhostPlayerRemove remove) {
            ghostManager.remove(remove.sourceNodeId(), remove.playerId(), remove.sequence());
        }
    }

    private String mirrorNodeId() {
        return nodeId + "-local-mirror";
    }

    @Override
    public void close() {
        if (publisherTask != null) publisherTask.cancel();
        if (cleanupTask != null) cleanupTask.cancel();

        outbound.shutdown();
        try {
            if (!outbound.awaitTermination(1, TimeUnit.SECONDS)) outbound.shutdownNow();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            outbound.shutdownNow();
        }

        // Send removals after queued state frames have drained so stale state cannot resurrect a ghost.
        for (Player player : Bukkit.getOnlinePlayers()) {
            GhostPlayerRemove remove = new GhostPlayerRemove(
                    player.getUniqueId(), nodeId, nextSequence(player.getUniqueId()));
            sendImmediate(remove);
            if (localMirrorEnabled) {
                ghostManager.remove(mirrorNodeId(), player.getUniqueId(), remove.sequence());
            }
        }

        ghostManager.close();
        sequences.clear();
    }
}
