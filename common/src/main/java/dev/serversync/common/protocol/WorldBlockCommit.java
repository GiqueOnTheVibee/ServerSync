package dev.serversync.common.protocol;

import java.util.Objects;
import java.util.UUID;

public record WorldBlockCommit(
        UUID requestId,
        UUID playerId,
        String sourceNodeId,
        String sequencerId,
        String world,
        String zoneId,
        long epoch,
        long sequence,
        int x,
        int y,
        int z,
        String oldBlockData,
        String newBlockData,
        long committedAtMillis
) implements WorldMessage {
    public WorldBlockCommit {
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(playerId, "playerId");
        sourceNodeId = requireText(sourceNodeId, "sourceNodeId");
        sequencerId = requireText(sequencerId, "sequencerId");
        world = requireText(world, "world");
        zoneId = requireText(zoneId, "zoneId");
        oldBlockData = requireText(oldBlockData, "oldBlockData");
        newBlockData = requireText(newBlockData, "newBlockData");
        if (epoch < 1) throw new IllegalArgumentException("epoch must be >= 1");
        if (sequence < 1) throw new IllegalArgumentException("sequence must be >= 1");
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }
}
