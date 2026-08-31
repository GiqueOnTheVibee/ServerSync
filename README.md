# ServerSync

Private experimental multi-region Minecraft synchronization project for Paper/Purpur + Velocity.

The target is one logical Minecraft world across multiple regional game nodes while keeping players connected to a nearby backend. ServerSync is designed so a relay is optional: nodes should communicate directly whenever possible and only fall back to a relay/witness when a direct path is unavailable or materially worse.

> This repository is still a prototype, not a production-ready anti-dupe system. Cross-region consistency, entity ownership and plugin-driven mutations still need deterministic testing before live deployment.

## Current direction

```text
                    Velocity
               routing / metadata
                      |
          +-----------+-----------+
          |                       |
          v                       v
      Paper EU  <============>  Paper US
          |       direct mesh      |
          |                        |
          +----------+-------------+
                     |
                     v
              Relay / Witness
                 optional
```

The relay is not the world host and does not contain a Minecraft `world/` directory. Every Paper/Purpur node keeps its own local world data. World changes are synchronized as ordered events/snapshots rather than by sharing the live region files over a network filesystem.

## Shared-zone owner selection

A Shared Zone has one authoritative owner for state that cannot safely be modified concurrently. The owner candidate is scored using average estimated player latency:

```text
estimatedLatency(player, candidate)
  = playerLocalPing
  + interNodeRTT(playerCurrentNode, candidate)

owner = candidate with the lowest average score
```

ServerSync now contains generic owner-selection primitives in `common`. The current owner is retained unless another candidate is better by a configurable threshold. The planned runtime election also uses a hold time and cooldown so ownership does not flap between nodes.

## Direct-first routing

ServerSync supports three architecture modes:

- `direct-first` - recommended. Nodes use direct links normally; relay is fallback only.
- `relay-only` - forces the relay, mainly useful for tests/debugging.
- `peer-to-peer` - never uses the relay. During a partition, contested irreversible operations must fail closed to avoid split-brain dupes.

The route selector can compare direct RTT against the two relay legs and only choose the relay when it is unavailable directly or the relay route is materially faster.

## Core rules

1. A player has exactly one authoritative game node at a time.
2. A Shared Zone has exactly one authority for non-mergeable state.
3. Ghost players are visual replicas only.
4. Normal gameplay stays local whenever possible.
5. Relay is optional and should not be in the normal hot path.
6. Every cross-region event needs a globally unique event id.
7. Item pickup needs a globally unique logical item id and one authoritative claim winner.
8. Ownership changes use epochs so stale owners cannot commit after a handoff.
9. If authority cannot be proven safely, contested irreversible operations fail closed instead of allowing split brain.

## Modules

- `common` - protocol, ownership records, latency-aware owner selection and direct/relay route selection.
- `paper` - Paper/Purpur plugin skeleton, shared-zone detection, block intents, player-state publishing and voice integration.
- `velocity` - Velocity plugin skeleton for routing/connection metadata; common networking code is bundled into its JAR.
- `relay` - optional standalone Java 21 relay/witness service and current voice relay prototype.
- `docs` - architecture, direct-first networking and voice notes.

## Build

Requires Java 21 and Maven.

```bash
mvn clean package
```

The deployable components are:

```text
paper/target/serversync-paper-0.1.0-SNAPSHOT.jar
velocity/target/serversync-velocity-0.1.0-SNAPSHOT.jar
relay/target/serversync-relay-0.1.0-SNAPSHOT.jar
```

The `common` module is an internal library and is shaded into deployable components where needed.

## Cost-efficient deployment target

A minimal multi-region test does not require a dedicated relay:

```text
VPS EU: Paper/Purpur + optional Velocity/Redis
VPS US: Paper/Purpur
Relay: none
```

A small relay VPS can be added later only if direct routing, firewall/NAT, witness/quorum or fallback requirements justify it.

## Current implementation status

Implemented/scaffolded:

- Java 21 multi-module build;
- Paper/Purpur plugin JAR;
- Velocity plugin JAR;
- standalone relay JAR;
- Simple Voice Chat relay prototype;
- zone lease/epoch primitives;
- atomic in-memory item-claim prototype in relay state;
- latency-aware Shared Zone owner scoring;
- direct-first route decision logic;
- optional-relay configuration model.

Still to implement before real multi-region gameplay:

- authenticated direct node-to-node transport;
- runtime latency probes and distributed metric exchange;
- full owner handoff state machine with hold/cooldown/epoch fencing;
- packet-only remote player ghosts;
- authoritative block/entity/combat synchronization;
- durable event log and chunk snapshot catch-up;
- direct cross-region voice fabric;
- coordinator/witness failover and partition handling;
- integrations for plugins such as Towny/Movecraft.
