package dev.serversync.common.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class WorldProtocol {
    private static final int MAGIC = 0x53535731; // SSW1
    private static final int VERSION = 1;
    private static final int TYPE_BLOCK_INTENT = 1;
    private static final int TYPE_BLOCK_COMMIT = 2;
    private static final int MAX_STRING_BYTES = 65_536;

    private WorldProtocol() {
    }

    public static byte[] encode(WorldMessage message) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.writeInt(MAGIC);
            out.writeByte(VERSION);
            if (message instanceof WorldBlockIntent intent) {
                out.writeByte(TYPE_BLOCK_INTENT);
                writeCommonIntent(out, intent);
            } else if (message instanceof WorldBlockCommit commit) {
                out.writeByte(TYPE_BLOCK_COMMIT);
                writeUuid(out, commit.requestId());
                writeUuid(out, commit.playerId());
                writeString(out, commit.sourceNodeId());
                writeString(out, commit.sequencerId());
                writeString(out, commit.world());
                writeString(out, commit.zoneId());
                out.writeLong(commit.epoch());
                out.writeLong(commit.sequence());
                out.writeInt(commit.x());
                out.writeInt(commit.y());
                out.writeInt(commit.z());
                writeString(out, commit.oldBlockData());
                writeString(out, commit.newBlockData());
                out.writeLong(commit.committedAtMillis());
            } else {
                throw new IOException("Unsupported world message: " + message.getClass().getName());
            }
        }
        return buffer.toByteArray();
    }

    public static WorldMessage decode(byte[] payload) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a ServerSync world frame");
            int version = in.readUnsignedByte();
            if (version != VERSION) throw new IOException("Unsupported world protocol version: " + version);
            int type = in.readUnsignedByte();
            WorldMessage message = switch (type) {
                case TYPE_BLOCK_INTENT -> new WorldBlockIntent(
                        readUuid(in), readUuid(in), readString(in), readString(in), readString(in),
                        in.readInt(), in.readInt(), in.readInt(), readString(in), readString(in), in.readLong());
                case TYPE_BLOCK_COMMIT -> new WorldBlockCommit(
                        readUuid(in), readUuid(in), readString(in), readString(in), readString(in), readString(in),
                        in.readLong(), in.readLong(), in.readInt(), in.readInt(), in.readInt(),
                        readString(in), readString(in), in.readLong());
                default -> throw new IOException("Unknown world message type: " + type);
            };
            if (in.available() != 0) throw new IOException("Trailing bytes in world frame");
            return message;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid world frame: " + ex.getMessage(), ex);
        }
    }

    public static boolean looksLikeWorldMessage(byte[] payload) {
        if (payload == null || payload.length < 6) return false;
        int magic = ((payload[0] & 0xFF) << 24)
                | ((payload[1] & 0xFF) << 16)
                | ((payload[2] & 0xFF) << 8)
                | (payload[3] & 0xFF);
        return magic == MAGIC && (payload[4] & 0xFF) == VERSION;
    }

    private static void writeCommonIntent(DataOutputStream out, WorldBlockIntent intent) throws IOException {
        writeUuid(out, intent.requestId());
        writeUuid(out, intent.playerId());
        writeString(out, intent.sourceNodeId());
        writeString(out, intent.world());
        writeString(out, intent.zoneId());
        out.writeInt(intent.x());
        out.writeInt(intent.y());
        out.writeInt(intent.z());
        writeString(out, intent.oldBlockData());
        writeString(out, intent.newBlockData());
        out.writeLong(intent.createdAtMillis());
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("World protocol string is too long");
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_STRING_BYTES) throw new IOException("Invalid world protocol string length: " + length);
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) throw new IOException("Unexpected end of world frame");
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
