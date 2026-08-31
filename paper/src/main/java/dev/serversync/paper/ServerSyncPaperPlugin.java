package dev.serversync.paper;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.serversync.common.network.NetworkMode;
import dev.serversync.common.security.SharedSecretKeys;
import dev.serversync.paper.listener.BlockIntentListener;
import dev.serversync.paper.listener.PlayerStateListener;
import dev.serversync.paper.network.RelayPoolManager;
import dev.serversync.paper.voice.ServerSyncVoicechatPlugin;
import dev.serversync.paper.voice.VoiceRelayClient;
import dev.serversync.paper.zone.SharedZoneManager;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;

public final class ServerSyncPaperPlugin extends JavaPlugin {
    private SharedZoneManager sharedZoneManager;
    private RelayPoolManager relayPoolManager;
    private VoiceRelayClient voiceRelayClient;
    private ServerSyncVoicechatPlugin voicechatPlugin;
    private NetworkMode networkMode = NetworkMode.DIRECT_FIRST;
    private byte[] rootSecret;

    @Override
    public void onEnable() {
        saveDefaultConfig();
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
        Bukkit.getPluginManager().registerEvents(new BlockIntentListener(this, sharedZoneManager), this);

        boolean needsRelaySecret = getConfig().getBoolean("relays.enabled", false)
                || getConfig().getBoolean("voice.relay.enabled", false);
        if (needsRelaySecret) {
            try {
                this.rootSecret = loadRootSecret();
            } catch (Exception ex) {
                getLogger().severe("ServerSync relay security could not be initialized: " + ex.getMessage());
                getLogger().severe("Relay connections are disabled; direct-first operation can continue.");
            }
        }

        if (getConfig().getBoolean("relays.enabled", false) && rootSecret != null) {
            this.relayPoolManager = new RelayPoolManager(nodeId, getConfig(), rootSecret, getLogger());
            relayPoolManager.start();
        }

        if (getConfig().getBoolean("voice.enabled", true)) {
            setupVoiceBridge(nodeId);
        }

        getLogger().info("ServerSync Paper started on node " + nodeId + " with network mode " + networkMode + ".");
        if (networkMode == NetworkMode.DIRECT_FIRST && !getConfig().getBoolean("relays.enabled", false)) {
            getLogger().info("Relay pool is optional/disabled; direct-first transport remains preferred.");
        }
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

    private void setupVoiceBridge(String nodeId) {
        BukkitVoicechatService service = getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            getLogger().warning("Simple Voice Chat API service was not found. Voice bridge is disabled on this node.");
            return;
        }

        this.voicechatPlugin = new ServerSyncVoicechatPlugin(this, nodeId);
        service.registerPlugin(voicechatPlugin);

        if (!getConfig().getBoolean("voice.relay.enabled", false)) {
            getLogger().info("ServerSync voice host integration enabled; cross-region voice relay is disabled.");
            return;
        }
        if (rootSecret == null) {
            getLogger().warning("Voice relay requested but the ServerSync root secret is unavailable.");
            return;
        }

        SecretKey key = SharedSecretKeys.deriveAesKey(rootSecret, "voice-relay-v1");
        int bindPort = getConfig().getInt("voice.relay.bind-port", 0);
        long heartbeatSeconds = Math.max(1L, getConfig().getLong("voice.relay.heartbeat-seconds", 5L));

        List<String> relayIds = voiceRelayCandidates();
        if (relayIds.isEmpty()) {
            getLogger().warning("Voice relay is enabled but no relay with capabilities.voice=true is configured.");
            return;
        }

        for (String relayId : relayIds) {
            String base = "relays.nodes." + relayId;
            String host = getConfig().getString(base + ".host", "0.0.0.0").trim();
            int port = getConfig().getInt(base + ".voice-port", 25581);
            if (host.isEmpty() || host.equals("0.0.0.0")) {
                getLogger().warning("Voice relay " + relayId + " still uses the 0.0.0.0 placeholder; skipped.");
                continue;
            }
            try {
                VoiceRelayClient client = new VoiceRelayClient(getLogger(), nodeId, new InetSocketAddress(host, port),
                        bindPort, key, Duration.ofSeconds(heartbeatSeconds), voicechatPlugin::onRemoteFrame);
                client.start();
                this.voiceRelayClient = client;
                voicechatPlugin.attachRelayClient(client);
                getLogger().info("Cross-region voice relay connected through " + relayId + " at " + host + ':' + port + ".");
                return;
            } catch (Exception ex) {
                getLogger().log(Level.WARNING, "Could not start voice relay through " + relayId, ex);
            }
        }
        getLogger().warning("No configured voice relay could be started.");
    }

    private List<String> voiceRelayCandidates() {
        List<String> result = new ArrayList<>();
        ConfigurationSection nodes = getConfig().getConfigurationSection("relays.nodes");
        if (nodes == null) return result;

        String preferred = getConfig().getString("voice.relay.preferred-relay", "").trim();
        if (!preferred.isEmpty() && nodes.contains(preferred)
                && getConfig().getBoolean("relays.nodes." + preferred + ".enabled", true)
                && getConfig().getBoolean("relays.nodes." + preferred + ".capabilities.voice", true)) {
            result.add(preferred);
        }
        for (String relayId : nodes.getKeys(false)) {
            if (result.contains(relayId)) continue;
            String base = "relays.nodes." + relayId;
            if (!getConfig().getBoolean(base + ".enabled", true)) continue;
            if (!getConfig().getBoolean(base + ".capabilities.voice", true)) continue;
            result.add(relayId);
        }
        return result;
    }

    @Override
    public void onDisable() {
        if (voiceRelayClient != null) voiceRelayClient.close();
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
}
