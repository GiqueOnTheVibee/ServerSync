package dev.serversync.common.ownership;

import java.util.Objects;
import java.util.UUID;

/**
 * Smoothed player latency as observed on the game node the player is attached to.
 */
public record PlayerLatencySample(UUID playerId, String currentNodeId, double localPingMs) {
    public PlayerLatencySample {
        playerId = Objects.requireNonNull(playerId, "playerId");
        currentNodeId = Objects.requireNonNull(currentNodeId, "currentNodeId");
        if (currentNodeId.isBlank()) {
            throw new IllegalArgumentException("currentNodeId cannot be blank");
        }
        if (!Double.isFinite(localPingMs) || localPingMs < 0.0D) {
            throw new IllegalArgumentException("localPingMs must be a finite non-negative value");
        }
    }
}
