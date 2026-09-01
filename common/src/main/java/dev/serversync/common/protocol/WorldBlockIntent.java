package dev.serversync.common.protocol;

import java.util.Objects;
import java.util.UUID;

public record WorldBlockIntent(
        UUID requestId,
        UUID playerId,
        String sourceNodeId,
        String world,
        String zoneId,
        int x,
        int y,
        int z,
        String oldBlockData,
        String newBlockData,
        long createdAtMillis
) implements WorldMessage {
    public WorldBlockIntent {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(playerId, "playerId");
        sourceNodeId = requireText(sourceNodeId, "sourceNodeId");
        world = requireText(world, "world");
        zoneId = requireText(zoneId, "zoneId");
        oldBlockData = requireText(oldBlockData, "oldBlockData");
        newBlockData = requireText(newBlockData, "newBlockData");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
