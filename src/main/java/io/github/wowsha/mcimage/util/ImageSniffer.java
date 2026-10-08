package io.github.wowsha.mcimage.util;

/**
 * Cheap, dependency-free checks on raw image bytes.
 * <p>
 * Used to decide whether a chat-sized paste is a complete image or only the
 * first part of a multi-part upload, without touching ImageIO on the main thread.
 */
public final class ImageSniffer {

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] PNG_IEND = {'I', 'E', 'N', 'D', (byte) 0xAE, 0x42, 0x60, (byte) 0x82};

    private ImageSniffer() {
    }

    /**
     * @param data raw bytes
     * @return a short format name ("png", "jpeg", "gif", "bmp", "webp") or {@code null} if unknown
     */
    public static String detectFormat(byte[] data) {
        if (data == null) {
            return null;
        }
        if (startsWith(data, PNG_MAGIC)) {
            return "png";
        }
        if (data.length >= 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (data.length >= 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F' && data[3] == '8') {
            return "gif";
        }
        if (data.length >= 14 && data[0] == 'B' && data[1] == 'M') {
            return "bmp";
        }
        if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "webp";
        }
        return null;
    }

    /**
     * Heuristically checks whether {@code data} is a whole image file rather than a truncated prefix.
     * Unknown formats are assumed complete and left to ImageIO.
     *
     * @param data raw bytes
     * @return false if the data is clearly truncated
     */
    public static boolean looksComplete(byte[] data) {
        if (data == null || data.length < 24) {
            return false;
        }
        String format = detectFormat(data);
        if (format == null) {
            return true;
        }
        int n = data.length;
        switch (format) {
            case "png":
                return endsWith(data, PNG_IEND);
            case "jpeg":
                return (data[n - 2] & 0xFF) == 0xFF && (data[n - 1] & 0xFF) == 0xD9;
            case "gif":
                return (data[n - 1] & 0xFF) == 0x3B;
            case "bmp": {
                long declared = readLeUInt(data, 2);
                return declared == n;
            }
            case "webp": {
                long declared = readLeUInt(data, 4) + 8;
                return declared == n || declared == n - 1; // RIFF pads odd sizes
            }
            default:
                return true;
        }
    }

    private static long readLeUInt(byte[] d, int offset) {
        return (d[offset] & 0xFFL)
                | ((d[offset + 1] & 0xFFL) << 8)
                | ((d[offset + 2] & 0xFFL) << 16)
                | ((d[offset + 3] & 0xFFL) << 24);
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean endsWith(byte[] data, byte[] suffix) {
        if (data.length < suffix.length) {
            return false;
        }
        int off = data.length - suffix.length;
        for (int i = 0; i < suffix.length; i++) {
            if (data[off + i] != suffix[i]) {
                return false;
            }
        }
        return true;
    }
}
