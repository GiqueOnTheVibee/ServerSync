# ServerSync

Experimental multi-region Minecraft synchronization prototype for Paper/Purpur + Velocity.

The goal is to keep each player connected to a nearby Minecraft node, show remote players as lightweight ghosts, and use a relay/arbiter only when two regions share gameplay state.

> This repository is an architecture prototype, not a production-ready anti-dupe system yet. The dangerous cases are intentionally documented so we can test them before using it on a live server.

## Current design

```text
Player EU -> Velocity -> Paper EU ----\
                                      Relay / Arbiter -> ordered shared events
Player US -> Velocity -> Paper US ----/

Paper EU -> local DB/cache
Paper US -> local DB/cache

Coordinator/Redis:
- player/server ownership
- shared-zone membership
- relay assignment
- server health
- non-critical messaging
```

### Rules

1. A player has exactly one authoritative game node at a time.
2. A chunk/zone has exactly one authority for state that cannot safely be merged.
3. Ghost players are visual replicas only.
4. Normal gameplay stays local whenever possible.
5. The relay is used only for shared/cross-region state.
6. Every cross-region event has a globally unique event id.
7. Item pickup uses a globally unique item id + player UUID so the same logical item cannot be claimed twice.
8. Relay changes use epochs so an old relay cannot keep writing after ownership moved.

## Modules

- `common` - protocol, ids, ownership records and relay client primitives.
- `paper` - Paper/Purpur plugin skeleton: shared-zone detection, block intents and ghost-state publishing.
- `velocity` - Velocity plugin skeleton: node/relay selection and player routing metadata.
- `relay` - lightweight arbiter service that orders events and performs atomic item claims.
- `docs` - architecture notes and unresolved problems.

## Build

Requires Java 21 and Maven.

```bash
mvn clean package
```

Artifacts are produced inside each module's `target/` directory.

## Status

Initial prototype. The first goal is to prove:

- two Paper nodes can publish player/ghost state;
- one relay can order shared-zone events;
- duplicate item claims are rejected atomically;
- relay selection can be changed per shared zone;
- stale relay epochs are rejected.

After that we can add PacketEvents-based ghost rendering, Redis-backed coordinator state, persistent databases, Towny integration and Movecraft integration.
