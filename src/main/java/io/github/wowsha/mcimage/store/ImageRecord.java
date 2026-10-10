package io.github.wowsha.mcimage.store;

import java.util.UUID;

/**
 * Metadata of one stored image.
 *
 * @param id             short public id, e.g. {@code k3x9ab}
 * @param mapId          id of the Minecraft map that displays the image
 * @param owner          creator uuid, or {@code null} when created from the console
 * @param ownerName      creator name at creation time
 * @param createdAt      creation time in epoch milliseconds
 * @param source         how it was uploaded: {@code base64}, {@code url} or {@code file}
 * @param originalWidth  width of the uploaded image before resizing
 * @param originalHeight height of the uploaded image before resizing
 */
public record ImageRecord(
        String id,
        int mapId,
        UUID owner,
        String ownerName,
        long createdAt,
        String source,
        int originalWidth,
        int originalHeight
) {

    public ImageRecord withMapId(int newMapId) {
        return new ImageRecord(id, newMapId, owner, ownerName, createdAt, source, originalWidth, originalHeight);
    }

    public boolean isOwnedBy(UUID uuid) {
        return owner != null && owner.equals(uuid);
    }
}
