package dev.serversync.paper.network;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

public final class RelayPoolManager implements AutoCloseable {
    private final String nodeId;
    private final FileConfiguration config;
    private final byte[] rootSecret;
    private final Logger logger;
    private final BiConsumer<String, byte[]> dataHandler;
    private final Map<String, RelayClient> clients = new ConcurrentHashMap<>();

    public RelayPoolManager(String nodeId, FileConfiguration config, byte[] rootSecret, Logger logger,
                            BiConsumer<String, byte[]> dataHandler) {
        this.nodeId = nodeId;
        this.config = config;
        this.rootSecret = rootSecret;
        this.logger = logger;
        this.dataHandler = dataHandler;
    }

    public void start() {
        if (!config.getBoolean("relays.enabled", false)) {
            logger.info("ServerSync relay pool is disabled.");
            return;
        }
        ConfigurationSection nodes = config.getConfigurationSection("relays.nodes");
        if (nodes == null || nodes.getKeys(false).isEmpty()) {
            logger.warning("relays.enabled is true but relays.nodes is empty.");
            return;
        }

        int connectTimeout = Math.max(250, config.getInt("relays.connect-timeout-millis", 3000));
        int pingInterval = Math.max(1, config.getInt("relays.ping-interval-seconds", 5));

        for (String relayId : nodes.getKeys(false)) {
            String base = "relays.nodes." + relayId;
            if (!config.getBoolean(base + ".enabled", true)) continue;
            if (!config.getBoolean(base + ".capabilities.gameplay", true)) continue;

            String host = config.getString(base + ".host", "0.0.0.0").trim();
            int port = config.getInt(base + ".gameplay-port", 25580);
            if (host.isEmpty() || host.equals("0.0.0.0")) {
                logger.warning("Relay " + relayId + " still uses the 0.0.0.0 placeholder; gameplay connection skipped.");
                continue;
            }
            if (port < 1 || port > 65535) {
                logger.warning("Relay " + relayId + " has invalid gameplay-port " + port + "; skipped.");
                continue;
            }

            RelayClient client = new RelayClient(nodeId, relayId, new InetSocketAddress(host, port), rootSecret,
                    Duration.ofMillis(connectTimeout), Duration.ofSeconds(pingInterval), logger::info, dataHandler);
            try {
                client.start();
                clients.put(relayId, client);
            } catch (Exception ex) {
                logger.warning("Could not connect to relay " + relayId + " at " + host + ':' + port + ": " + ex.getMessage());
                client.close();
            }
        }

        if (clients.isEmpty()) {
            logger.warning("No gameplay relays are currently connected. Direct-first mode can still continue without them.");
        } else {
            logger.info("Connected gameplay relays: " + String.join(", ", clients.keySet()));
        }
    }

    public int broadcast(byte[] payload) {
        int sent = 0;
        for (Map.Entry<String, RelayClient> entry : clients.entrySet()) {
            RelayClient client = entry.getValue();
            if (!client.isConnected()) continue;
            try {
                client.broadcast(payload);
                sent++;
            } catch (IOException ex) {
                logger.warning("Could not broadcast through relay " + entry.getKey() + ": " + ex.getMessage());
            }
        }
        return sent;
    }

    public Map<String, RelayClient> clients() {
        return Collections.unmodifiableMap(clients);
    }

    public RelayClient client(String relayId) {
        return clients.get(relayId);
    }

    @Override
    public void close() {
        for (RelayClient client : clients.values()) client.close();
        clients.clear();
    }
}
