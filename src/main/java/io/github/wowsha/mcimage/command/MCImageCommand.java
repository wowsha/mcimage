package io.github.wowsha.mcimage.command;

import io.github.wowsha.mcimage.MCImagePlugin;
import io.github.wowsha.mcimage.config.PluginSettings;
import io.github.wowsha.mcimage.map.MapArtService;
import io.github.wowsha.mcimage.store.ImageRecord;
import io.github.wowsha.mcimage.store.ImageStore;
import io.github.wowsha.mcimage.text.Messages;
import io.github.wowsha.mcimage.upload.UploadSessionManager;
import io.github.wowsha.mcimage.upload.UploadSessionManager.Session;
import io.github.wowsha.mcimage.util.Base64Util;
import io.github.wowsha.mcimage.util.IdGenerator;
import io.github.wowsha.mcimage.util.ImageSniffer;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapView;

/**
 * {@code /mcimage} - see {@link #help(CommandSender)} for the full list of sub commands.
 * <p>
 * Anything that is not a known sub command is treated as base64 image data, so
 * {@code /mcimage iVBORw0KGgo...} just works.
 */
public final class MCImageCommand implements TabExecutor {

    /** Longest command the vanilla client can send (including the leading slash). */
    private static final int VANILLA_CHAT_LIMIT = 256;

    /** Anything shorter cannot be an image; treat it as a mistyped sub command instead. */
    private static final int MIN_DATA_LENGTH = 32;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault());

    private record SubCommand(String name, String permission, String usage, String description) {
    }

    private static final List<SubCommand> SUB_COMMANDS = List.of(
            new SubCommand("create", "mcimage.create", "/mcimage <base64>", "Create a map from a base64-encoded image (parts are joined automatically)"),
            new SubCommand("done", "mcimage.create", "/mcimage done", "Finish a multi-part upload"),
            new SubCommand("begin", "mcimage.create", "/mcimage begin", "Start a multi-part upload (optional)"),
            new SubCommand("cancel", "mcimage.create", "/mcimage cancel", "Cancel the upload in progress"),
            new SubCommand("status", "mcimage.create", "/mcimage status", "Show how much of the upload has arrived"),
            new SubCommand("get", "mcimage.get", "/mcimage get <id> [player]", "Get a map of an existing image"),
            new SubCommand("info", "mcimage.use", "/mcimage info", "Show the ID of the map you are holding"),
            new SubCommand("list", "mcimage.list", "/mcimage list [all|player] [page]", "List images"),
            new SubCommand("delete", "mcimage.delete", "/mcimage delete <id>", "Delete one of your images"),
            new SubCommand("url", "mcimage.url", "/mcimage url <link>", "Create a map from an image URL"),
            new SubCommand("file", "mcimage.file", "/mcimage file <name>", "Create a map from plugins/MCImage/import/<name>"),
            new SubCommand("reload", "mcimage.admin", "/mcimage reload", "Reload config.yml"),
            new SubCommand("help", "mcimage.use", "/mcimage help", "Show this help")
    );

    private final MCImagePlugin plugin;

    public MCImageCommand(MCImagePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------ dispatch

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "help", "?" -> help(sender);
            case "get" -> get(sender, args);
            case "info" -> info(sender);
            case "list" -> list(sender, args);
            case "delete", "remove", "del" -> delete(sender, args);
            case "begin", "start" -> begin(sender);
            case "done", "finish", "end" -> done(sender);
            case "cancel", "abort" -> cancel(sender);
            case "status" -> status(sender);
            case "url" -> url(sender, args);
            case "file" -> file(sender, args);
            case "reload" -> reload(sender);
            case "create", "add", "new" -> data(sender, join(args, 1));
            default -> data(sender, join(args, 0));
        }
        return true;
    }

    // ------------------------------------------------------------------ base64 upload

    private void data(CommandSender sender, String raw) {
        if (!require(sender, "mcimage.create")) {
            return;
        }
        Messages messages = plugin.messages();
        PluginSettings settings = plugin.settings();
        String normalized = Base64Util.normalize(raw);
        if (!Base64Util.isBase64Alphabet(normalized)) {
            messages.send(sender, "not-base64");
            return;
        }

        if (sender instanceof Player player) {
            UUID uuid = player.getUniqueId();
            UploadSessionManager sessions = plugin.sessions();
            boolean expired = sessions.hasExpiredSession(uuid, settings.uploadSessionTimeoutSeconds());
            Session session = sessions.get(uuid, settings.uploadSessionTimeoutSeconds());

            // Without an upload in progress, something short like "lsit" is a typo, not image data.
            if (session == null && normalized.length() < MIN_DATA_LENGTH) {
                messages.send(sender, "unknown-command", Messages.text("input", raw));
                return;
            }

            if (session != null) {
                if (session.length() + normalized.length() > settings.maxBase64Length()) {
                    sessions.end(uuid);
                    messages.send(sender, "too-long",
                            Messages.number("length", (long) session.length() + normalized.length()),
                            Messages.number("limit", settings.maxBase64Length()));
                    return;
                }
                session.append(normalized);
                messages.send(sender, "upload-part",
                        Messages.number("part", session.parts()),
                        Messages.number("chars", session.length()));
                return;
            }

            // A chat-sized paste that is not a whole image file is the first part of a multi-part upload.
            if (normalized.length() <= VANILLA_CHAT_LIMIT && !isCompleteImage(normalized)) {
                if (!canCreate(player)) {
                    return;
                }
                if (expired) {
                    messages.send(sender, "upload-expired");
                }
                Session fresh = sessions.begin(uuid);
                fresh.append(normalized);
                messages.send(sender, "upload-started", Messages.number("chars", fresh.length()));
                return;
            }
        }

        if (normalized.length() < MIN_DATA_LENGTH) {
            messages.send(sender, "unknown-command", Messages.text("input", raw));
            return;
        }
        if (normalized.length() > settings.maxBase64Length()) {
            messages.send(sender, "too-long",
                    Messages.number("length", normalized.length()),
                    Messages.number("limit", settings.maxBase64Length()));
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64Util.decode(normalized);
        } catch (IllegalArgumentException ex) {
            messages.send(sender, "not-base64");
            return;
        }
        if (!startCreation(sender)) {
            return;
        }
        plugin.creator().createFromBytes(sender, recipientOf(sender), bytes, "base64");
    }

    private void begin(CommandSender sender) {
        if (!require(sender, "mcimage.create")) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null || !canCreate(player)) {
            return;
        }
        plugin.sessions().begin(player.getUniqueId());
        plugin.messages().send(sender, "upload-begin");
    }

    private void done(CommandSender sender) {
        if (!require(sender, "mcimage.create")) {
            return;
        }
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        UploadSessionManager sessions = plugin.sessions();
        UUID uuid = player.getUniqueId();
        Session session = sessions.get(uuid, plugin.settings().uploadSessionTimeoutSeconds());
        if (session == null || session.length() == 0) {
            sessions.end(uuid);
            plugin.messages().send(sender, "upload-none");
            return;
        }
        byte[] bytes;
        try {
            bytes = Base64Util.decode(session.data());
        } catch (IllegalArgumentException ex) {
            sessions.end(uuid);
            plugin.messages().send(sender, "not-base64");
            return;
        }
        if (!startCreation(sender)) {
            return; // cooldown or limit: keep the parts so /mcimage done can be retried
        }
        sessions.end(uuid);
        plugin.creator().createFromBytes(sender, recipientOf(sender), bytes, "base64");
    }

    private void cancel(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Session removed = plugin.sessions().end(player.getUniqueId());
        plugin.messages().send(sender, removed == null ? "upload-none" : "upload-cancelled");
    }

    private void status(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        Session session = plugin.sessions().get(player.getUniqueId(), plugin.settings().uploadSessionTimeoutSeconds());
        if (session == null) {
            plugin.messages().send(sender, "upload-none");
            return;
        }
        plugin.messages().send(sender, "upload-status",
                Messages.number("part", session.parts()),
                Messages.number("chars", session.length()));
    }

    private static boolean isCompleteImage(String normalized) {
        if (!Base64Util.isDecodable(normalized)) {
            return false;
        }
        return ImageSniffer.looksComplete(Base64Util.decode(normalized));
    }

    // ------------------------------------------------------------------ url / file

    private void url(CommandSender sender, String[] args) {
        if (!require(sender, "mcimage.url")) {
            return;
        }
        if (!plugin.settings().url().enabled()) {
            plugin.messages().send(sender, "url-disabled");
            return;
        }
        if (args.length < 2) {
            usage(sender, "url");
            return;
        }
        if (!startCreation(sender)) {
            return;
        }
        plugin.creator().createFromUrl(sender, recipientOf(sender), args[1]);
    }

    private void file(CommandSender sender, String[] args) {
        if (!require(sender, "mcimage.file")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "file");
            return;
        }
        String name = args[1];
        Path importDir = plugin.importDir().toPath().toAbsolutePath().normalize();
        Path file = importDir.resolve(name).toAbsolutePath().normalize();
        if (name.contains("/") || name.contains("\\") || name.startsWith(".") || !file.startsWith(importDir)
                || !Files.isRegularFile(file)) {
            plugin.messages().send(sender, "file-not-found", Messages.text("name", name));
            return;
        }
        long size = file.toFile().length();
        if (size > plugin.settings().maxFileBytes()) {
            plugin.messages().send(sender, "error-too-large", Messages.text("detail", size + " bytes"));
            return;
        }
        if (!startCreation(sender)) {
            return;
        }
        plugin.creator().createFromFile(sender, recipientOf(sender), file);
    }

    // ------------------------------------------------------------------ get / info / list / delete

    private void get(CommandSender sender, String[] args) {
        if (!require(sender, "mcimage.get")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "get");
            return;
        }
        Messages messages = plugin.messages();
        ImageRecord record = findRecord(sender, args[1]);
        if (record == null) {
            return;
        }

        Player target;
        if (args.length >= 3) {
            if (!require(sender, "mcimage.admin")) {
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                messages.send(sender, "player-not-found", Messages.text("name", args[2]));
                return;
            }
        } else {
            target = asPlayer(sender);
            if (target == null) {
                return;
            }
        }

        MapArtService maps = plugin.maps();
        MapView view = maps.viewFor(record);
        ItemStack item = maps.createItem(record, view);
        boolean fit = maps.give(target, item);
        if (target == sender) {
            messages.send(sender, "given", Messages.id(record.id()));
        } else {
            messages.send(sender, "given-other", Messages.id(record.id()), Messages.text("player", target.getName()));
            messages.send(target, "received", Messages.id(record.id()), Messages.text("sender", sender.getName()));
        }
        if (!fit) {
            messages.send(target, "inventory-full");
        }
    }

    private void info(CommandSender sender) {
        Player player = asPlayer(sender);
        if (player == null) {
            return;
        }
        MapArtService maps = plugin.maps();
        ImageRecord record = maps.recordFromItem(player.getInventory().getItemInMainHand());
        if (record == null) {
            record = maps.recordFromItem(player.getInventory().getItemInOffHand());
        }
        if (record == null) {
            plugin.messages().send(sender, "info-none");
            return;
        }
        plugin.messages().send(sender, "info",
                Messages.id(record.id()),
                Messages.text("owner", record.ownerName()),
                Messages.number("width", record.originalWidth()),
                Messages.number("height", record.originalHeight()),
                Messages.text("date", formatDate(record.createdAt())),
                Messages.text("source", record.source()));
    }

    private void list(CommandSender sender, String[] args) {
        if (!require(sender, "mcimage.list")) {
            return;
        }
        Messages messages = plugin.messages();
        ImageStore store = plugin.store();

        int index = 1;
        String scopeArg = null;
        if (args.length > index && !isInteger(args[index])) {
            scopeArg = args[index++];
        }
        int page = 1;
        if (args.length > index) {
            page = Math.max(1, parseInt(args[index], 1));
        }

        List<ImageRecord> records;
        String scopeLabel;
        String scopeCommand;
        if (scopeArg == null) {
            if (sender instanceof Player player) {
                records = store.byOwner(player.getUniqueId());
                scopeLabel = "yours";
                scopeCommand = "";
            } else {
                records = store.all();
                scopeLabel = "all";
                scopeCommand = "all ";
            }
        } else if (scopeArg.equalsIgnoreCase("all")) {
            if (!require(sender, "mcimage.admin")) {
                return;
            }
            records = store.all();
            scopeLabel = "all";
            scopeCommand = "all ";
        } else if (scopeArg.equalsIgnoreCase("mine") && sender instanceof Player player) {
            records = store.byOwner(player.getUniqueId());
            scopeLabel = "yours";
            scopeCommand = "";
        } else {
            if (!require(sender, "mcimage.admin")) {
                return;
            }
            UUID owner = null;
            String ownerName = scopeArg;
            Player online = Bukkit.getPlayerExact(scopeArg);
            if (online != null) {
                owner = online.getUniqueId();
                ownerName = online.getName();
            } else {
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(scopeArg);
                if (cached != null) {
                    owner = cached.getUniqueId();
                    ownerName = cached.getName() != null ? cached.getName() : scopeArg;
                }
            }
            if (owner == null) {
                messages.send(sender, "player-not-found", Messages.text("name", scopeArg));
                return;
            }
            records = store.byOwner(owner);
            scopeLabel = ownerName;
            scopeCommand = ownerName + " ";
        }

        if (records.isEmpty()) {
            messages.send(sender, "list-empty");
            return;
        }
        records.sort(Comparator.comparingLong(ImageRecord::createdAt).reversed());

        int pageSize = plugin.settings().listPageSize();
        int pages = (records.size() + pageSize - 1) / pageSize;
        page = Math.min(page, pages);
        int from = (page - 1) * pageSize;
        int to = Math.min(records.size(), from + pageSize);

        messages.send(sender, "list-header",
                Messages.text("scope", scopeLabel),
                Messages.number("page", page),
                Messages.number("pages", pages),
                Messages.number("total", records.size()));
        for (ImageRecord record : records.subList(from, to)) {
            messages.send(sender, "list-entry",
                    Messages.id(record.id()),
                    Messages.text("owner", record.ownerName()),
                    Messages.text("date", formatDate(record.createdAt())),
                    Messages.text("source", record.source()));
        }
        if (page < pages) {
            messages.send(sender, "list-footer",
                    Messages.command("next-command", "/mcimage list " + scopeCommand + (page + 1)));
        }
    }

    private void delete(CommandSender sender, String[] args) {
        if (!require(sender, "mcimage.delete")) {
            return;
        }
        if (args.length < 2) {
            usage(sender, "delete");
            return;
        }
        ImageRecord record = findRecord(sender, args[1]);
        if (record == null) {
            return;
        }
        boolean owner = sender instanceof Player player && record.isOwnedBy(player.getUniqueId());
        if (!owner && !sender.hasPermission("mcimage.admin")) {
            plugin.messages().send(sender, "not-owner", Messages.id(record.id()));
            return;
        }
        plugin.maps().detach(record);
        plugin.store().remove(record.id());
        plugin.store().deleteImageFile(record.id());
        plugin.saveStoreAsync();
        plugin.messages().send(sender, "deleted", Messages.id(record.id()));
    }

    private void reload(CommandSender sender) {
        if (!require(sender, "mcimage.admin")) {
            return;
        }
        plugin.reloadSettings();
        plugin.messages().send(sender, "reloaded");
    }

    private void help(CommandSender sender) {
        Messages messages = plugin.messages();
        messages.send(sender, "help-header", Messages.text("version", plugin.getPluginMeta().getVersion()));
        for (SubCommand sub : SUB_COMMANDS) {
            if (sub.name().equals("url") && !plugin.settings().url().enabled()) {
                continue;
            }
            if (sender.hasPermission(sub.permission())) {
                messages.send(sender, "help-line",
                        Messages.text("command", sub.usage()),
                        Messages.text("description", sub.description()));
            }
        }
        messages.send(sender, "help-footer");
    }

    // ------------------------------------------------------------------ tab completion

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (SubCommand sub : SUB_COMMANDS) {
                if (sender.hasPermission(sub.permission())) {
                    out.add(sub.name());
                }
            }
            return filter(out, args[0]);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2) {
            switch (sub) {
                case "get", "delete", "remove", "del" -> {
                    if (!sender.hasPermission("mcimage.get")) {
                        return out;
                    }
                    List<ImageRecord> records = sender.hasPermission("mcimage.admin") || !(sender instanceof Player player)
                            ? plugin.store().all()
                            : plugin.store().byOwner(player.getUniqueId());
                    records.sort(Comparator.comparingLong(ImageRecord::createdAt).reversed());
                    int limit = 40;
                    for (ImageRecord record : records) {
                        out.add(record.id());
                        if (--limit == 0) {
                            break;
                        }
                    }
                }
                case "list" -> {
                    if (sender instanceof Player) {
                        out.add("mine");
                    }
                    if (sender.hasPermission("mcimage.admin")) {
                        out.add("all");
                        for (Player online : Bukkit.getOnlinePlayers()) {
                            out.add(online.getName());
                        }
                    }
                }
                case "file" -> {
                    if (sender.hasPermission("mcimage.file")) {
                        File[] files = plugin.importDir().listFiles();
                        if (files != null) {
                            for (File f : files) {
                                if (f.isFile() && !f.getName().startsWith(".")) {
                                    out.add(f.getName());
                                }
                            }
                        }
                    }
                }
                case "url" -> {
                    if (sender.hasPermission("mcimage.url")) {
                        out.add("https://");
                    }
                }
                default -> {
                    // base64 data: nothing sensible to complete
                }
            }
            return filter(out, args[1]);
        }

        if (args.length == 3 && sub.equals("get") && sender.hasPermission("mcimage.admin")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                out.add(online.getName());
            }
            return filter(out, args[2]);
        }
        return out;
    }

    // ------------------------------------------------------------------ helpers

    /** Common pre-flight checks for anything that creates an image; marks the cooldown on success. */
    private boolean startCreation(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            return true;
        }
        if (!canCreate(player)) {
            return false;
        }
        if (!player.hasPermission("mcimage.admin")) {
            plugin.markCooldown(player.getUniqueId());
        }
        return true;
    }

    /** Cooldown and per-player limit checks (admins bypass both). */
    private boolean canCreate(Player player) {
        if (player.hasPermission("mcimage.admin")) {
            return true;
        }
        long remaining = plugin.cooldownRemaining(player.getUniqueId());
        if (remaining > 0) {
            plugin.messages().send(player, "cooldown", Messages.number("seconds", remaining));
            return false;
        }
        int limit = plugin.settings().maxImagesPerPlayer();
        if (limit > 0) {
            int count = plugin.store().countByOwner(player.getUniqueId());
            if (count >= limit) {
                plugin.messages().send(player, "limit-reached", Messages.number("count", count), Messages.number("limit", limit));
                return false;
            }
        }
        return true;
    }

    private ImageRecord findRecord(CommandSender sender, String rawId) {
        String id = IdGenerator.normalize(rawId);
        if (id == null) {
            plugin.messages().send(sender, "invalid-id", Messages.text("input", rawId));
            return null;
        }
        ImageRecord record = plugin.store().get(id);
        if (record == null) {
            plugin.messages().send(sender, "unknown-image", Messages.id(id));
            return null;
        }
        return record;
    }

    private boolean require(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        plugin.messages().send(sender, "no-permission");
        return false;
    }

    private Player asPlayer(CommandSender sender) {
        if (sender instanceof Player player) {
            return player;
        }
        plugin.messages().send(sender, "players-only");
        return null;
    }

    private static UUID recipientOf(CommandSender sender) {
        return sender instanceof Player player ? player.getUniqueId() : null;
    }

    private void usage(CommandSender sender, String name) {
        for (SubCommand sub : SUB_COMMANDS) {
            if (sub.name().equals(name)) {
                plugin.messages().send(sender, "help-line",
                        Messages.text("command", sub.usage()),
                        Messages.text("description", sub.description()));
                return;
            }
        }
    }

    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            sb.append(args[i]);
        }
        return sb.toString();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String p = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(p)) {
                out.add(option);
            }
        }
        return out;
    }

    private static boolean isInteger(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private static String formatDate(long epochMillis) {
        return epochMillis <= 0 ? "unknown" : DATE.format(Instant.ofEpochMilli(epochMillis));
    }
}
