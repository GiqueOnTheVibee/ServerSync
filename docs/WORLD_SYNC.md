# ServerSync world synchronization

## Test7 MVP

The first world-sync stage keeps a normal local Minecraft world on every Paper/Purpur node. It does not share `.mca` files over NFS and the relay does not run a Minecraft world.

Before enabling world sync, every Paper node must start from the same compatible world snapshot. From that point forward, block break/place mutations can be replicated through the authoritative ServerSync relay.

Flow:

```text
Paper ro-1                  Relay                  Paper ro-2
    |                         |                         |
    | WORLD_BLOCK_INTENT      |                         |
    |------------------------>|                         |
    |                         | assign zone sequence    |
    |                         | create BLOCK_COMMIT     |
    |<------------------------|------------------------>|
    |       ordered COMMIT    |       ordered COMMIT    |
```

The relay currently assigns:

- one monotonic sequence per Shared Zone;
- epoch `1` as a temporary MVP value;
- a relay-generated commit timestamp;
- the relay ID as the sequencer ID.

The source Paper receives its own commit as an acknowledgement/order stamp. Other Paper nodes apply the committed BlockData locally.

## Configuration

```yaml
world-sync:
  enabled: true
  authoritative-relay: relay-1
  fail-closed-when-relay-unavailable: true
  debug: false
  blocks:
    break: true
    place: true
    apply-physics: false
  world-map: {}
```

`authoritative-relay` is important when more than one relay is configured: a world mutation is sent through exactly one relay so two relays cannot independently assign competing commits. If the preferred relay is unavailable, the current MVP deterministically selects another connected relay by relay ID. Production multi-relay failover still needs persistent shared epoch/sequence state or consensus before it can be considered split-brain safe.

`fail-closed-when-relay-unavailable: true` prevents new player block break/place events while world sync is enabled and no gameplay relay is connected.

Use `/ssworldstatus` to inspect relay connectivity, intents sent, commits received, remote blocks applied, sequence gaps and known zone sequences.

## Current limitations

This is deliberately the first map-interaction milestone, not production-safe full world authority yet.

- The source Paper still performs the original Bukkit block event optimistically before the relay commit returns.
- Block drops, tool durability, containers, inventories and item rewards are not relay-authoritative yet.
- Redstone, fluids, pistons, explosions, mobs, crops and other simulation changes are not synchronized yet.
- Relay zone sequences and request deduplication are currently in memory and reset on relay restart.
- There is no durable event log, database catch-up or chunk snapshot transfer yet.
- A sequence gap is logged and the newest commit is applied; missing commits are not replayed yet.
- The relay does not yet calculate latency-based Shared Zone ownership. Persistent owner/epoch fencing is the next authority stage.

The next world-sync stages should add durable event storage/catch-up, Shared Zone ownership epochs, strict pre-commit interaction handling for dupe-sensitive actions, containers/items, then entity/simulation ownership.
