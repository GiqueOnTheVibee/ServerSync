package dev.serversync.relay;

public final class StaleEpochException extends RuntimeException {
    public StaleEpochException(String zoneId, long expected, long received) {
        super("Stale epoch for zone " + zoneId + ": expected " + expected + ", received " + received);
    }
}
