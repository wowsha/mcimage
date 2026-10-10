package io.github.wowsha.mcimage.util;

import java.security.SecureRandom;
import java.util.Locale;

/**
 * Generates short, human-friendly image IDs.
 * <p>
 * The alphabet leaves out characters that are easy to confuse when read
 * aloud or typed from a screenshot ({@code 0/o}, {@code 1/l/i}).
 */
public final class IdGenerator {

    public static final String ALPHABET = "23456789abcdefghjkmnpqrstuvwxyz";
    public static final int MIN_LENGTH = 4;
    public static final int MAX_LENGTH = 32;

    private final SecureRandom random = new SecureRandom();

    /**
     * @param length id length, clamped to [{@value MIN_LENGTH}, {@value MAX_LENGTH}]
     * @return a random id
     */
    public String next(int length) {
        int len = Math.max(MIN_LENGTH, Math.min(MAX_LENGTH, length));
        char[] out = new char[len];
        for (int i = 0; i < len; i++) {
            out[i] = ALPHABET.charAt(random.nextInt(ALPHABET.length()));
        }
        return new String(out);
    }

    /**
     * Normalizes user-typed ids (case-insensitive, optional leading '#').
     *
     * @param raw typed id
     * @return normalized id, or {@code null} if it cannot be an id
     */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.strip();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        s = s.toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.length() > MAX_LENGTH) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9'))) {
                return null;
            }
        }
        return s;
    }
}
