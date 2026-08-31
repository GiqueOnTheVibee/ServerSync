package dev.serversync.common.voice;

public enum VoiceMessageType {
    REGISTER(1),
    HEARTBEAT(2),
    VOICE_FRAME(3);

    private final int id;

    VoiceMessageType(int id) {
        this.id = id;
    }

    public int id() {
        return id;
    }

    public static VoiceMessageType fromId(int id) {
        for (VoiceMessageType value : values()) {
            if (value.id == id) {
                return value;
            }
        }
        throw new IllegalArgumentException("Unknown voice message type: " + id);
    }
}
