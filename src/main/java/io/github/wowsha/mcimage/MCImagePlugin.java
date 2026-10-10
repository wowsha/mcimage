package io.github.wowsha.mcimage;

import io.github.wowsha.mcimage.command.MCImageCommand;
import io.github.wowsha.mcimage.config.PluginSettings;
import io.github.wowsha.mcimage.image.ImageCreator;
import io.github.wowsha.mcimage.map.MapArtService;
import io.github.wowsha.mcimage.net.ImageDownloader;
import io.github.wowsha.mcimage.store.ImageRecord;
import io.github.wowsha.mcimage.store.ImageStore;
import io.github.wowsha.mcimage.text.Messages;
import io.github.wowsha.mcimage.upload.UploadSessionManager;
import io.github.wowsha.mcimage.upload.UploadWebServer;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.command.PluginCommand;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.MapInitializeEvent;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * MCImage - turns base64-encoded images into map art items with shareable ids.
 *
 * <pre>
 *   /mcimage &lt;base64&gt;      create a map from an image
 *   /mcimage get &lt;id&gt;       get a copy of an existing image
 * </pre>
 */
public final class MCImagePlugin extends JavaPlugin implements Listener {

    private PluginSettings settings;
    private Messages messages;
    private ImageStore store;
    private MapArtService maps;
    private UploadSessionManager sessions;
    private UploadWebServer uploadServer;
    private ImageDownloader downloader;
    private ImageCreator creator;
    private File importDir;

    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final AtomicBoolean savePending = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();

        importDir = new File(getDataFolder(), "import");
        if (!importDir.isDirectory() && !importDir.mkdirs()) {
            getLogger().warning("Could not create " + importDir);
        }

        store = new ImageStore(getDataFolder(), getLogger());
        store.load();
        maps = new MapArtService(this, store);
        sessions = new UploadSessionManager();
        downloader = new ImageDownloader();
        creator = new ImageCreator(this);
        uploadServer = new UploadWebServer(this);
        uploadServer.start();

        PluginCommand command = getCommand("mcimage");
        if (command == null) {
            getLogger().severe("Command 'mcimage' is missing from plugin.yml - disabling.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        MCImageCommand executor = new MCImageCommand(this);
        command.setExecutor(executor);
        command.setTabCompleter(executor);

        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimer(this, () -> {
            sessions.cleanup(settings.uploadSessionTimeoutSeconds());
            pruneCooldowns();
        }, 20L * 60L, 20L * 60L);

        if (settings.preloadMapsOnStartup()) {
            int attached = maps.reattachAll();
            getLogger().info("Attached renderers to " + attached + " map(s)");
        }
        getLogger().info("MCImage enabled - type /mcimage help in game");
    }

    @Override
    public void onDisable() {
        if (uploadServer != null) {
            uploadServer.stop();
        }
        if (sessions != null) {
            sessions.clear();
        }
        if (store != null) {
            store.save();
        }
    }

    // ------------------------------------------------------------------ events

    /**
     * Maps are loaded lazily by the server; whenever one of ours shows up we
     * put the image renderer back on it.
     */
    @EventHandler
    public void onMapInitialize(MapInitializeEvent event) {
        ImageRecord record = store.byMapId(event.getMap().getId());
        if (record != null) {
            maps.ensureRendered(record, event.getMap());
        }
    }

    /**
     * MCImage map items are ordinary filled maps. Some item-protection plugins cancel drops
     * based on custom names or persistent data, so explicitly allow a player to drop an
     * MCImage map as they would any other map.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onImageDrop(PlayerDropItemEvent event) {
        org.bukkit.entity.Item dropped = event.getItemDrop();
        if (maps != null && maps.recordFromItem(dropped.getItemStack()) != null) {
            // Keep the drop in the world instead of letting it be picked straight back up
            // or locked to its thrower by creative-inventory/server-specific drop behavior.
            event.setCancelled(false);
            dropped.setOwner(null);
            dropped.setPickupDelay(Math.max(dropped.getPickupDelay(), 40));
            dropped.setUnlimitedLifetime(false);
            dropped.setWillAge(true);
        }
    }

    /**
     * Do not make MCImage items fireproof or otherwise special: if a dropped image item
     * is damaged by lava/fire, it should be destroyed like a normal filled map.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onImageItemDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof org.bukkit.entity.Item item)) {
            return;
        }
        if (maps != null && maps.recordFromItem(item.getItemStack()) != null
                && (event.getCause() == DamageCause.LAVA || event.getCause() == DamageCause.FIRE
                || event.getCause() == DamageCause.FIRE_TICK || event.getCause() == DamageCause.HOT_FLOOR)) {
            event.setCancelled(false);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sessions.end(event.getPlayer().getUniqueId());
        if (uploadServer != null) {
            uploadServer.revoke(event.getPlayer().getUniqueId());
        }
    }

    // ------------------------------------------------------------------ services

    public void reloadSettings() {
        reloadConfig();
        settings = PluginSettings.load(getConfig(), getLogger());
        messages = new Messages(getConfig().getConfigurationSection("messages"), getLogger());
        if (uploadServer != null) {
            uploadServer.restart();
        }
    }

    /** Debounced asynchronous save of {@code images.yml}. Safe to call from any thread. */
    public void saveStoreAsync() {
        if (!isEnabled()) {
            store.save();
            return;
        }
        if (savePending.compareAndSet(false, true)) {
            getServer().getScheduler().runTaskLaterAsynchronously(this, () -> {
                savePending.set(false);
                store.save();
            }, 20L);
        }
    }

    /**
     * @param uuid player
     * @return remaining cooldown in whole seconds, 0 if none
     */
    public long cooldownRemaining(UUID uuid) {
        Long last = cooldowns.get(uuid);
        if (last == null) {
            return 0L;
        }
        long end = last + settings.cooldownSeconds() * 1000L;
        long remaining = end - System.currentTimeMillis();
        return remaining <= 0 ? 0L : (remaining + 999L) / 1000L;
    }

    public void markCooldown(UUID uuid) {
        cooldowns.put(uuid, System.currentTimeMillis());
    }

    private void pruneCooldowns() {
        long cutoff = System.currentTimeMillis() - settings.cooldownSeconds() * 1000L;
        cooldowns.values().removeIf(last -> last < cutoff);
    }

    public PluginSettings settings() {
        return settings;
    }

    public Messages messages() {
        return messages;
    }

    public ImageStore store() {
        return store;
    }

    public MapArtService maps() {
        return maps;
    }

    public UploadSessionManager sessions() {
        return sessions;
    }

    public UploadWebServer uploadServer() {
        return uploadServer;
    }

    /**
     * Browser-upload requests arrive on an HTTP worker thread and are dispatched here on
     * the server thread. Re-check permissions, cooldown and quota before processing.
     */
    public boolean startBrowserCreation(org.bukkit.entity.Player player) {
        if (!player.hasPermission("mcimage.create")) {
            messages.send(player, "no-permission");
            return false;
        }
        if (player.hasPermission("mcimage.admin")) {
            return true;
        }
        long remaining = cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            messages.send(player, "cooldown", Messages.number("seconds", remaining));
            return false;
        }
        int limit = settings.maxImagesPerPlayer();
        if (limit > 0) {
            int count = store.countByOwner(player.getUniqueId());
            if (count >= limit) {
                messages.send(player, "limit-reached",
                        Messages.number("count", count), Messages.number("limit", limit));
                return false;
            }
        }
        markCooldown(player.getUniqueId());
        return true;
    }

    public ImageDownloader downloader() {
        return downloader;
    }

    public ImageCreator creator() {
        return creator;
    }

    public File importDir() {
        return importDir;
    }
}
