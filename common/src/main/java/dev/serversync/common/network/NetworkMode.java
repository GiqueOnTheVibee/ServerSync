package dev.serversync.common.network;

import java.util.Locale;

/**
 * High-level transport policy for cross-node ServerSync traffic.
 */
public enum NetworkMode {
    DIRECT_FIRST,
    RELAY_ONLY,
    PEER_TO_PEER;

    public static NetworkMode fromConfig(String value) {
        if (value == null || value.isBlank()) {
            return DIRECT_FIRST;
        }

        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "direct-first", "direct_first", "direct" -> DIRECT_FIRST;
            case "relay", "relay-only", "relay_only" -> RELAY_ONLY;
            case "peer-to-peer", "peer_to_peer", "p2p" -> PEER_TO_PEER;
            default -> throw new IllegalArgumentException("Unknown ServerSync network mode: " + value);
        };
    }
}
