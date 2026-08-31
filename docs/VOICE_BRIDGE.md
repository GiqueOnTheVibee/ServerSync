# Cross-region voice bridge

This is the first full prototype for bridging Simple Voice Chat between ServerSync game nodes.

```text
Player EU -> Simple Voice Chat UDP -> Paper EU
                                  -> encrypted ServerSync UDP -> Relay UK
                                                                -> Paper US
                                                                -> locational audio at ghost XYZ
                                                                -> Player US
```

Normal local voice remains handled by Simple Voice Chat. ServerSync mirrors only the already Opus-encoded microphone frame; there is no Redis or database in the live audio path.

## Setup

Generate one test key:

```bash
openssl rand -base64 32
```

Relay:

```bash
export SERVERSYNC_VOICE_KEY='PASTE_KEY'
export SERVERSYNC_VOICE_BIND='0.0.0.0'
export SERVERSYNC_VOICE_PORT='25581'
java -jar relay/target/serversync-relay-0.1.0-SNAPSHOT.jar
```

Every Paper node uses the same key in `voice.relay.shared-key-base64`, and points `voice.relay.host` to the chosen relay.

`voice.public-host` is separate: it is the UDP endpoint Simple Voice Chat tells the Minecraft client to use. Point it at TCPShield voice support or another protected regional UDP endpoint. ServerSync's relay is for **game-node to game-node** voice and is not a drop-in replacement for client-side TCPShield UDP protection.

## Privacy

`voice.forward-group-audio` defaults to `false`. Group voice can be private/passworded, so forwarding those microphone packets as proximity audio would be a privacy bug.

## Security

The prototype encrypts/authenticates game-node <-> relay datagrams using AES-GCM. The test design shares one key across trusted nodes. Production should move to per-node identities/keys or mTLS/QUIC, plus replay protection and strict firewalling.

## Current limitations

- Relay fanout currently sends an authenticated voice frame to every other live game node. World/distance filtering happens on the target node. The coordinator will later provide an interest set so only regions with nearby listeners receive the frame.
- Old/out-of-order frames are dropped by sequence instead of adding another large jitter buffer.
- Remote audio currently uses a `LocationalAudioChannel`. 3D position is correct, but Simple Voice Chat may not identify that audio exactly like a native player for per-player mute/volume/speaker-icon UI.
- Once packet-only ghosts are implemented, test `EntitySoundPacket` using the ghost player's UUID; if the client resolves it correctly, that should become the final path.
- If the same UUID becomes a real player on the target node during handoff, remote frames for that UUID are ignored to prevent duplicate echo.
- Voice position is carried in the audio frame for now. Later it should use the exact same `RemotePlayerState` as ghost rendering so visual and audio positions cannot drift.

## Why no database here

Voice is ephemeral. Losing one UDP frame is better than waiting on SQL/Redis and adding latency. Persistent player/world state stays in the normal ServerSync ownership/event system; live Opus frames do not.
