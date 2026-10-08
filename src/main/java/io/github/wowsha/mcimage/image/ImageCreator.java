package io.github.wowsha.mcimage.image;

import io.github.wowsha.mcimage.MCImagePlugin;
import io.github.wowsha.mcimage.config.PluginSettings;
import io.github.wowsha.mcimage.map.MapArtService;
import io.github.wowsha.mcimage.net.ImageDownloader;
import io.github.wowsha.mcimage.store.ImageRecord;
import io.github.wowsha.mcimage.store.ImageStore;
import io.github.wowsha.mcimage.text.Messages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/**
 * The upload pipeline: decode and resize off the main thread, then create the
 * map, store the record and hand out the item on the main thread.
 */
public final class ImageCreator {

    private final MCImagePlugin plugin;

    public ImageCreator(MCImagePlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Creates an image from already decoded bytes. Main thread.
     *
     * @param sender    who asked (gets the status messages)
     * @param recipient player that receives the map, or {@code null}
     * @param data      encoded image file bytes
     * @param source    label stored with the image ({@code base64}, {@code url}, {@code file})
     */
    public void createFromBytes(CommandSender sender, UUID recipient, byte[] data, String source) {
        plugin.messages().send(sender, "processing");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> processAsync(sender, recipient, data, source));
    }

    /** Downloads {@code url} and creates an image from it. Main thread. */
    public void createFromUrl(CommandSender sender, UUID recipient, String url) {
        plugin.messages().send(sender, "processing");
        PluginSettings settings = plugin.settings();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] data;
            try {
                data = plugin.downloader().download(url, settings.url());
            } catch (ImageDownloader.DownloadException ex) {
                String detail = ex.getMessage();
                onMain(() -> plugin.messages().send(sender, "error-download", Messages.text("detail", detail)));
                return;
            }
            processAsync(sender, recipient, data, "url");
        });
    }

    /** Reads {@code file} and creates an image from it. Main thread. */
    public void createFromFile(CommandSender sender, UUID recipient, Path file) {
        plugin.messages().send(sender, "processing");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            byte[] data;
            try {
                data = Files.readAllBytes(file);
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not read " + file + ": " + ex.getMessage());
                onMain(() -> plugin.messages().send(sender, "error-io"));
                return;
            }
            processAsync(sender, recipient, data, "file");
        });
    }

    // ------------------------------------------------------------------ pipeline

    /** Async thread: decode, resize, write the PNG, then hop back to the main thread. */
    private void processAsync(CommandSender sender, UUID recipient, byte[] data, String source) {
        PluginSettings settings = plugin.settings();
        ImageStore store = plugin.store();

        ImageProcessor.Result result;
        try {
            result = ImageProcessor.process(data, settings);
        } catch (ImageProcessingException ex) {
            String key = ex.messageKey();
            String detail = ex.detail();
            onMain(() -> plugin.messages().send(sender, key, Messages.text("detail", detail)));
            return;
        } catch (RuntimeException | OutOfMemoryError ex) {
            plugin.getLogger().log(Level.WARNING, "Unexpected error while processing an image", ex);
            String detail = ex.getClass().getSimpleName();
            onMain(() -> plugin.messages().send(sender, "error-corrupt", Messages.text("detail", detail)));
            return;
        }

        String id = store.reserveId(settings.idLength());
        try {
            store.writeImage(id, result.mapImage());
        } catch (IOException ex) {
            store.releaseId(id);
            plugin.getLogger().log(Level.SEVERE, "Could not write image " + id, ex);
            onMain(() -> plugin.messages().send(sender, "error-io"));
            return;
        }

        onMain(() -> finish(sender, recipient, id, result, source));
    }

    /** Main thread: create the map view, store the record and give the item. */
    private void finish(CommandSender sender, UUID recipient, String id, ImageProcessor.Result result, String source) {
        ImageStore store = plugin.store();
        MapArtService maps = plugin.maps();
        Messages messages = plugin.messages();

        if (sender instanceof Player player && !player.hasPermission("mcimage.admin")) {
            int limit = plugin.settings().maxImagesPerPlayer();
            int count = store.countByOwner(player.getUniqueId());
            if (limit > 0 && count >= limit) {
                store.releaseId(id);
                store.deleteImageFile(id);
                messages.send(sender, "limit-reached", Messages.number("count", count), Messages.number("limit", limit));
                return;
            }
        }

        ImageRecord record;
        try {
            record = maps.register(id, result.mapImage(), sender, source, result.originalWidth(), result.originalHeight());
        } catch (RuntimeException ex) {
            store.releaseId(id);
            store.deleteImageFile(id);
            plugin.getLogger().log(Level.SEVERE, "Could not create a map for image " + id, ex);
            messages.send(sender, "error-io");
            return;
        }

        messages.send(sender, "created",
                Messages.id(id),
                Messages.number("width", result.originalWidth()),
                Messages.number("height", result.originalHeight()),
                Messages.text("format", result.format()));

        Player target = recipient == null ? null : Bukkit.getPlayer(recipient);
        if (target != null && target.isOnline()) {
            MapView view = maps.viewFor(record);
            ItemStack item = maps.createItem(record, view);
            boolean fit = maps.give(target, item);
            if (target != sender) {
                messages.send(target, "received", Messages.id(id), Messages.text("sender", sender.getName()));
            }
            if (!fit) {
                messages.send(target, "inventory-full");
            }
        } else if (!(sender instanceof Player)) {
            messages.send(sender, "created-no-item", Messages.id(id));
        }
    }

    private void onMain(Runnable task) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, task);
    }
}
