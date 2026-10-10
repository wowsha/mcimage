package io.github.wowsha.mcimage.map;

import java.awt.image.BufferedImage;
import org.bukkit.entity.Player;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;

/**
 * Renders one stored image onto a map, exactly once per canvas.
 * <p>
 * The renderer is non-contextual, so all viewers share a single canvas and
 * the (comparatively expensive) palette conversion happens only once per
 * server session. The image is handed over asynchronously via
 * {@link #setImage(BufferedImage)}; until it arrives the map simply stays blank.
 */
public final class ImageMapRenderer extends MapRenderer {

    private final String imageId;
    private final boolean dither;
    private volatile BufferedImage pending;
    private volatile boolean painted;

    public ImageMapRenderer(String imageId, boolean dither) {
        super(false);
        this.imageId = imageId;
        this.dither = dither;
    }

    public String imageId() {
        return imageId;
    }

    /**
     * Supplies (or replaces) the image to draw. Safe to call from any thread.
     *
     * @param image 128x128 ARGB image
     */
    public void setImage(BufferedImage image) {
        this.pending = image;
        this.painted = false;
    }

    public boolean isPainted() {
        return painted;
    }

    @Override
    public void render(MapView map, MapCanvas canvas, Player player) {
        if (painted) {
            return;
        }
        BufferedImage image = pending;
        if (image == null) {
            return;
        }
        MapCanvasPainter.paint(canvas, image, dither);
        painted = true;
        pending = null; // the canvas keeps the pixels; free the image
    }
}
