package world.marchoflegacy.serversync.relay.voice;

import world.marchoflegacy.serversync.common.voice.VoiceProtocol;

import javax.crypto.SecretKey;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;

public record VoiceRelayConfig(InetAddress bindAddress, int port, SecretKey key, Duration nodeTimeout) {
    public static VoiceRelayConfig fromEnvironment() throws UnknownHostException {
        String bindHost = env("SERVERSYNC_VOICE_BIND", "0.0.0.0");
        int port = Integer.parseInt(env("SERVERSYNC_VOICE_PORT", "25581"));
        long timeoutSeconds = Long.parseLong(env("SERVERSYNC_VOICE_NODE_TIMEOUT_SECONDS", "15"));
        String keyBase64 = System.getenv("SERVERSYNC_VOICE_KEY");
        if (keyBase64 == null || keyBase64.isBlank()) {
            throw new IllegalArgumentException("SERVERSYNC_VOICE_KEY is required. Generate one with: openssl rand -base64 32");
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("SERVERSYNC_VOICE_PORT must be between 1 and 65535");
        }
        return new VoiceRelayConfig(InetAddress.getByName(bindHost), port, VoiceProtocol.keyFromBase64(keyBase64),
                Duration.ofSeconds(Math.max(5L, timeoutSeconds)));
    }

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
