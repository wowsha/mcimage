package io.github.wowsha.mcimage.image;

import io.github.wowsha.mcimage.config.PluginSettings;
import io.github.wowsha.mcimage.config.PluginSettings.ResizeMode;
import io.github.wowsha.mcimage.util.ImageSniffer;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;

/**
 * Turns raw image bytes into a 128x128 ARGB image ready to be painted on a map.
 * <p>
 * Everything in here is pure Java2D and safe to run off the main thread.
 */
public final class ImageProcessor {

    /** Side length of a Minecraft map in pixels. */
    public static final int MAP_SIZE = 128;

    /** Output of {@link #process(byte[], PluginSettings)}. */
    public record Result(BufferedImage mapImage, int originalWidth, int originalHeight, String format) {
    }

    private ImageProcessor() {
    }

    /**
     * Decodes, validates and resizes an image.
     *
     * @param data     encoded image bytes (PNG, JPEG, GIF, BMP, ...)
     * @param settings current plugin settings (limits, resize mode, background)
     * @return the processed image
     * @throws ImageProcessingException if the data is not a usable image
     */
    public static Result process(byte[] data, PluginSettings settings) throws ImageProcessingException {
        if (data == null || data.length == 0) {
            throw new ImageProcessingException("error-empty", null);
        }

        BufferedImage source;
        int width;
        int height;
        String format;
        try (MemoryCacheImageInputStream in = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                String sniffed = ImageSniffer.detectFormat(data);
                throw new ImageProcessingException("error-unsupported-format", sniffed == null ? "unknown" : sniffed);
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                format = reader.getFormatName().toLowerCase(Locale.ROOT);
                width = reader.getWidth(0);
                height = reader.getHeight(0);
                if (width <= 0 || height <= 0) {
                    throw new ImageProcessingException("error-corrupt", "empty image");
                }
                if (width > settings.maxImageDimension() || height > settings.maxImageDimension()
                        || (long) width * (long) height > settings.maxImagePixels()) {
                    throw new ImageProcessingException("error-too-large", width + "x" + height);
                }
                source = reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException ex) {
            // ImageIO throws IIOException, but also the odd IndexOutOfBounds/IllegalArgument on corrupt data.
            String reason = ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage();
            throw new ImageProcessingException("error-corrupt", reason, ex);
        }

        if (source == null) {
            throw new ImageProcessingException("error-corrupt", "decoder returned nothing");
        }

        BufferedImage map = resize(source, settings.resizeMode(), settings.background());
        return new Result(map, width, height, format);
    }

    /**
     * Resizes {@code src} onto a fresh {@value MAP_SIZE}x{@value MAP_SIZE} ARGB canvas.
     *
     * @param src        decoded source image
     * @param mode       how to deal with non-square images
     * @param background fill color, or {@code null} to keep letterbox areas transparent
     * @return the map-sized image
     */
    public static BufferedImage resize(BufferedImage src, ResizeMode mode, Color background) {
        BufferedImage out = new BufferedImage(MAP_SIZE, MAP_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            if (background != null) {
                g.setColor(background);
                g.fillRect(0, 0, MAP_SIZE, MAP_SIZE);
            }

            int sw = src.getWidth();
            int sh = src.getHeight();
            int dx = 0;
            int dy = 0;
            int dw = MAP_SIZE;
            int dh = MAP_SIZE;
            int sx = 0;
            int sy = 0;
            int cw = sw;
            int ch = sh;

            switch (mode) {
                case FIT -> {
                    double scale = Math.min((double) MAP_SIZE / sw, (double) MAP_SIZE / sh);
                    dw = Math.max(1, (int) Math.round(sw * scale));
                    dh = Math.max(1, (int) Math.round(sh * scale));
                    dx = (MAP_SIZE - dw) / 2;
                    dy = (MAP_SIZE - dh) / 2;
                }
                case CROP -> {
                    double scale = Math.max((double) MAP_SIZE / sw, (double) MAP_SIZE / sh);
                    cw = Math.max(1, Math.min(sw, (int) Math.round(MAP_SIZE / scale)));
                    ch = Math.max(1, Math.min(sh, (int) Math.round(MAP_SIZE / scale)));
                    sx = (sw - cw) / 2;
                    sy = (sh - ch) / 2;
                }
                case STRETCH -> {
                    // use the whole source and the whole destination
                }
            }

            BufferedImage region = (sx == 0 && sy == 0 && cw == sw && ch == sh) ? src : src.getSubimage(sx, sy, cw, ch);
            BufferedImage scaled = scaleWithQuality(region, dw, dh);
            g.drawImage(scaled, dx, dy, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /**
     * Scales with progressive halving for downscales (much better than a single bicubic pass for large photos)
     * and nearest-neighbour for integer upscales (keeps pixel art crisp).
     */
    private static BufferedImage scaleWithQuality(BufferedImage src, int targetW, int targetH) {
        BufferedImage current = toArgb(src);
        int w = current.getWidth();
        int h = current.getHeight();
        if (w == targetW && h == targetH) {
            return current;
        }

        boolean upscale = w <= targetW && h <= targetH;
        if (upscale) {
            boolean integerMultiple = targetW % w == 0 && targetH % h == 0 && targetW / w == targetH / h;
            Object hint = integerMultiple
                    ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                    : RenderingHints.VALUE_INTERPOLATION_BILINEAR;
            return scaleTo(current, targetW, targetH, hint);
        }

        while (w / 2 >= targetW && h / 2 >= targetH) {
            w /= 2;
            h /= 2;
            current = scaleTo(current, w, h, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        }
        if (w != targetW || h != targetH) {
            current = scaleTo(current, targetW, targetH, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        }
        return current;
    }

    private static BufferedImage scaleTo(BufferedImage src, int w, int h, Object interpolation) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(src, 0, 0, w, h, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    private static BufferedImage toArgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return src;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        try {
            g.drawImage(src, 0, 0, null);
        } finally {
            g.dispose();
        }
        return out;
    }
}
