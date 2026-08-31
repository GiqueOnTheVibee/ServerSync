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

## First live ghost-player MVP

The Paper module now contains the first real packet-level remote-player ghost path. It uses PacketEvents 2.13.0 to render a player-shaped NPC with the remote player's profile/skin and location instead of spawning a Bukkit entity.

For the current MVP, Paper nodes can broadcast player state through the authenticated gameplay relay. The receiving Paper node renders that state as a PacketEvents ghost for local viewers in the same world name.

Requirements for ghost rendering:

```text
Paper/Purpur 1.21.4
Java 21
PacketEvents 2.13.0
ServerSync Paper
```

### One-server self test

You can verify the renderer before setting up a second backend or relay. Enable the local mirror:

```yaml
ghosts:
  enabled: true
  debug-local-mirror:
    enabled: true
    offset-x: 2.0
    offset-z: 0.0
```

Join the server and ServerSync should render a packet ghost of your own player approximately two blocks beside you. This mode is only a renderer test and should normally remain disabled.

### Two-server relay test

Both Paper nodes need unique node IDs, PacketEvents, the same ServerSync/Velocity forwarding secret and access to the same authenticated relay. For example:

```yaml
node-id: ro-1

security:
  mode: velocity-secret
  secret-file: forwarding.secret

ghosts:
  enabled: true
  debug-local-mirror:
    enabled: false

relays:
  enabled: true
  nodes:
    relay-1:
      enabled: true
      host: 0.0.0.0 # placeholder: replace before enabling
      gameplay-port: 25580
      voice-port: 25581
      capabilities:
        gameplay: true
        voice: true
        witness: true
```

Use a different `node-id` on the second Paper backend. Both players must currently be in worlds with the same Bukkit world name for the ghosts to be shown to each other.

Current ghost MVP limitations:

- default publish rate is 10 updates/sec;
- movement is updated but interpolation/prediction is not implemented yet;
- skin/profile texture is transferred;
- equipment and animations are not synchronized yet;
- sneaking/sprinting flags are carried in the protocol but are not rendered yet;
- Shared Zone interest filtering is intentionally bypassed for this first visual test;
- the current cross-node ghost MVP uses the relay transport; direct Paper-to-Paper gameplay transport is still planned.

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

## Relay security and ports

The standalone relay starts from `config.yml`; environment variables are no longer required for normal startup. If no `config.yml` exists, the relay creates one automatically.

Default listeners:

```text
TCP 25580 = authenticated gameplay/control relay
UDP 25581 = encrypted Simple Voice Chat relay
```

The default security mode is:

```yaml
security:
  mode: velocity-secret
  secret-file: forwarding.secret
```

The same Velocity forwarding secret acts as the root of trust. ServerSync derives separate keys for gameplay authentication and voice encryption, so the raw forwarding secret is not used directly as the wire key. If the relay runs on another machine, copy `forwarding.secret` next to the relay JAR or change `security.secret-file`.

The gameplay relay requires an authenticated HMAC handshake with timestamp and replay protection before a node is accepted. Optional `allowed-node-ids` can further restrict which configured node IDs are accepted even when the correct secret is known.

The relay can be started normally on Windows with the included `relay/start.bat`, or directly with:

```bash
java -Xms128M -Xmx512M -jar ServerSync-Relay.jar
```

Multiple relay endpoints can be configured in the Paper plugin under `relays.nodes`. Each relay can expose gameplay, voice and witness capabilities. `0.0.0.0` values in example remote-host fields are placeholders and must be replaced before enabling those connections.

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

- `common` - protocol, ownership records, latency-aware owner selection, direct/relay route selection, shared-secret key derivation and ghost wire messages.
- `paper` - Paper/Purpur plugin, PacketEvents ghost rendering, player-state publishing, shared-zone scaffolding, authenticated relay pool and voice integration.
- `velocity` - Velocity plugin skeleton for routing/connection metadata; common networking code is bundled into its JAR.
- `relay` - optional standalone Java 21 authenticated relay/witness service and encrypted voice relay.
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

A minimal multi-region test does not require a dedicated relay once direct gameplay transport is implemented:

```text
VPS EU: Paper/Purpur + optional Velocity/Redis
VPS US: Paper/Purpur
Relay: optional
```

The current first cross-node ghost MVP uses the relay path so the transport and renderer can be validated independently before direct peer transport is added.

## Current implementation status

Implemented/scaffolded:

- Java 21 multi-module build;
- Paper/Purpur plugin JAR;
- Velocity plugin JAR;
- standalone relay JAR;
- relay `config.yml` generation;
- Velocity-secret based relay authentication;
- HMAC timestamp/replay-protected gameplay relay handshake;
- authenticated multi-relay Paper connection pool with RTT pings;
- relay DATA/BROADCAST delivery into Paper gameplay code;
- first PacketEvents remote-player ghost renderer;
- ghost binary wire protocol with sequence numbers and profile textures;
- periodic player-state publishing and remote ghost timeout/despawn;
- local self-mirror ghost debug mode;
- encrypted Simple Voice Chat relay using a derived key;
- zone lease/epoch primitives;
- atomic in-memory item-claim prototype in relay state;
- latency-aware Shared Zone owner scoring;
- direct-first route decision logic;
- optional-relay configuration model.

Still to implement before real multi-region gameplay:

- authenticated direct node-to-node transport;
- smooth ghost interpolation/prediction;
- ghost equipment/animation/pose synchronization;
- Shared Zone interest filtering for ghost traffic;
- runtime node latency matrix and distributed player-ping exchange;
- full owner handoff state machine with hold/cooldown/epoch fencing;
- authoritative block/entity/combat synchronization;
- durable event log and chunk snapshot catch-up;
- direct cross-region voice fabric;
- coordinator/witness failover and partition handling;
- integrations for plugins such as Towny/Movecraft.
