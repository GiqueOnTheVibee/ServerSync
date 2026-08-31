package dev.serversync.common.protocol;

import java.util.UUID;

public record GhostPlayerRemove(UUID playerId, String sourceNodeId, long sequence) implements GhostMessage {
    public GhostPlayerRemove {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        if (sourceNodeId == null || sourceNodeId.isBlank() || sourceNodeId.length() > 128) {
            throw new IllegalArgumentException("sourceNodeId must contain 1-128 characters");
        }
    }
}
