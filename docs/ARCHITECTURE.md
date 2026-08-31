# ServerSync architecture

## Objective

Keep players on nearby regional Minecraft nodes while maintaining one logical world. The system should scale from two game servers with no dedicated relay to a larger global mesh.

## World placement

The relay never hosts the Minecraft map. Every Paper/Purpur game node stores its own local world data. Nodes start from the same compatible world snapshot and synchronize changes as events. Large catch-up gaps should eventually use chunk snapshots plus event replay instead of copying live `.mca` files on every change.

Do not place a live cross-continent Minecraft world on NFS/shared storage.

## Normal mode

If no remote-region player is relevant to a zone, gameplay remains fully local.

```text
player -> nearby Paper/Purpur -> local world
```

No relay is required.

## Shared-zone mode

When players attached to different game nodes can see or affect the same area, ServerSync creates/uses a Shared Zone. Nodes publish only state that must be shared:

- player transform / equipment / animations for ghost rendering;
- block intents and authoritative commits;
- entity state that crosses the shared boundary;
- combat/projectile intents and authoritative results;
- globally identified item spawns and pickups.

Private/local operations such as inventory UI navigation should stay local unless they mutate shared authoritative state.

## Shared-zone owner

Each Shared Zone has exactly one authoritative game-node owner at a time. Owner selection is latency aware.

For player `p` currently attached to node `A`, and candidate owner `B`:

```text
estimatedLatency(p, B)
  = localPlayerPing(p)
  + interNodeRTT(A, B)
```

If `A == B`, the inter-node term is zero.

The candidate score is the average estimated latency across every active player in the Shared Zone. The lowest score wins, but runtime election must use hysteresis:

- another candidate must improve the score by a configured minimum (default plan: 15 ms);
- it must remain better for a hold period (default plan: 10 s);
- owner changes have a cooldown (default plan: 30 s).

Ownership handoff increments the zone epoch. A stale owner from an older epoch cannot commit authoritative mutations.

## Direct-first mesh

The preferred data path is game-node to game-node:

```text
Paper EU <=================> Paper US
              direct
```

The relay is optional and is used only if needed:

```text
Paper EU -> Relay -> Paper US
```

The generic route selector supports:

- `direct-first`: prefer direct; relay only if direct is unavailable or materially slower;
- `relay-only`: force relay for tests/debugging;
- `peer-to-peer`: no relay at all.

Direct and relay routes should be chosen independently per node pair. One broken EU-US link does not force unrelated EU-DE traffic through the relay.

## Velocity role

Velocity is useful for connection routing and player/backend metadata, but it must not be a hard dependency for already-running gameplay synchronization.

Velocity can provide or help derive:

- which backend a player is attached to;
- connection/ping samples;
- initial regional backend routing.

The Shared Zone owner calculation belongs to ServerSync's common/runtime logic and combines player ping with measured game-node RTT. If Velocity goes down, already-connected Paper nodes should continue operating as far as their current state permits.

## Relay / witness role

The relay should not be the permanent gameplay authority for every event. In the direct-first design it can provide some or all of:

1. fallback forwarding for bad/unreachable direct links;
2. witness/quorum metadata for ownership and epochs;
3. recovery/failover coordination;
4. temporary relay for current voice prototype;
5. optional arbitration for strict operations while direct arbitration is not yet implemented.

A relay must not become the persistent world database. Its hot state should be reproducible from durable state later.

## Partition / split-brain rule

Without a witness, a two-node partition cannot safely let both sides become owner of the same Shared Zone.

```text
EU  X  US
```

If authority cannot be proven, contested irreversible shared mutations must fail closed rather than risk duplicate rewards or conflicting state. Local/reversible presentation may continue where safe.

With a third witness, a future implementation can use quorum semantics so only the side with sufficient authority may keep committing.

## Item pickup anti-dupe model

A logical dropped item gets one globally unique id. Only one authoritative copy is interactable; remote copies are visual ghosts.

A pickup request contains at least:

```text
(itemId, playerUUID, sourceNode, zoneId, epoch, requestId)
```

Exactly one claim may move the logical item from `AVAILABLE` to `CLAIMED`. Remote nodes must not irreversibly add the item to an inventory before the authoritative claim succeeds.

The current relay has an in-memory atomic-claim prototype. Production requires durable/replicated state so relay crashes cannot forget accepted claims.

## World synchronization plan

All game nodes should begin with the same compatible map snapshot. After that, sync changes rather than entire world files.

Each chunk/zone eventually needs version information such as:

```text
world
chunkX/chunkZ
ownerNodeId
epoch
version
```

A returning node can replay missing events when the gap is small. If the gap is too large, it should download a newer chunk snapshot and then replay only the remaining events.

## Cost model

Minimal intended deployment:

```text
Game node EU
Game node US
Relay: optional
```

Velocity, Redis/coordinator and monitoring may be colocated with an existing node for small installations. A dedicated relay should only be added when routing, witness or fallback requirements justify the cost.

## Important unresolved cases

- authenticated direct node-to-node transport;
- runtime RTT probes and secure metric exchange;
- distributed owner handoff with epochs, hold time and cooldown;
- two nodes predicting the same instant block break before commit;
- instant-break blocks where there is no useful mining-intent window;
- containers modified concurrently from different regions;
- piston/redstone/explosion chains crossing ownership boundaries;
- projectiles and combat lag compensation;
- entity ownership migration;
- Minecraft-driven ticks (water, lava, crops, mobs, TNT) on replicated chunks;
- plugin-driven world mutations that bypass normal Bukkit events;
- direct voice routing and protected UDP ingress;
- node crash after granting a reward but before durable commit;
- persistent item identity through hoppers, crafting, chests, death drops and plugin inventories;
- Towny and Movecraft integration.

## Safety rule

No production deployment until deterministic tests cover duplicate item claims, stale epochs, replayed events, owner handoff, network partition behavior, relay failover and conflicting block commits.
