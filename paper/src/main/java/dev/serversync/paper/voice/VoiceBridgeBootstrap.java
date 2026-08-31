package dev.serversync.paper.voice;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import dev.serversync.common.security.SharedSecretKeys;
import dev.serversync.paper.ServerSyncPaperPlugin;
import org.bukkit.configuration.ConfigurationSection;

import javax.crypto.SecretKey;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;

/**
 * Loads all Simple Voice Chat API types behind an optional-dependency boundary.
 * This class must only be touched after the voicechat plugin is confirmed enabled.
 */
public final class VoiceBridgeBootstrap {
    private VoiceBridgeBootstrap() {
    }

    public static AutoCloseable start(ServerSyncPaperPlugin plugin, String nodeId, byte[] rootSecret) {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service == null) {
            plugin.getLogger().warning("Simple Voice Chat API service was not found. Voice bridge is disabled on this node.");
            return () -> { };
        }

        ServerSyncVoicechatPlugin voicechatPlugin = new ServerSyncVoicechatPlugin(plugin, nodeId);
        service.registerPlugin(voicechatPlugin);

        if (!plugin.getConfig().getBoolean("voice.relay.enabled", false)) {
            plugin.getLogger().info("ServerSync voice host integration enabled; cross-region voice relay is disabled.");
            return () -> { };
        }
        if (rootSecret == null) {
            plugin.getLogger().warning("Voice relay requested but the ServerSync root secret is unavailable.");
            return () -> { };
        }

        SecretKey key = SharedSecretKeys.deriveAesKey(rootSecret, "voice-relay-v1");
        int bindPort = plugin.getConfig().getInt("voice.relay.bind-port", 0);
        long heartbeatSeconds = Math.max(1L, plugin.getConfig().getLong("voice.relay.heartbeat-seconds", 5L));

        List<String> relayIds = voiceRelayCandidates(plugin);
        if (relayIds.isEmpty()) {
            plugin.getLogger().warning("Voice relay is enabled but no relay with capabilities.voice=true is configured.");
            return () -> { };
        }

        for (String relayId : relayIds) {
            String base = "relays.nodes." + relayId;
            String host = plugin.getConfig().getString(base + ".host", "0.0.0.0").trim();
            int port = plugin.getConfig().getInt(base + ".voice-port", 25581);
            if (host.isEmpty() || host.equals("0.0.0.0")) {
                plugin.getLogger().warning("Voice relay " + relayId + " still uses the 0.0.0.0 placeholder; skipped.");
                continue;
            }
            try {
                VoiceRelayClient client = new VoiceRelayClient(plugin.getLogger(), nodeId, new InetSocketAddress(host, port),
                        bindPort, key, Duration.ofSeconds(heartbeatSeconds), voicechatPlugin::onRemoteFrame);
                client.start();
                voicechatPlugin.attachRelayClient(client);
                plugin.getLogger().info("Cross-region voice relay connected through " + relayId + " at " + host + ':' + port + ".");
                return client;
            } catch (Exception ex) {
                plugin.getLogger().log(Level.WARNING, "Could not start voice relay through " + relayId, ex);
            }
        }

        plugin.getLogger().warning("No configured voice relay could be started.");
        return () -> { };
    }

    private static List<String> voiceRelayCandidates(ServerSyncPaperPlugin plugin) {
        List<String> result = new ArrayList<>();
        ConfigurationSection nodes = plugin.getConfig().getConfigurationSection("relays.nodes");
        if (nodes == null) return result;

        String preferred = plugin.getConfig().getString("voice.relay.preferred-relay", "").trim();
        if (!preferred.isEmpty() && nodes.contains(preferred)
                && plugin.getConfig().getBoolean("relays.nodes." + preferred + ".enabled", true)
                && plugin.getConfig().getBoolean("relays.nodes." + preferred + ".capabilities.voice", true)) {
            result.add(preferred);
        }
        for (String relayId : nodes.getKeys(false)) {
            if (result.contains(relayId)) continue;
            String base = "relays.nodes." + relayId;
            if (!plugin.getConfig().getBoolean(base + ".enabled", true)) continue;
            if (!plugin.getConfig().getBoolean(base + ".capabilities.voice", true)) continue;
            result.add(relayId);
        }
        return result;
    }
}
