package dev.serversync.paper.world;

import dev.serversync.common.protocol.WorldBlockCommit;
import dev.serversync.common.protocol.WorldBlockIntent;
import dev.serversync.common.protocol.WorldMessage;
import dev.serversync.common.protocol.WorldProtocol;
import dev.serversync.paper.ServerSyncPaperPlugin;
import dev.serversync.paper.network.RelayPoolManager;
import dev.serversync.paper.zone.SharedZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

public final class WorldSyncService implements AutoCloseable {
    private final ServerSyncPaperPlugin plugin;
    private final String nodeId;
    private final RelayPoolManager relayPool;
    private final SharedZoneManager zones;
    private final boolean enabled;
    private final boolean syncBreak;
    private final boolean syncPlace;
    private final boolean applyPhysics;
    private final boolean failClosed;
    private final boolean debug;
    private final String preferredRelay;
    private final Map<String, Long> lastSequenceByZone = new ConcurrentHashMap<>();
    private final AtomicLong intentsSent = new AtomicLong();
    private final AtomicLong commitsReceived = new AtomicLong();
    private final AtomicLong remoteBlocksApplied = new AtomicLong();
    private final AtomicLong sequenceGaps = new AtomicLong();

    public WorldSyncService(ServerSyncPaperPlugin plugin, String nodeId, RelayPoolManager relayPool,
                            SharedZoneManager zones) {
        this.plugin = plugin;
        this.nodeId = nodeId;
        this.relayPool = relayPool;
        this.zones = zones;
        this.enabled = plugin.getConfig().getBoolean("world-sync.enabled", true);
        this.syncBreak = plugin.getConfig().getBoolean("world-sync.blocks.break", true);
        this.syncPlace = plugin.getConfig().getBoolean("world-sync.blocks.place", true);
        this.applyPhysics = plugin.getConfig().getBoolean("world-sync.blocks.apply-physics", false);
        this.failClosed = plugin.getConfig().getBoolean("world-sync.fail-closed-when-relay-unavailable", true);
        this.debug = plugin.getConfig().getBoolean("world-sync.debug", false);
        this.preferredRelay = plugin.getConfig().getString("world-sync.authoritative-relay", "relay-1").trim();
    }

    public void start() {
        if (!enabled) {
            plugin.getLogger().info("World sync is disabled.");
            return;
        }
        plugin.getLogger().info("World sync MVP enabled: block break/place commits are sequenced by relay '"
                + preferredRelay + "'. Start every node from the same world snapshot.");
        plugin.getLogger().warning("World sync is currently an optimistic block-state MVP. Containers, drops, entities, "
                + "redstone/fluids and durable catch-up are not authoritative yet.");
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean syncBreak() {
        return enabled && syncBreak;
    }

    public boolean syncPlace() {
        return enabled && syncPlace;
    }

    public boolean shouldFailClosed() {
        return enabled && failClosed;
    }

    public boolean hasCommitPath() {
        return relayPool != null && relayPool.hasConnectedRelay();
    }

    public boolean publishBreak(Player player, Block block) {
        if (!syncBreak()) return false;
        return publish(player, block, block.getBlockData().getAsString(), Bukkit.createBlockData("minecraft:air").getAsString());
    }

    public boolean publishPlace(Player player, Block block, BlockData oldBlockData, BlockData newBlockData) {
        if (!syncPlace()) return false;
        return publish(player, block, oldBlockData.getAsString(), newBlockData.getAsString());
    }

    private boolean publish(Player player, Block block, String oldBlockData, String newBlockData) {
        if (!enabled || relayPool == null) return false;
        String zoneId = zones.zoneFor(block.getLocation());
        WorldBlockIntent intent = new WorldBlockIntent(
                UUID.randomUUID(),
                player.getUniqueId(),
                nodeId,
                block.getWorld().getName(),
                zoneId,
                block.getX(),
                block.getY(),
                block.getZ(),
                oldBlockData,
                newBlockData,
                System.currentTimeMillis()
        );
        try {
            byte[] payload = WorldProtocol.encode(intent);
            String relayId = relayPool.sendAuthoritative(payload, preferredRelay);
            if (relayId == null) return false;
            intentsSent.incrementAndGet();
            if (debug) {
                plugin.getLogger().info("World intent " + intent.requestId() + " zone=" + zoneId
                        + " block=" + block.getX() + ',' + block.getY() + ',' + block.getZ()
                        + " via=" + relayId);
            }
            return true;
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not encode world block intent", ex);
            return false;
        }
    }

    public void onRelayData(String authenticatedSourceNodeId, byte[] payload) {
        if (!enabled || !WorldProtocol.looksLikeWorldMessage(payload)) return;
        final WorldMessage message;
        try {
            message = WorldProtocol.decode(payload);
        } catch (IOException ex) {
            plugin.getLogger().warning("Invalid world frame: " + ex.getMessage());
            return;
        }
        if (!(message instanceof WorldBlockCommit commit)) {
            if (debug) plugin.getLogger().warning("Ignoring non-commit world frame received from relay.");
            return;
        }
        if (!commit.sourceNodeId().equals(authenticatedSourceNodeId)) {
            plugin.getLogger().warning("Dropped world commit whose source node does not match relay DATA source: "
                    + commit.sourceNodeId() + " != " + authenticatedSourceNodeId);
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () -> applyCommit(commit));
    }

    private void applyCommit(WorldBlockCommit commit) {
        Long previous = lastSequenceByZone.get(commit.zoneId());
        if (previous != null) {
            if (commit.sequence() <= previous) return;
            if (commit.sequence() > previous + 1) {
                sequenceGaps.incrementAndGet();
                plugin.getLogger().warning("World sequence gap in " + commit.zoneId() + ": have " + previous
                        + ", received " + commit.sequence() + ". Durable catch-up is not implemented yet; applying newest commit.");
            }
        }

        commitsReceived.incrementAndGet();
        lastSequenceByZone.put(commit.zoneId(), commit.sequence());

        // The source Paper already performed the Bukkit event locally. Receiving its commit is the relay ACK/order stamp.
        if (commit.sourceNodeId().equals(nodeId)) {
            if (debug) plugin.getLogger().info("World commit ACK zone=" + commit.zoneId() + " seq=" + commit.sequence());
            return;
        }

        String localWorldName = mapWorldName(commit.world());
        World world = Bukkit.getWorld(localWorldName);
        if (world == null) {
            plugin.getLogger().warning("Cannot apply world commit " + commit.zoneId() + '#' + commit.sequence()
                    + ": local world '" + localWorldName + "' is not loaded.");
            return;
        }

        try {
            if (!world.isChunkLoaded(commit.x() >> 4, commit.z() >> 4)) {
                world.getChunkAt(commit.x() >> 4, commit.z() >> 4).load();
            }
            Block block = world.getBlockAt(commit.x(), commit.y(), commit.z());
            BlockData newData = Bukkit.createBlockData(commit.newBlockData());
            block.setBlockData(newData, applyPhysics);
            remoteBlocksApplied.incrementAndGet();
            if (debug) {
                plugin.getLogger().info("Applied world commit from " + commit.sourceNodeId() + " zone="
                        + commit.zoneId() + " seq=" + commit.sequence() + " block=" + commit.x() + ','
                        + commit.y() + ',' + commit.z() + " -> " + commit.newBlockData());
            }
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to apply world commit " + commit.zoneId()
                    + '#' + commit.sequence(), ex);
        }
    }

    private String mapWorldName(String remoteWorld) {
        String configured = plugin.getConfig().getString("world-sync.world-map." + remoteWorld);
        return configured == null || configured.isBlank() ? remoteWorld : configured.trim();
    }

    public WorldSyncStatus status() {
        return new WorldSyncStatus(enabled, hasCommitPath(), preferredRelay, intentsSent.get(), commitsReceived.get(),
                remoteBlocksApplied.get(), sequenceGaps.get(), Map.copyOf(lastSequenceByZone));
    }

    @Override
    public void close() {
        lastSequenceByZone.clear();
    }

    public record WorldSyncStatus(
            boolean enabled,
            boolean relayConnected,
            String authoritativeRelay,
            long intentsSent,
            long commitsReceived,
            long remoteBlocksApplied,
            long sequenceGaps,
            Map<String, Long> lastSequenceByZone
    ) {
    }
}
