package dev.serversync.common.protocol;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record SyncEvent(
        UUID eventId,
        EventType type,
        String sourceNode,
        String zoneId,
        long epoch,
        Instant createdAt,
        Map<String, String> data
) {
    public static SyncEvent of(EventType type, String sourceNode, String zoneId, long epoch, Map<String, String> data) {
        return new SyncEvent(UUID.randomUUID(), type, sourceNode, zoneId, epoch, Instant.now(), data);
    }
}
