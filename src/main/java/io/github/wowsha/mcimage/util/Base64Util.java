package io.github.wowsha.mcimage.util;

import java.util.Base64;
import java.util.regex.Pattern;

/**
 * Lenient base64 handling for data pasted into chat.
 * <p>
 * Accepts standard and URL-safe alphabets, optional padding, optional
 * {@code data:image/png;base64,} prefixes, surrounding quotes and whitespace.
 */
public final class Base64Util {

    private static final Pattern DATA_URL_PREFIX = Pattern.compile("^data:[^,]*;base64,", Pattern.CASE_INSENSITIVE);

    private Base64Util() {
    }

    /**
     * Normalizes pasted input to the standard base64 alphabet without whitespace.
     *
     * @param input raw user input
     * @return normalized base64 text (may still be invalid)
     */
    public static String normalize(String input) {
        String s = input.strip();
        s = DATA_URL_PREFIX.matcher(s).replaceFirst("");
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '"' || c == '\'' || c == '`') {
                continue;
            }
            if (c == '-') {
                c = '+';
            } else if (c == '_') {
                c = '/';
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * @param normalized text produced by {@link #normalize(String)}
     * @return true if every character belongs to the base64 alphabet
     */
    public static boolean isBase64Alphabet(String normalized) {
        if (normalized.isEmpty()) {
            return false;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '+' || c == '/' || c == '=';
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /**
     * Decodes normalized base64, tolerating missing padding.
     *
     * @param normalized text produced by {@link #normalize(String)}
     * @return decoded bytes
     * @throws IllegalArgumentException if the text is not valid base64
     */
    public static byte[] decode(String normalized) {
        String s = normalized;
        int pad = s.indexOf('=');
        if (pad >= 0) {
            s = s.substring(0, pad);
        }
        int rem = s.length() % 4;
        if (rem == 1) {
            throw new IllegalArgumentException("Invalid base64 length");
        } else if (rem == 2) {
            s = s + "==";
        } else if (rem == 3) {
            s = s + "=";
        }
        return Base64.getDecoder().decode(s);
    }

    /**
     * @param normalized normalized base64 text
     * @return true if the text can be decoded with {@link #decode(String)}
     */
    public static boolean isDecodable(String normalized) {
        if (!isBase64Alphabet(normalized)) {
            return false;
        }
        try {
            decode(normalized);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
