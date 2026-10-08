package io.github.wowsha.mcimage.map;

import io.github.wowsha.mcimage.image.ImageProcessor;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import org.bukkit.map.MapCanvas;

/**
 * Paints a 128x128 ARGB image onto a {@link MapCanvas}.
 * <p>
 * With dithering enabled, the image is quantized to the map palette with
 * Floyd–Steinberg error diffusion. The palette lookup is delegated to the
 * server through {@link MapCanvas#setPixelColor(int, int, Color)} /
 * {@link MapCanvas#getPixelColor(int, int)}, so no deprecated
 * {@code MapPalette} magic values are needed and new palette colors added by
 * future Minecraft versions are picked up automatically.
 * <p>
 * Must be called on the main thread (the canvas belongs to the server).
 */
public final class MapCanvasPainter {

    private static final Color TRANSPARENT = new Color(0, 0, 0, 0);

    private MapCanvasPainter() {
    }

    /**
     * @param canvas target canvas
     * @param image  128x128 image (larger images are clipped, smaller ones leave the rest untouched)
     * @param dither whether to apply Floyd–Steinberg dithering
     */
    public static void paint(MapCanvas canvas, BufferedImage image, boolean dither) {
        if (!dither) {
            canvas.drawImage(0, 0, image);
            return;
        }

        final int size = ImageProcessor.MAP_SIZE;
        final int w = Math.min(size, image.getWidth());
        final int h = Math.min(size, image.getHeight());
        final int[] pixels = image.getRGB(0, 0, w, h, null, 0, w);

        // Error buffers for the current and the next row, three channels each, one pixel of padding on both sides.
        final int stride = (w + 2) * 3;
        float[] current = new float[stride];
        float[] next = new float[stride];

        for (int y = 0; y < h; y++) {
            Arrays.fill(next, 0f);
            for (int x = 0; x < w; x++) {
                int argb = pixels[y * w + x];
                int alpha = argb >>> 24;
                int base = (x + 1) * 3;

                if (alpha < 128) {
                    canvas.setPixelColor(x, y, TRANSPARENT);
                    continue;
                }

                int r = clamp(((argb >> 16) & 0xFF) + Math.round(current[base]));
                int g = clamp(((argb >> 8) & 0xFF) + Math.round(current[base + 1]));
                int b = clamp((argb & 0xFF) + Math.round(current[base + 2]));

                canvas.setPixelColor(x, y, new Color(r, g, b));
                Color actual = canvas.getPixelColor(x, y);
                if (actual == null) {
                    continue;
                }

                float er = r - actual.getRed();
                float eg = g - actual.getGreen();
                float eb = b - actual.getBlue();

                diffuse(current, base + 3, er, eg, eb, 7f / 16f); // right
                diffuse(next, base - 3, er, eg, eb, 3f / 16f);    // bottom-left
                diffuse(next, base, er, eg, eb, 5f / 16f);        // bottom
                diffuse(next, base + 3, er, eg, eb, 1f / 16f);    // bottom-right
            }
            float[] swap = current;
            current = next;
            next = swap;
        }
    }

    private static void diffuse(float[] buffer, int index, float er, float eg, float eb, float factor) {
        buffer[index] += er * factor;
        buffer[index + 1] += eg * factor;
        buffer[index + 2] += eb * factor;
    }

    private static int clamp(int v) {
        return v < 0 ? 0 : Math.min(255, v);
    }
}
