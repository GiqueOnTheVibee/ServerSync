package dev.serversync.common.voice;

import java.util.Objects;

public record VoiceWireMessage(
        VoiceMessageType type,
        String nodeId,
        VoiceFrame voiceFrame
) {
    public VoiceWireMessage {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(nodeId, "nodeId");
        if (type == VoiceMessageType.VOICE_FRAME && voiceFrame == null) {
            throw new IllegalArgumentException("VOICE_FRAME requires a VoiceFrame payload");
        }
        if (type != VoiceMessageType.VOICE_FRAME && voiceFrame != null) {
            throw new IllegalArgumentException(type + " must not contain a VoiceFrame payload");
        }
    }

    public static VoiceWireMessage control(VoiceMessageType type, String nodeId) {
        return new VoiceWireMessage(type, nodeId, null);
    }

    public static VoiceWireMessage voice(String nodeId, VoiceFrame frame) {
        return new VoiceWireMessage(VoiceMessageType.VOICE_FRAME, nodeId, frame);
    }
}
