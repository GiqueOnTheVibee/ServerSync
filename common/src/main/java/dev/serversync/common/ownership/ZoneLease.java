package dev.serversync.common.ownership;

import java.time.Instant;

public record ZoneLease(
        String zoneId,
        String relayId,
        long epoch,
        Instant expiresAt
) {
    public boolean isExpired() {
        return Instant.now().isAfter(expiresAt);
    }
}
