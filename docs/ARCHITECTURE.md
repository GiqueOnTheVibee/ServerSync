# ServerSync architecture

## Objective

Keep players on a nearby regional Minecraft node while maintaining one logical world.

## Normal mode

If no remote-region player is relevant to a zone, gameplay remains fully local.

## Shared-zone mode

When players from different regions can see or affect the same zone, nodes publish only the state that must be shared:

- player transform / equipment / animations for ghost rendering;
- block intents and commits;
- entity state that crosses the shared boundary;
- combat intents;
- globally identified item spawns and pickups.

## Relay / Arbiter

A shared zone is assigned to one relay at a time. The assignment carries an epoch.

The relay:

1. validates the zone epoch;
2. assigns an order to conflicting events;
3. atomically claims globally identified item drops;
4. rejects stale writers after a relay/zone handover.

A relay must not become the persistent database. Its hot state should be reproducible from an event log or coordinator state later.

## Item pickup anti-dupe model

A logical dropped item gets one UUID. Only one authoritative copy is interactable; remote copies are visual ghosts.

A pickup request is:

```text
(itemId, playerUUID, sourceNode, zoneId, epoch, requestId)
```

The relay performs an atomic `putIfAbsent`-style claim. One player wins. Every duplicate request is rejected.

For a production system this in-memory operation must move to a strongly consistent store or replicated relay state, otherwise relay crashes can forget claims.

## Important unresolved cases

- two nodes predict the same instant block break before arbitration;
- instant-break blocks where there is no useful mining-intent window;
- containers modified concurrently from different regions;
- piston/redstone/explosion chains crossing ownership boundaries;
- projectiles and combat lag compensation;
- entity ownership migration;
- plugin-driven world mutations that bypass normal Bukkit events;
- relay crash after accepting an event but before all nodes receive it;
- coordinator partition / split brain;
- persistent item identity through hoppers, crafting, chests, death drops and plugin inventories;
- Towny and Movecraft integration.

## Safety rule

No production deployment until the system has deterministic tests for duplicate item claims, stale epochs, replayed events, relay failover and conflicting block commits.
