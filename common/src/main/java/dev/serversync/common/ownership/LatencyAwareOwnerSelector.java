package dev.serversync.common.ownership;

import dev.serversync.common.network.NodeLink;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Scores candidate owner nodes by average estimated RTT for every player in a shared zone.
 *
 * estimated(player -> candidate) = player's local ping + RTT(current node -> candidate)
 */
public final class LatencyAwareOwnerSelector {
    public OwnerSelection select(
            String zoneId,
            String currentOwnerNodeId,
            Collection<String> candidateNodeIds,
            Collection<PlayerLatencySample> players,
            Map<NodeLink, Double> interNodeRttMs,
            double minimumImprovementMs
    ) {
        Objects.requireNonNull(zoneId, "zoneId");
        Objects.requireNonNull(candidateNodeIds, "candidateNodeIds");
        Objects.requireNonNull(players, "players");
        Objects.requireNonNull(interNodeRttMs, "interNodeRttMs");

        if (candidateNodeIds.isEmpty()) {
            throw new IllegalArgumentException("At least one owner candidate is required");
        }

        Map<String, Double> scores = new LinkedHashMap<>();
        for (String candidate : candidateNodeIds) {
            scores.put(candidate, averageLatency(candidate, players, interNodeRttMs));
        }

        String bestNode = scores.entrySet().stream()
                .min(Comparator.comparingDouble(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .orElseThrow();
        double bestScore = scores.get(bestNode);

        if (!Double.isFinite(bestScore)) {
            if (currentOwnerNodeId != null && scores.containsKey(currentOwnerNodeId)) {
                return new OwnerSelection(zoneId, currentOwnerNodeId, scores, false,
                        "no candidate has complete latency data; retaining current owner");
            }
            return new OwnerSelection(zoneId, bestNode, scores, false,
                    "no candidate has complete latency data; provisional owner selected");
        }

        if (currentOwnerNodeId != null && scores.containsKey(currentOwnerNodeId)) {
            double currentScore = scores.get(currentOwnerNodeId);
            double requiredImprovement = Math.max(0.0D, minimumImprovementMs);

            if (Double.isFinite(currentScore)
                    && !currentOwnerNodeId.equals(bestNode)
                    && currentScore - bestScore < requiredImprovement) {
                return new OwnerSelection(zoneId, currentOwnerNodeId, scores, false,
                        "best candidate does not beat current owner by the minimum improvement threshold");
            }

            if (currentOwnerNodeId.equals(bestNode)) {
                return new OwnerSelection(zoneId, currentOwnerNodeId, scores, false,
                        "current owner has the best average latency");
            }
        }

        return new OwnerSelection(zoneId, bestNode, scores,
                currentOwnerNodeId != null && !currentOwnerNodeId.equals(bestNode),
                "candidate has the lowest average estimated player latency");
    }

    private double averageLatency(
            String candidate,
            Collection<PlayerLatencySample> players,
            Map<NodeLink, Double> interNodeRttMs
    ) {
        if (players.isEmpty()) {
            return 0.0D;
        }

        double sum = 0.0D;
        for (PlayerLatencySample player : players) {
            double estimated = player.localPingMs();
            if (!player.currentNodeId().equals(candidate)) {
                Double linkRtt = interNodeRttMs.get(NodeLink.of(player.currentNodeId(), candidate));
                if (linkRtt == null || !Double.isFinite(linkRtt) || linkRtt < 0.0D) {
                    return Double.POSITIVE_INFINITY;
                }
                estimated += linkRtt;
            }
            sum += estimated;
        }
        return sum / players.size();
    }
}
