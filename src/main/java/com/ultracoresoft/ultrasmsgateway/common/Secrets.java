package com.ultracoresoft.ultrasmsgateway.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * API keys and device tokens: 256 random bits, shown once, stored as a SHA-256 hash.
 * A fast hash is enough here because the secrets are long random values, not passwords.
 */
public final class Secrets {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int DISPLAY_PREFIX_LENGTH = 12;

    private Secrets() {
    }

    public static String generate(String prefix) {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return prefix + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String secret) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** Start of the secret, safe to display so that an administrator can recognise it. */
    public static String displayPrefix(String secret) {
        return secret.substring(0, Math.min(DISPLAY_PREFIX_LENGTH, secret.length()));
    }
}
