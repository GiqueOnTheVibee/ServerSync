package dev.serversync.relay.voice;

import dev.serversync.common.security.SharedSecretKeys;
import dev.serversync.relay.RelayConfig;

import javax.crypto.SecretKey;
import java.net.InetAddress;
import java.time.Duration;

public record VoiceRelayConfig(InetAddress bindAddress, int port, SecretKey key, Duration nodeTimeout) {
    public static VoiceRelayConfig fromRelayConfig(RelayConfig config, byte[] rootSecret) {
        RelayConfig.VoiceConfig voice = config.voice();
        SecretKey key = SharedSecretKeys.deriveAesKey(rootSecret, "voice-relay-v1");
        return new VoiceRelayConfig(voice.bindAddress(), voice.port(), key, voice.nodeTimeout());
    }
}
