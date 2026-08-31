package dev.serversync.common.protocol;

import java.util.UUID;

public record ItemClaimRequest(
        UUID requestId,
        UUID itemId,
        UUID playerId,
        String sourceNode,
        String zoneId,
        long epoch
) {
    public static ItemClaimRequest create(UUID itemId, UUID playerId, String sourceNode, String zoneId, long epoch) {
        return new ItemClaimRequest(UUID.randomUUID(), itemId, playerId, sourceNode, zoneId, epoch);
    }
}
