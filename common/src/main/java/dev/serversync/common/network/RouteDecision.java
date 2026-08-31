package dev.serversync.common.network;

public record RouteDecision(
        RouteKind kind,
        double directRttMs,
        double relayRttMs,
        String reason
) {
}
