package dev.serversync.common.voice;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class VoiceProtocol {
    public static final int MAGIC = 0x53535631;
    public static final int VERSION = 1;
    public static final int NONCE_BYTES = 12;
    public static final int GCM_TAG_BITS = 128;
    public static final int MAX_DATAGRAM_BYTES = 65_507;
    public static final int MAX_NODE_ID_BYTES = 128;
    public static final int MAX_WORLD_BYTES = 512;
    public static final int MAX_OPUS_BYTES = 16_384;

    private static final SecureRandom RANDOM = new SecureRandom();

    private VoiceProtocol() {
    }

    public static SecretKey keyFromBase64(String encoded) {
        if (encoded == null || encoded.isBlank()) {
            throw new IllegalArgumentException("Voice relay shared key is empty");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Voice relay key is not valid base64", ex);
        }
        if (bytes.length != 16 && bytes.length != 24 && bytes.length != 32) {
            throw new IllegalArgumentException("Voice relay AES key must decode to 16, 24 or 32 bytes");
        }
        return new SecretKeySpec(bytes, "AES");
    }

    public static String generateKeyBase64() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    public static byte[] encode(VoiceWireMessage message, SecretKey key) throws IOException, GeneralSecurityException {
        byte[] plaintext = encodePlaintext(message);
        byte[] nonce = new byte[NONCE_BYTES];
        RANDOM.nextBytes(nonce);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(ByteBuffer.allocate(Integer.BYTES).putInt(MAGIC).array());
        byte[] ciphertext = cipher.doFinal(plaintext);

        int total = Integer.BYTES + NONCE_BYTES + ciphertext.length;
        if (total > MAX_DATAGRAM_BYTES) {
            throw new IOException("Voice datagram exceeds UDP maximum: " + total + " bytes");
        }

        ByteBuffer packet = ByteBuffer.allocate(total);
        packet.putInt(MAGIC);
        packet.put(nonce);
        packet.put(ciphertext);
        return packet.array();
    }

    public static VoiceWireMessage decode(byte[] packet, int length, SecretKey key)
            throws IOException, GeneralSecurityException {
        if (length < Integer.BYTES + NONCE_BYTES + 16) {
            throw new IOException("Voice datagram is too short");
        }
        ByteBuffer buffer = ByteBuffer.wrap(packet, 0, length);
        if (buffer.getInt() != MAGIC) {
            throw new IOException("Invalid voice protocol magic");
        }

        byte[] nonce = new byte[NONCE_BYTES];
        buffer.get(nonce);
        byte[] ciphertext = new byte[buffer.remaining()];
        buffer.get(ciphertext);

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, nonce));
        cipher.updateAAD(ByteBuffer.allocate(Integer.BYTES).putInt(MAGIC).array());
        byte[] plaintext = cipher.doFinal(ciphertext);
        return decodePlaintext(plaintext);
    }

    private static byte[] encodePlaintext(VoiceWireMessage message) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(512);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            out.writeByte(message.type().id());
            writeString(out, message.nodeId(), MAX_NODE_ID_BYTES);

            if (message.type() == VoiceMessageType.VOICE_FRAME) {
                VoiceFrame frame = message.voiceFrame();
                out.writeLong(frame.playerId().getMostSignificantBits());
                out.writeLong(frame.playerId().getLeastSignificantBits());
                writeString(out, frame.sourceNodeId(), MAX_NODE_ID_BYTES);
                writeString(out, frame.world(), MAX_WORLD_BYTES);
                out.writeLong(frame.sequence());
                out.writeLong(frame.sentAtNanos());
                out.writeDouble(frame.x());
                out.writeDouble(frame.y());
                out.writeDouble(frame.z());
                out.writeBoolean(frame.whispering());
                byte[] opus = frame.opusData();
                if (opus.length > MAX_OPUS_BYTES) {
                    throw new IOException("Opus frame is too large: " + opus.length + " bytes");
                }
                out.writeInt(opus.length);
                out.write(opus);
            }
        }
        return bytes.toByteArray();
    }

    private static VoiceWireMessage decodePlaintext(byte[] plaintext) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(plaintext))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IOException("Unsupported voice protocol version: " + version);
            }

            VoiceMessageType type = VoiceMessageType.fromId(in.readUnsignedByte());
            String nodeId = readString(in, MAX_NODE_ID_BYTES);
            if (type != VoiceMessageType.VOICE_FRAME) {
                return VoiceWireMessage.control(type, nodeId);
            }

            UUID playerId = new UUID(in.readLong(), in.readLong());
            String sourceNodeId = readString(in, MAX_NODE_ID_BYTES);
            String world = readString(in, MAX_WORLD_BYTES);
            long sequence = in.readLong();
            long sentAtNanos = in.readLong();
            double x = in.readDouble();
            double y = in.readDouble();
            double z = in.readDouble();
            boolean whispering = in.readBoolean();
            int opusLength = in.readInt();
            if (opusLength < 0 || opusLength > MAX_OPUS_BYTES || opusLength > in.available()) {
                throw new IOException("Invalid Opus payload length: " + opusLength);
            }
            byte[] opus = in.readNBytes(opusLength);
            if (opus.length != opusLength) {
                throw new EOFException("Unexpected end of Opus payload");
            }
            if (in.available() != 0) {
                throw new IOException("Trailing bytes in voice frame");
            }

            VoiceFrame frame = new VoiceFrame(playerId, sourceNodeId, world, sequence, sentAtNanos,
                    x, y, z, whispering, opus);
            return VoiceWireMessage.voice(nodeId, frame);
        } catch (IllegalArgumentException ex) {
            throw new IOException("Malformed voice datagram", ex);
        }
    }

    private static void writeString(DataOutputStream out, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length == 0 || bytes.length > maxBytes) {
            throw new IOException("String length must be between 1 and " + maxBytes + " bytes");
        }
        out.writeShort(bytes.length);
        out.write(bytes);
    }

    private static String readString(DataInputStream in, int maxBytes) throws IOException {
        int length = in.readUnsignedShort();
        if (length <= 0 || length > maxBytes || length > in.available()) {
            throw new IOException("Invalid string length: " + length);
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException("Unexpected end of string");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
