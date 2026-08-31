package dev.serversync.common.protocol;

public record GhostTextureProperty(String name, String value, String signature) {
    public GhostTextureProperty {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Texture property name is required");
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Texture property value is required");
    }
}
