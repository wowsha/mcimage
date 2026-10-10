package io.github.wowsha.mcimage.image;

/**
 * Thrown when uploaded data cannot be turned into a map image.
 * <p>
 * {@link #messageKey()} names the user-facing message in {@code config.yml};
 * {@link #detail()} is optional extra text (dimensions, format, ...).
 */
public final class ImageProcessingException extends Exception {

    private static final long serialVersionUID = 1L;

    private final String messageKey;
    private final String detail;

    public ImageProcessingException(String messageKey, String detail) {
        super(messageKey + (detail == null ? "" : ": " + detail));
        this.messageKey = messageKey;
        this.detail = detail == null ? "" : detail;
    }

    public ImageProcessingException(String messageKey, String detail, Throwable cause) {
        super(messageKey + (detail == null ? "" : ": " + detail), cause);
        this.messageKey = messageKey;
        this.detail = detail == null ? "" : detail;
    }

    public String messageKey() {
        return messageKey;
    }

    public String detail() {
        return detail;
    }
}
