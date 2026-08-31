package dev.serversync.common.protocol;

import java.util.List;
import java.util.UUID;

public record GhostPlayerState(
        UUID playerId,
        String playerName,
        String sourceNodeId,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        boolean onGround,
        boolean sneaking,
        boolean sprinting,
        long sequence,
        long sentAtMillis,
        List<GhostTextureProperty> textureProperties
) implements GhostMessage {
    public GhostPlayerState {
        if (playerId == null) throw new IllegalArgumentException("playerId is required");
        if (playerName == null || playerName.isBlank() || playerName.length() > 64) {
            throw new IllegalArgumentException("playerName must contain 1-64 characters");
        }
        if (sourceNodeId == null || sourceNodeId.isBlank() || sourceNodeId.length() > 128) {
            throw new IllegalArgumentException("sourceNodeId must contain 1-128 characters");
        }
        if (world == null || world.isBlank() || world.length() > 256) {
            throw new IllegalArgumentException("world must contain 1-256 characters");
        }
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("Ghost coordinates must be finite");
        }
        if (!Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Ghost rotation must be finite");
        }
        textureProperties = textureProperties == null ? List.of() : List.copyOf(textureProperties);
        if (textureProperties.size() > 16) throw new IllegalArgumentException("Too many texture properties");
    }
}
