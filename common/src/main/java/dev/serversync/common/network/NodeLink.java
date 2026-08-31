package dev.serversync.common.network;

import java.util.Objects;

/**
 * Undirected node-to-node link key used by latency measurements.
 */
public record NodeLink(String first, String second) {
    public NodeLink {
        first = Objects.requireNonNull(first, "first");
        second = Objects.requireNonNull(second, "second");
        if (first.isBlank() || second.isBlank()) {
            throw new IllegalArgumentException("Node ids cannot be blank");
        }
        if (first.compareTo(second) > 0) {
            String tmp = first;
            first = second;
            second = tmp;
        }
    }

    public static NodeLink of(String a, String b) {
        return new NodeLink(a, b);
    }
}
