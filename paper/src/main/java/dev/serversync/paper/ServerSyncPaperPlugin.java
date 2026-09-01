package dev.serversync.paper;

import dev.serversync.common.network.NetworkMode;
import dev.serversync.paper.command.GhostAdminCommand;
import dev.serversync.paper.command.WorldAdminCommand;
import dev.serversync.paper.ghost.GhostSyncService;
import dev.serversync.paper.listener.BlockIntentListener;
import dev.serversync.paper.listener.PlayerStateListener;
import dev.serversync.paper.network.RelayPoolManager;
import dev.serversync.paper.voice.VoiceBridgeBootstrap;
import dev.serversync.paper.world.WorldSyncService;
import dev.serversync.paper.zone.SharedZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.logging.Level;

public final class ServerSyncPaperPlugin extends JavaPlugin {
    private SharedZoneManager sharedZoneManager;
    private RelayPoolManager relayPoolManager;
    private GhostSyncService ghostSyncService;
    private WorldSyncService worldSyncService;
    private AutoCloseable voiceBridge;
    private NetworkMode networkMode = NetworkMode.DIRECT_FIRST;
    private byte[] rootSecret;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        String nodeId = getConfig().getString("node-id", "unknown-node");
        int radius = getConfig().getInt("shared-zone-radius", 96);

        try {
            this.networkMode = NetworkMode.fromConfig(getConfig().getString("network.mode", "direct-first"));
        } catch (IllegalArgumentException ex) {
            getLogger().warning(ex.getMessage() + "; falling back to DIRECT_FIRST.");
            this.networkMode = NetworkMode.DIRECT_FIRST;
        }

        this.sharedZoneManager = new SharedZoneManager(nodeId, radius);
        Bukkit.getPluginManager().registerEvents(new PlayerStateListener(this, sharedZoneManager), this);

        boolean needsRelaySecret = getConfig().getBoolean("relays.enabled", false)
                || getConfig().getBoolean("voice.relay.enabled", false)
                || getConfig().getBoolean("world-sync.enabled", false);
        if (needsRelaySecret) {
            try {
                this.rootSecret = loadRootSecret();
            } catch (Exception ex) {
                getLogger().severe("ServerSync relay security could not be initialized: " + ex.getMessage());
                getLogger().severe("Relay-backed features are disabled; direct-first operation can continue.");
            }
        }

        if (getConfig().getBoolean("relays.enabled", false) && rootSecret != null) {
            this.relayPoolManager = new RelayPoolManager(nodeId, getConfig(), rootSecret, getLogger(),
                    (sourceNodeId, payload) -> {
                        GhostSyncService ghosts = this.ghostSyncService;
                        if (ghosts != null) ghosts.onRelayData(sourceNodeId, payload);
                        WorldSyncService world = this.worldSyncService;
                        if (world != null) world.onRelayData(sourceNodeId, payload);
                    });
        }

        if (getConfig().getBoolean("ghosts.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("packetevents")) {
                try {
                    this.ghostSyncService = new GhostSyncService(this, nodeId, relayPoolManager);
                } catch (Throwable ex) {
                    getLogger().log(Level.SEVERE, "Could not initialize PacketEvents ghost rendering", ex);
                }
            } else {
                getLogger().warning("Ghost players are enabled but PacketEvents is not installed/enabled. Install PacketEvents 2.13.0 to render player ghosts.");
            }
        }

        this.worldSyncService = new WorldSyncService(this, nodeId, relayPoolManager, sharedZoneManager);
        if (worldSyncService.enabled()) {
            Bukkit.getPluginManager().registerEvents(new BlockIntentListener(worldSyncService), this);
        }

        registerCommands();

        if (relayPoolManager != null) relayPoolManager.start();
        if (ghostSyncService != null) ghostSyncService.start();
        if (worldSyncService != null) worldSyncService.start();

        if (getConfig().getBoolean("voice.enabled", true)) {
            if (Bukkit.getPluginManager().isPluginEnabled("voicechat")) {
                try {
                    this.voiceBridge = VoiceBridgeBootstrap.start(this, nodeId, rootSecret);
                } catch (Throwable ex) {
                    getLogger().log(Level.WARNING, "Simple Voice Chat integration could not be initialized; ServerSync will continue without voice integration.", ex);
                }
            } else {
                getLogger().info("Simple Voice Chat is not installed/enabled; voice integration is skipped.");
            }
        }

        getLogger().info("ServerSync Paper started on node " + nodeId + " with network mode " + networkMode + ".");
        if (networkMode == NetworkMode.DIRECT_FIRST && !getConfig().getBoolean("relays.enabled", false)) {
            getLogger().info("Relay pool is optional/disabled; direct-first transport remains preferred.");
        }
    }

    private void registerCommands() {
        GhostAdminCommand ghostCommand = new GhostAdminCommand(this);
        registerGhostCommand("sstp", ghostCommand);
        registerGhostCommand("ssghosts", ghostCommand);

        PluginCommand worldStatus = getCommand("ssworldstatus");
        if (worldStatus == null) {
            getLogger().warning("Command /ssworldstatus is missing from plugin.yml.");
        } else {
            worldStatus.setExecutor(new WorldAdminCommand(this));
        }
    }

    private void registerGhostCommand(String name, GhostAdminCommand handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().warning("Command /" + name + " is missing from plugin.yml.");
            return;
        }
        command.setExecutor(handler);
        command.setTabCompleter(handler);
    }

    private byte[] loadRootSecret() throws Exception {
        String mode = getConfig().getString("security.mode", "velocity-secret").trim().toLowerCase(Locale.ROOT);
        if (!mode.equals("velocity-secret") && !mode.equals("dedicated-key")) {
            throw new IllegalArgumentException("security.mode must be velocity-secret or dedicated-key");
        }
        String configuredPath = getConfig().getString("security.secret-file", "forwarding.secret").trim();
        Path path = Path.of(configuredPath).toAbsolutePath().normalize();
        if (Files.notExists(path)) {
            throw new IllegalArgumentException("Secret file does not exist: " + path);
        }
        String value = Files.readString(path, StandardCharsets.UTF_8).trim();
        if (value.length() < 16) {
            throw new IllegalArgumentException("Secret file must contain at least 16 characters");
        }
        getLogger().info("ServerSync security mode " + mode + " using " + path + ".");
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void onDisable() {
        if (voiceBridge != null) {
            try {
                voiceBridge.close();
            } catch (Exception ex) {
                getLogger().log(Level.FINE, "Voice bridge shutdown failed", ex);
            }
        }
        if (worldSyncService != null) worldSyncService.close();
        if (ghostSyncService != null) ghostSyncService.close();
        if (relayPoolManager != null) relayPoolManager.close();
    }

    public SharedZoneManager sharedZoneManager() {
        return sharedZoneManager;
    }

    public NetworkMode networkMode() {
        return networkMode;
    }

    public RelayPoolManager relayPoolManager() {
        return relayPoolManager;
    }

    public GhostSyncService ghostSyncService() {
        return ghostSyncService;
    }

    public WorldSyncService worldSyncService() {
        return worldSyncService;
    }
}
