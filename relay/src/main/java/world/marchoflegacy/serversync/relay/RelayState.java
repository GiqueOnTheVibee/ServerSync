package world.marchoflegacy.serversync.relay;

import world.marchoflegacy.serversync.common.protocol.ItemClaimRequest;
import world.marchoflegacy.serversync.common.protocol.ItemClaimResult;
import world.marchoflegacy.serversync.common.protocol.SyncEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class RelayState {
    private final String relayId;
    private final AtomicLong globalSequence = new AtomicLong();
    private final Map<UUID, UUID> itemClaims = new ConcurrentHashMap<>();
    private final Map<String, Long> zoneEpochs = new ConcurrentHashMap<>();

    public RelayState(String relayId) {
        this.relayId = relayId;
    }

    public String relayId() {
        return relayId;
    }

    public long nextSequence(SyncEvent event) {
        validateEpoch(event.zoneId(), event.epoch());
        return globalSequence.incrementAndGet();
    }

    public ItemClaimResult claim(ItemClaimRequest request) {
        validateEpoch(request.zoneId(), request.epoch());
        UUID winner = itemClaims.putIfAbsent(request.itemId(), request.playerId());
        if (winner == null || winner.equals(request.playerId())) {
            return new ItemClaimResult(request.requestId(), request.itemId(), request.playerId(), true, "accepted");
        }
        return new ItemClaimResult(request.requestId(), request.itemId(), winner, false, "item already claimed");
    }

    public void assignZoneEpoch(String zoneId, long epoch) {
        zoneEpochs.compute(zoneId, (ignored, current) -> {
            if (current != null && epoch < current) {
                throw new IllegalStateException("Cannot move zone epoch backwards");
            }
            return epoch;
        });
    }

    private void validateEpoch(String zoneId, long eventEpoch) {
        long expected = zoneEpochs.getOrDefault(zoneId, eventEpoch);
        if (eventEpoch != expected) {
            throw new StaleEpochException(zoneId, expected, eventEpoch);
        }
    }
}
