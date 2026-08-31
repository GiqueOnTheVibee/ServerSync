package dev.serversync.relay;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public record RelayConfig(
        Path configPath,
        String relayId,
        String region,
        GameplayConfig gameplay,
        SecurityConfig security,
        VoiceConfig voice
) {
    public static final String DEFAULT_CONFIG = """
            # ServerSync standalone relay configuration.
            # 0.0.0.0 is a bind address, not a public relay IP.
            relay:
              id: relay-1
              region: unknown

            network:
              gameplay:
                enabled: true
                bind-host: 0.0.0.0
                port: 25580
                max-connections: 256
                auth-timeout-millis: 5000
                max-payload-bytes: 1048576

            security:
              # velocity-secret reuses the Velocity forwarding secret as the root of trust.
              # Copy forwarding.secret next to the relay JAR when the relay is on another machine.
              # dedicated-key uses the same file mechanism but with a ServerSync-only secret.
              mode: velocity-secret
              secret-file: forwarding.secret
              timestamp-tolerance-seconds: 30
              replay-cache-seconds: 60
              # Empty means every node with the correct secret may authenticate.
              # Example: [ro-1, us-1]
              allowed-node-ids: []

            voice:
              enabled: true
              bind-host: 0.0.0.0
              port: 25581
              node-timeout-seconds: 15
            """;

    public static RelayConfig loadOrCreate(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        Path parent = absolute.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        if (Files.notExists(absolute)) {
            Files.writeString(absolute, DEFAULT_CONFIG, StandardCharsets.UTF_8);
            System.out.println("Created default relay config: " + absolute);
        }

        Map<String, Object> root;
        try (InputStream in = Files.newInputStream(absolute)) {
            Object loaded = new Yaml().load(in);
            root = asMap(loaded, "root");
        }

        Map<String, Object> relay = map(root, "relay");
        Map<String, Object> network = map(root, "network");
        Map<String, Object> gameplayMap = map(network, "gameplay");
        Map<String, Object> securityMap = map(root, "security");
        Map<String, Object> voiceMap = map(root, "voice");

        String relayId = string(relay, "id", "relay-1");
        String region = string(relay, "region", "unknown");

        GameplayConfig gameplay = new GameplayConfig(
                bool(gameplayMap, "enabled", true),
                address(gameplayMap, "bind-host", "0.0.0.0"),
                port(gameplayMap, "port", 25580),
                positiveInt(gameplayMap, "max-connections", 256),
                positiveInt(gameplayMap, "auth-timeout-millis", 5000),
                positiveInt(gameplayMap, "max-payload-bytes", 1_048_576)
        );

        String mode = string(securityMap, "mode", "velocity-secret").toLowerCase(Locale.ROOT);
        if (!mode.equals("velocity-secret") && !mode.equals("dedicated-key")) {
            throw new IllegalArgumentException("security.mode must be velocity-secret or dedicated-key");
        }
        String secretFileValue = string(securityMap, "secret-file", "forwarding.secret");
        Path secretFile = Path.of(secretFileValue);
        if (!secretFile.isAbsolute()) {
            Path base = parent == null ? Path.of(".").toAbsolutePath().normalize() : parent;
            secretFile = base.resolve(secretFile).normalize();
        }

        SecurityConfig security = new SecurityConfig(
                mode,
                secretFile,
                Duration.ofSeconds(positiveLong(securityMap, "timestamp-tolerance-seconds", 30)),
                Duration.ofSeconds(positiveLong(securityMap, "replay-cache-seconds", 60)),
                stringSet(securityMap.get("allowed-node-ids"))
        );

        VoiceConfig voice = new VoiceConfig(
                bool(voiceMap, "enabled", true),
                address(voiceMap, "bind-host", "0.0.0.0"),
                port(voiceMap, "port", 25581),
                Duration.ofSeconds(positiveLong(voiceMap, "node-timeout-seconds", 15))
        );

        return new RelayConfig(absolute, relayId, region, gameplay, security, voice);
    }

    public byte[] readRootSecret() throws IOException {
        if (Files.notExists(security.secretFile())) {
            throw new IOException("Security secret file does not exist: " + security.secretFile()
                    + ". For velocity-secret mode, copy Velocity's forwarding.secret here or change security.secret-file.");
        }
        String value = Files.readString(security.secretFile(), StandardCharsets.UTF_8).trim();
        if (value.length() < 16) {
            throw new IOException("Security secret is too short; expected at least 16 characters");
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public record GameplayConfig(
            boolean enabled,
            InetAddress bindAddress,
            int port,
            int maxConnections,
            int authTimeoutMillis,
            int maxPayloadBytes
    ) {
    }

    public record SecurityConfig(
            String mode,
            Path secretFile,
            Duration timestampTolerance,
            Duration replayCache,
            Set<String> allowedNodeIds
    ) {
        public boolean nodeAllowed(String nodeId) {
            return allowedNodeIds.isEmpty() || allowedNodeIds.contains(nodeId);
        }
    }

    public record VoiceConfig(boolean enabled, InetAddress bindAddress, int port, Duration nodeTimeout) {
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, String name) {
        if (value == null) {
            return Collections.emptyMap();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(name + " must be a YAML map");
        }
        return (Map<String, Object>) map;
    }

    private static Map<String, Object> map(Map<String, Object> parent, String key) {
        return asMap(parent.get(key), key);
    }

    private static String string(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private static boolean bool(Map<String, Object> map, String key, boolean fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        if (value instanceof Boolean b) return b;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static int port(Map<String, Object> map, String key, int fallback) {
        int value = integer(map, key, fallback);
        if (value < 1 || value > 65535) {
            throw new IllegalArgumentException(key + " must be between 1 and 65535");
        }
        return value;
    }

    private static int positiveInt(Map<String, Object> map, String key, int fallback) {
        int value = integer(map, key, fallback);
        if (value < 1) throw new IllegalArgumentException(key + " must be greater than 0");
        return value;
    }

    private static long positiveLong(Map<String, Object> map, String key, long fallback) {
        Object value = map.get(key);
        long parsed = value == null ? fallback : Long.parseLong(String.valueOf(value));
        if (parsed < 1) throw new IllegalArgumentException(key + " must be greater than 0");
        return parsed;
    }

    private static int integer(Map<String, Object> map, String key, int fallback) {
        Object value = map.get(key);
        return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    }

    private static InetAddress address(Map<String, Object> map, String key, String fallback) throws UnknownHostException {
        return InetAddress.getByName(string(map, key, fallback));
    }

    private static Set<String> stringSet(Object value) {
        if (value == null) return Set.of();
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("allowed-node-ids must be a YAML list");
        }
        Set<String> result = new LinkedHashSet<>();
        for (Object entry : list) {
            String text = String.valueOf(entry).trim();
            if (!text.isEmpty()) result.add(text);
        }
        return Collections.unmodifiableSet(result);
    }
}
