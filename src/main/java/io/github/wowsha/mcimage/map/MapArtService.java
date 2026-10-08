package io.github.wowsha.mcimage.map;

import io.github.wowsha.mcimage.MCImagePlugin;
import io.github.wowsha.mcimage.store.ImageRecord;
import io.github.wowsha.mcimage.store.ImageStore;
import io.github.wowsha.mcimage.text.Messages;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

/**
 * Everything that touches Bukkit map views and map items. Main-thread only
 * (except where noted).
 * <p>
 * Images never touch the world: a map view is a tiny saved-data entry in the
 * main world's {@code data} folder, the pixels come from our renderer, and
 * the view is locked so vanilla never draws terrain into it.
 */
public final class MapArtService {

    private final MCImagePlugin plugin;
    private final ImageStore store;
    private final NamespacedKey imageIdKey;

    public MapArtService(MCImagePlugin plugin, ImageStore store) {
        this.plugin = plugin;
        this.store = store;
        this.imageIdKey = new NamespacedKey(plugin, "image-id");
    }

    public NamespacedKey imageIdKey() {
        return imageIdKey;
    }

    // ------------------------------------------------------------------ creation

    /**
     * Creates the map view for a freshly processed image and stores its record.
     *
     * @param id             reserved image id (the PNG must already be written)
     * @param mapImage       processed 128x128 image
     * @param creator        who created it (console allowed)
     * @param source         upload source label
     * @param originalWidth  original width
     * @param originalHeight original height
     * @return the stored record
     */
    public ImageRecord register(String id, BufferedImage mapImage, CommandSender creator, String source,
                                int originalWidth, int originalHeight) {
        MapView view = Bukkit.createMap(mainWorld());
        ImageMapRenderer renderer = attachRenderer(view, id);
        renderer.setImage(mapImage);

        UUID owner = creator instanceof Player player ? player.getUniqueId() : null;
        String ownerName = creator instanceof Player player ? player.getName() : "console";
        ImageRecord record = new ImageRecord(id, view.getId(), owner, ownerName, System.currentTimeMillis(),
                source, originalWidth, originalHeight);
        store.put(record);
        plugin.saveStoreAsync();
        return record;
    }

    // ------------------------------------------------------------------ lookup / attach

    /**
     * Returns the view for an image, recreating it if the world lost the map data.
     *
     * @param record image record
     * @return a view with our renderer attached
     */
    public MapView viewFor(ImageRecord record) {
        MapView view = Bukkit.getMap(record.mapId());
        ImageRecord current = record;
        if (view == null) {
            view = Bukkit.createMap(mainWorld());
            current = record.withMapId(view.getId());
            store.put(current);
            plugin.saveStoreAsync();
            plugin.getLogger().warning("Map #" + record.mapId() + " for image " + record.id()
                    + " no longer exists, recreated it as map #" + view.getId());
        }
        ensureRendered(current, view);
        return view;
    }

    /**
     * Makes sure {@code view} shows {@code record}'s image. Idempotent.
     */
    public void ensureRendered(ImageRecord record, MapView view) {
        for (MapRenderer renderer : view.getRenderers()) {
            if (renderer instanceof ImageMapRenderer ours && ours.imageId().equals(record.id())) {
                return;
            }
        }
        ImageMapRenderer renderer = attachRenderer(view, record.id());
        loadImageAsync(record.id(), renderer);
    }

    /**
     * Re-attaches renderers for every known image whose map data can be loaded.
     *
     * @return number of maps that were attached
     */
    public int reattachAll() {
        int attached = 0;
        for (ImageRecord record : store.all()) {
            MapView view = Bukkit.getMap(record.mapId());
            if (view != null) {
                ensureRendered(record, view);
                attached++;
            }
        }
        return attached;
    }

    /** Removes our renderer so maps of a deleted image go blank. */
    public void detach(ImageRecord record) {
        MapView view = Bukkit.getMap(record.mapId());
        if (view == null) {
            return;
        }
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            if (renderer instanceof ImageMapRenderer) {
                view.removeRenderer(renderer);
            }
        }
    }

    private ImageMapRenderer attachRenderer(MapView view, String imageId) {
        view.setScale(MapView.Scale.CLOSEST);
        view.setTrackingPosition(false);
        view.setUnlimitedTracking(false);
        view.setLocked(true);
        for (MapRenderer renderer : new ArrayList<>(view.getRenderers())) {
            view.removeRenderer(renderer);
        }
        ImageMapRenderer renderer = new ImageMapRenderer(imageId, plugin.settings().dithering());
        view.addRenderer(renderer);
        return renderer;
    }

    private void loadImageAsync(String imageId, ImageMapRenderer renderer) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                renderer.setImage(store.readImage(imageId));
            } catch (IOException ex) {
                plugin.getLogger().log(Level.WARNING, "Could not load image " + imageId + ": " + ex.getMessage());
            }
        });
    }

    // ------------------------------------------------------------------ items

    public ItemStack createItem(ImageRecord record, MapView view) {
        ItemStack item = ItemStack.of(Material.FILLED_MAP);
        Messages messages = plugin.messages();
        Component name = messages.get("item-name", Messages.id(record.id()), Messages.text("owner", record.ownerName()));
        Component lore = messages.get("item-lore", Messages.id(record.id()), Messages.text("owner", record.ownerName()));
        item.editMeta(MapMeta.class, meta -> {
            meta.setMapView(view);
            meta.setScaling(false);
            meta.displayName(name);
            meta.lore(List.of(lore));
            meta.getPersistentDataContainer().set(imageIdKey, PersistentDataType.STRING, record.id());
        });
        return item;
    }

    /**
     * Gives a map item to a player, dropping it at their feet if the inventory is full.
     *
     * @return true if it fit into the inventory
     */
    public boolean give(Player player, ItemStack item) {
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (leftover.isEmpty()) {
            return true;
        }
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }
        return false;
    }

    /**
     * Finds the image an item belongs to, via our item tag first and the map id second.
     *
     * @param item any item, may be null
     * @return the record or {@code null}
     */
    public ImageRecord recordFromItem(ItemStack item) {
        if (item == null || item.getType() != Material.FILLED_MAP || !item.hasItemMeta()) {
            return null;
        }
        if (!(item.getItemMeta() instanceof MapMeta meta)) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String tagged = pdc.get(imageIdKey, PersistentDataType.STRING);
        if (tagged != null) {
            ImageRecord record = store.get(tagged);
            if (record != null) {
                return record;
            }
        }
        if (meta.hasMapView()) {
            MapView view = meta.getMapView();
            if (view != null) {
                return store.byMapId(view.getId());
            }
        }
        return null;
    }

    private static World mainWorld() {
        List<World> worlds = Bukkit.getWorlds();
        if (worlds.isEmpty()) {
            throw new IllegalStateException("No worlds are loaded");
        }
        return worlds.get(0);
    }
}
