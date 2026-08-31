package dev.serversync.common.ownership;

import java.util.Map;
import java.util.Objects;

public record OwnerSelection(
        String zoneId,
        String selectedOwnerNodeId,
        Map<String, Double> averageLatencyByNodeMs,
        boolean ownerChanged,
        String reason
) {
    public OwnerSelection {
        zoneId = Objects.requireNonNull(zoneId, "zoneId");
        selectedOwnerNodeId = Objects.requireNonNull(selectedOwnerNodeId, "selectedOwnerNodeId");
        averageLatencyByNodeMs = Map.copyOf(averageLatencyByNodeMs);
        reason = Objects.requireNonNull(reason, "reason");
    }
}
