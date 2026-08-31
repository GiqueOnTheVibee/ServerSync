package dev.serversync.common.security;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;

public final class SharedSecretKeys {
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] PREFIX = "ServerSync-v1".getBytes(StandardCharsets.UTF_8);

    private SharedSecretKeys() {
    }

    public static byte[] derive(byte[] rootSecret, String context) {
        if (rootSecret == null || rootSecret.length < 16) {
            throw new IllegalArgumentException("ServerSync root secret must contain at least 16 bytes");
        }
        if (context == null || context.isBlank()) {
            throw new IllegalArgumentException("Key derivation context cannot be empty");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(rootSecret, HMAC_ALGORITHM));
            mac.update(PREFIX);
            mac.update((byte) 0);
            mac.update(context.getBytes(StandardCharsets.UTF_8));
            return mac.doFinal();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to derive ServerSync key", ex);
        }
    }

    public static SecretKey deriveAesKey(byte[] rootSecret, String context) {
        return new SecretKeySpec(derive(rootSecret, context), "AES");
    }

    public static String hmacBase64(byte[] key, String message) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key, HMAC_ALGORITHM));
            return Base64.getEncoder().encodeToString(mac.doFinal(message.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to authenticate ServerSync message", ex);
        }
    }

    public static boolean verifyHmacBase64(byte[] key, String message, String encodedMac) {
        if (encodedMac == null || encodedMac.isBlank()) {
            return false;
        }
        byte[] expected;
        byte[] received;
        try {
            expected = Base64.getDecoder().decode(hmacBase64(key, message));
            received = Base64.getDecoder().decode(encodedMac.trim());
        } catch (IllegalArgumentException ex) {
            return false;
        }
        return MessageDigest.isEqual(expected, received);
    }
}
