package io.github.wowsha.mcimage.store;

import io.github.wowsha.mcimage.util.IdGenerator;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.imageio.ImageIO;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Persists image metadata ({@code images.yml}) and the 128x128 PNGs ({@code images/<id>.png}).
 * <p>
 * All metadata access is synchronized so the asynchronous upload pipeline can
 * reserve ids and write files while the main thread reads records.
 */
public final class ImageStore {

    private static final String ROOT = "images";

    private final Logger logger;
    private final File dataFile;
    private final File imagesDir;
    private final IdGenerator idGenerator = new IdGenerator();

    private final Map<String, ImageRecord> byId = new LinkedHashMap<>();
    private final Map<Integer, String> idByMapId = new HashMap<>();
    private final Set<String> reservedIds = new HashSet<>();

    /** Serializes file writes so concurrent saves (async task + shutdown) never interleave. */
    private final Object fileLock = new Object();
    private long snapshotVersion;
    private long writtenVersion;

    public ImageStore(File dataFolder, Logger logger) {
        this.logger = logger;
        this.dataFile = new File(dataFolder, "images.yml");
        this.imagesDir = new File(dataFolder, "images");
    }

    // ------------------------------------------------------------------ loading / saving

    public synchronized void load() {
        byId.clear();
        idByMapId.clear();
        if (!imagesDir.isDirectory() && !imagesDir.mkdirs()) {
            logger.warning("Could not create " + imagesDir);
        }
        if (!dataFile.isFile()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection root = yaml.getConfigurationSection(ROOT);
        if (root == null) {
            return;
        }
        for (String id : root.getKeys(false)) {
            ConfigurationSection s = root.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            UUID owner = null;
            String ownerRaw = s.getString("owner");
            if (ownerRaw != null && !ownerRaw.isEmpty() && !ownerRaw.equalsIgnoreCase("console")) {
                try {
                    owner = UUID.fromString(ownerRaw);
                } catch (IllegalArgumentException ex) {
                    logger.warning("Image " + id + " has an invalid owner uuid '" + ownerRaw + "'");
                }
            }
            ImageRecord record = new ImageRecord(
                    id,
                    s.getInt("map-id", -1),
                    owner,
                    s.getString("owner-name", "unknown"),
                    s.getLong("created-at", 0L),
                    s.getString("source", "base64"),
                    s.getInt("original-width", 0),
                    s.getInt("original-height", 0)
            );
            if (record.mapId() < 0) {
                logger.warning("Image " + id + " has no map id, skipping");
                continue;
            }
            byId.put(id, record);
            idByMapId.put(record.mapId(), id);
        }
        logger.info("Loaded " + byId.size() + " image(s)");
    }

    /**
     * Serializes the metadata (under lock) and writes it atomically (outside the lock).
     * Safe to call from any thread.
     */
    public void save() {
        String content;
        long version;
        synchronized (this) {
            version = ++snapshotVersion;
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.options().setHeader(List.of(
                    "MCImage image index - generated file, edit only while the server is stopped.",
                    "The actual pictures live next to this file in images/<id>.png"));
            ConfigurationSection root = yaml.createSection(ROOT);
            for (ImageRecord r : byId.values()) {
                ConfigurationSection s = root.createSection(r.id());
                s.set("map-id", r.mapId());
                s.set("owner", r.owner() == null ? "console" : r.owner().toString());
                s.set("owner-name", r.ownerName());
                s.set("created-at", r.createdAt());
                s.set("source", r.source());
                s.set("original-width", r.originalWidth());
                s.set("original-height", r.originalHeight());
            }
            content = yaml.saveToString();
        }
        synchronized (fileLock) {
            if (version <= writtenVersion) {
                return; // a newer snapshot has already been written
            }
            try {
                Path target = dataFile.toPath();
                Files.createDirectories(target.getParent());
                Path tmp = target.resolveSibling(dataFile.getName() + ".tmp");
                Files.writeString(tmp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailed) {
                    Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                }
                writtenVersion = version;
            } catch (IOException ex) {
                logger.log(Level.SEVERE, "Could not save " + dataFile, ex);
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public synchronized ImageRecord get(String id) {
        return id == null ? null : byId.get(id);
    }

    public synchronized ImageRecord byMapId(int mapId) {
        String id = idByMapId.get(mapId);
        return id == null ? null : byId.get(id);
    }

    public synchronized List<ImageRecord> all() {
        return new ArrayList<>(byId.values());
    }

    public synchronized List<ImageRecord> byOwner(UUID owner) {
        if (owner == null) {
            return Collections.emptyList();
        }
        List<ImageRecord> out = new ArrayList<>();
        for (ImageRecord r : byId.values()) {
            if (r.isOwnedBy(owner)) {
                out.add(r);
            }
        }
        return out;
    }

    public synchronized int countByOwner(UUID owner) {
        if (owner == null) {
            return 0;
        }
        int n = 0;
        for (ImageRecord r : byId.values()) {
            if (r.isOwnedBy(owner)) {
                n++;
            }
        }
        return n;
    }

    public synchronized int size() {
        return byId.size();
    }

    // ------------------------------------------------------------------ mutation

    /**
     * Reserves a fresh id so an asynchronous upload can write its file before the record exists.
     *
     * @param length desired id length
     * @return the reserved id
     */
    public synchronized String reserveId(int length) {
        for (int attempt = 0; attempt < 100; attempt++) {
            String id = idGenerator.next(length);
            if (!byId.containsKey(id) && !reservedIds.contains(id) && !imageFile(id).exists()) {
                reservedIds.add(id);
                return id;
            }
        }
        // Astronomically unlikely unless the id length is tiny; fall back to a longer id.
        String id = idGenerator.next(Math.min(IdGenerator.MAX_LENGTH, length + 8));
        reservedIds.add(id);
        return id;
    }

    public synchronized void releaseId(String id) {
        reservedIds.remove(id);
    }

    public synchronized void put(ImageRecord record) {
        ImageRecord previous = byId.put(record.id(), record);
        if (previous != null) {
            idByMapId.remove(previous.mapId());
        }
        idByMapId.put(record.mapId(), record.id());
        reservedIds.remove(record.id());
    }

    public synchronized ImageRecord remove(String id) {
        ImageRecord removed = byId.remove(id);
        if (removed != null) {
            idByMapId.remove(removed.mapId());
        }
        return removed;
    }

    // ------------------------------------------------------------------ files

    public File imageFile(String id) {
        return new File(imagesDir, id + ".png");
    }

    /** Writes the PNG for {@code id}. Safe to call from any thread. */
    public void writeImage(String id, BufferedImage image) throws IOException {
        if (!imagesDir.isDirectory()) {
            Files.createDirectories(imagesDir.toPath());
        }
        File file = imageFile(id);
        if (!ImageIO.write(image, "png", file)) {
            throw new IOException("No PNG writer available");
        }
    }

    /** Reads the PNG for {@code id}. Safe to call from any thread. */
    public BufferedImage readImage(String id) throws IOException {
        File file = imageFile(id);
        if (!file.isFile()) {
            throw new IOException("Missing image file " + file.getName());
        }
        BufferedImage image = ImageIO.read(file);
        if (image == null) {
            throw new IOException("Unreadable image file " + file.getName());
        }
        return image;
    }

    public boolean deleteImageFile(String id) {
        File file = imageFile(id);
        return !file.exists() || file.delete();
    }
}
