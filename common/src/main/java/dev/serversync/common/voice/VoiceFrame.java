package dev.serversync.common.voice;

import java.util.Objects;
import java.util.UUID;

public record VoiceFrame(
        UUID playerId,
        String sourceNodeId,
        String world,
        long sequence,
        long sentAtNanos,
        double x,
        double y,
        double z,
        boolean whispering,
        byte[] opusData
) {
    public VoiceFrame {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(sourceNodeId, "sourceNodeId");
        Objects.requireNonNull(world, "world");
        Objects.requireNonNull(opusData, "opusData");
        opusData = opusData.clone();
    }

    @Override
    public byte[] opusData() {
        return opusData.clone();
    }
}
