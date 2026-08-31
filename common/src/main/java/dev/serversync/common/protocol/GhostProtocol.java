package dev.serversync.common.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class GhostProtocol {
    public static final int MAGIC = 0x53534731; // SSG1
    public static final int VERSION = 1;
    public static final int MAX_MESSAGE_BYTES = 128 * 1024;

    private static final int TYPE_STATE = 1;
    private static final int TYPE_REMOVE = 2;

    private GhostProtocol() {
    }

    public static byte[] encode(GhostMessage message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            if (message instanceof GhostPlayerState state) {
                out.writeByte(TYPE_STATE);
                writeUuid(out, state.playerId());
                writeString(out, state.playerName(), 64);
                writeString(out, state.sourceNodeId(), 128);
                writeString(out, state.world(), 256);
                out.writeDouble(state.x());
                out.writeDouble(state.y());
                out.writeDouble(state.z());
                out.writeFloat(state.yaw());
                out.writeFloat(state.pitch());
                out.writeBoolean(state.onGround());
                out.writeBoolean(state.sneaking());
                out.writeBoolean(state.sprinting());
                out.writeLong(state.sequence());
                out.writeLong(state.sentAtMillis());
                out.writeByte(state.textureProperties().size());
                for (GhostTextureProperty property : state.textureProperties()) {
                    writeString(out, property.name(), 64);
                    writeString(out, property.value(), 32_768);
                    writeNullableString(out, property.signature(), 32_768);
                }
            } else if (message instanceof GhostPlayerRemove remove) {
                out.writeByte(TYPE_REMOVE);
                writeUuid(out, remove.playerId());
                writeString(out, remove.sourceNodeId(), 128);
                out.writeLong(remove.sequence());
            } else {
                throw new IOException("Unsupported ghost message: " + message.getClass().getName());
            }
        }
        byte[] result = bytes.toByteArray();
        if (result.length > MAX_MESSAGE_BYTES) {
            throw new IOException("Ghost message exceeds " + MAX_MESSAGE_BYTES + " bytes");
        }
        return result;
    }

    public static GhostMessage decode(byte[] payload) throws IOException {
        if (payload == null || payload.length == 0 || payload.length > MAX_MESSAGE_BYTES) {
            throw new IOException("Invalid ghost payload length");
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a ServerSync ghost message");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported ghost protocol version: " + version);
            int type = in.readUnsignedByte();
            GhostMessage message = switch (type) {
                case TYPE_STATE -> readState(in);
                case TYPE_REMOVE -> readRemove(in);
                default -> throw new IOException("Unknown ghost message type: " + type);
            };
            if (in.available() != 0) throw new IOException("Trailing bytes in ghost message");
            return message;
        } catch (EOFException ex) {
            throw new IOException("Truncated ghost message", ex);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid ghost message", ex);
        }
    }

    public static boolean looksLikeGhostMessage(byte[] payload) {
        if (payload == null || payload.length < 6) return false;
        int magic = ((payload[0] & 0xff) << 24)
                | ((payload[1] & 0xff) << 16)
                | ((payload[2] & 0xff) << 8)
                | (payload[3] & 0xff);
        return magic == MAGIC;
    }

    private static GhostPlayerState readState(DataInputStream in) throws IOException {
        UUID playerId = readUuid(in);
        String playerName = readString(in, 64);
        String sourceNodeId = readString(in, 128);
        String world = readString(in, 256);
        double x = in.readDouble();
        double y = in.readDouble();
        double z = in.readDouble();
        float yaw = in.readFloat();
        float pitch = in.readFloat();
        boolean onGround = in.readBoolean();
        boolean sneaking = in.readBoolean();
        boolean sprinting = in.readBoolean();
        long sequence = in.readLong();
        long sentAtMillis = in.readLong();
        int textureCount = in.readUnsignedByte();
        if (textureCount > 16) throw new IOException("Too many texture properties");
        List<GhostTextureProperty> textures = new ArrayList<>(textureCount);
        for (int i = 0; i < textureCount; i++) {
            textures.add(new GhostTextureProperty(
                    readString(in, 64),
                    readString(in, 32_768),
                    readNullableString(in, 32_768)
            ));
        }
        return new GhostPlayerState(playerId, playerName, sourceNodeId, world, x, y, z, yaw, pitch,
                onGround, sneaking, sprinting, sequence, sentAtMillis, textures);
    }

    private static GhostPlayerRemove readRemove(DataInputStream in) throws IOException {
        return new GhostPlayerRemove(readUuid(in), readString(in, 128), in.readLong());
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        if (value == null) throw new IOException("String value is null");
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length == 0 || encoded.length > maxBytes) {
            throw new IOException("String length must be 1-" + maxBytes + " UTF-8 bytes");
        }
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readInt();
        if (length <= 0 || length > maxBytes || length > in.available()) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] encoded = in.readNBytes(length);
        if (encoded.length != length) throw new EOFException("Unexpected end of string");
        return new String(encoded, StandardCharsets.UTF_8);
    }

    private static void writeNullableString(DataOutputStream out, String value, int maxBytes) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeString(out, value, maxBytes);
    }

    private static String readNullableString(DataInputStream in, int maxBytes) throws IOException {
        return in.readBoolean() ? readString(in, maxBytes) : null;
    }
}
