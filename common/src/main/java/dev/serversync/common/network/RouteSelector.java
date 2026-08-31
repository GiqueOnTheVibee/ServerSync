package dev.serversync.common.network;

/**
 * Chooses a cross-node path. DIRECT_FIRST keeps the relay out of the hot path
 * unless the direct route is unavailable or materially worse.
 */
public final class RouteSelector {
    private RouteSelector() {
    }

    public static RouteDecision select(
            NetworkMode mode,
            double directRttMs,
            double sourceToRelayRttMs,
            double relayToTargetRttMs,
            double relayMinimumImprovementMs
    ) {
        boolean directAvailable = usable(directRttMs);
        boolean relayAvailable = usable(sourceToRelayRttMs) && usable(relayToTargetRttMs);
        double relayRttMs = relayAvailable ? sourceToRelayRttMs + relayToTargetRttMs : Double.POSITIVE_INFINITY;
        double minimumImprovement = Math.max(0.0D, relayMinimumImprovementMs);

        if (mode == NetworkMode.PEER_TO_PEER) {
            return directAvailable
                    ? new RouteDecision(RouteKind.DIRECT, directRttMs, relayRttMs, "peer-to-peer mode")
                    : new RouteDecision(RouteKind.UNAVAILABLE, directRttMs, relayRttMs, "direct route unavailable");
        }

        if (mode == NetworkMode.RELAY_ONLY) {
            return relayAvailable
                    ? new RouteDecision(RouteKind.RELAY, directRttMs, relayRttMs, "relay-only mode")
                    : new RouteDecision(RouteKind.UNAVAILABLE, directRttMs, relayRttMs, "relay route unavailable");
        }

        if (!directAvailable) {
            return relayAvailable
                    ? new RouteDecision(RouteKind.RELAY, directRttMs, relayRttMs, "direct route unavailable")
                    : new RouteDecision(RouteKind.UNAVAILABLE, directRttMs, relayRttMs, "no usable route");
        }

        if (relayAvailable && relayRttMs + minimumImprovement < directRttMs) {
            return new RouteDecision(RouteKind.RELAY, directRttMs, relayRttMs,
                    "relay is materially faster than direct");
        }

        return new RouteDecision(RouteKind.DIRECT, directRttMs, relayRttMs, "direct-first preferred");
    }

    private static boolean usable(double value) {
        return Double.isFinite(value) && value >= 0.0D;
    }
}
