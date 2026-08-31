package dev.serversync.common.protocol;

import java.util.UUID;

public record ItemClaimResult(
        UUID requestId,
        UUID itemId,
        UUID winnerPlayerId,
        boolean accepted,
        String reason
) {}
