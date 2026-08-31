package dev.serversync.paper;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import dev.serversync.common.network.NetworkMode;
import dev.serversync.common.voice.VoiceProtocol;
import dev.serversync.paper.listener.BlockIntentListener;
import dev.serversync.paper.listener.PlayerStateListener;
import dev.serversync.paper.voice.ServerSyncVoicechatPlugin;
import dev.serversync.paper.voice.VoiceRelayClient;
import dev.serversync.paper.zone.SharedZoneManager;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.logging.Level;

public final class ServerSyncPaperPlugin extends JavaPlugin {
    private SharedZoneManager sharedZoneManager;
    private VoiceRelayClient voiceRelayClient;
    private ServerSyncVoicechatPlugin voicechatPlugin;
    private NetworkMode networkMode = NetworkMode.DIRECT_FIRST;

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

        if (getConfig().getBoolean("voice.enabled", true)) {
            setupVoiceBridge(nodeId);
        }
        getLogger().info("ServerSync Paper started on node " + nodeId + " with network mode " + networkMode + ".");
        if (networkMode == NetworkMode.DIRECT_FIRST && !getConfig().getBoolean("relay.enabled", false)) {
            getLogger().info("Gameplay relay is optional/disabled; direct-first transport will be preferred as it is implemented.");
        }
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
            getLogger().info("ServerSync voice host integration enabled; optional cross-region voice relay is disabled.");
            return;
        }

        String keyBase64 = getConfig().getString("voice.relay.shared-key-base64", "").trim();
        if (keyBase64.isEmpty()) {
            getLogger().warning("voice.relay.shared-key-base64 is empty. Cross-region voice relay is disabled.");
            return;
        }

        try {
            SecretKey key = VoiceProtocol.keyFromBase64(keyBase64);
            String host = getConfig().getString("voice.relay.host", "127.0.0.1");
            int port = getConfig().getInt("voice.relay.port", 25581);
            int bindPort = getConfig().getInt("voice.relay.bind-port", 0);
            long heartbeatSeconds = Math.max(1L, getConfig().getLong("voice.relay.heartbeat-seconds", 5L));

            this.voiceRelayClient = new VoiceRelayClient(getLogger(), nodeId, new InetSocketAddress(host, port),
                    bindPort, key, Duration.ofSeconds(heartbeatSeconds), voicechatPlugin::onRemoteFrame);
            voicechatPlugin.attachRelayClient(voiceRelayClient);
            voiceRelayClient.start();
            getLogger().info("Cross-region voice relay connected to " + host + ":" + port + ".");
        } catch (Exception ex) {
            getLogger().log(Level.SEVERE, "Failed to start ServerSync cross-region voice relay", ex);
        }
    }

    @Override
    public void onDisable() {
        if (voiceRelayClient != null) {
            voiceRelayClient.close();
        }
    }

    public SharedZoneManager sharedZoneManager() {
        return sharedZoneManager;
    }

    public NetworkMode networkMode() {
        return networkMode;
    }
}
