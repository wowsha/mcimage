package io.github.wowsha.mcimage.text;

import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.configuration.ConfigurationSection;

/**
 * MiniMessage templates, overridable from the {@code messages} section of {@code config.yml}.
 * <p>
 * Every template may use {@code <prefix>}; individual messages document their own placeholders.
 * Image ids are inserted with {@link Placeholder#parsed(String, String)} (ids only ever contain
 * {@code [a-z0-9]}) so templates can use them inside click/hover arguments as well.
 */
public final class Messages {

    private static final Map<String, String> DEFAULTS = new HashMap<>();

    static {
        DEFAULTS.put("prefix", "<dark_gray>[<gradient:#5ee7df:#b490ca>MCImage</gradient>]</dark_gray> ");
        DEFAULTS.put("no-permission", "<prefix><red>You don't have permission to do that.");
        DEFAULTS.put("players-only", "<prefix><red>Only players can do that. From the console, use <white>/mcimage get [id] [player]</white> or <white>/mcimage [base64]</white>.");
        DEFAULTS.put("player-not-found", "<prefix><red>Player <white><name></white> is not online.");
        DEFAULTS.put("unknown-image", "<prefix><red>There is no image with the ID <white><id></white>.");
        DEFAULTS.put("invalid-id", "<prefix><red><white><input></white> is not a valid image ID.");
        DEFAULTS.put("cooldown", "<prefix><yellow>Please wait <white><seconds></white>s before creating another image.");
        DEFAULTS.put("limit-reached", "<prefix><red>You already have <white><count></white> images (limit <white><limit></white>). Delete one with <white>/mcimage delete [id]</white> first.");
        DEFAULTS.put("processing", "<prefix><gray>Processing your image...");
        DEFAULTS.put("created", "<prefix><green>Created image <click:suggest_command:'/mcimage get <id>'><hover:show_text:'<gray>Click to put <white>/mcimage get <id></white> in your chat box'><white><bold>#<id></bold></white></hover></click> <dark_gray>(<gray><width>x<height> <format></gray>)</dark_gray> <click:copy_to_clipboard:'<id>'><hover:show_text:'<gray>Copy the ID to your clipboard'><dark_gray>[<aqua>copy ID</aqua>]</dark_gray></hover></click>");
        DEFAULTS.put("created-no-item", "<prefix><gray>You are not holding a player's inventory, so no map was given. Use <white>/mcimage get <id> [player]</white> to hand it out.");
        DEFAULTS.put("given", "<prefix><green>Here is your map for image <white>#<id></white>.");
        DEFAULTS.put("given-other", "<prefix><green>Gave <white><player></white> a map for image <white>#<id></white>.");
        DEFAULTS.put("received", "<prefix><green>You received a map for image <white>#<id></white> from <white><sender></white>.");
        DEFAULTS.put("inventory-full", "<prefix><yellow>Your inventory was full, the map was dropped at your feet.");
        DEFAULTS.put("unknown-command", "<prefix><red>Unknown sub command <white><input></white>. See <white>/mcimage help</white>.");
        DEFAULTS.put("not-base64", "<prefix><red>That doesn't look like base64. Paste the base64 text of a PNG/JPEG, e.g. <white>/mcimage iVBORw0KGgo...</white>");
        DEFAULTS.put("too-long", "<prefix><red>That upload is too large (<white><length></white> characters, limit <white><limit></white>).");
        DEFAULTS.put("error-empty", "<prefix><red>No image data received.");
        DEFAULTS.put("error-unsupported-format", "<prefix><red>Unsupported image format <dark_gray>(<gray><detail></gray>)</dark_gray>. Use PNG, JPEG, GIF or BMP.");
        DEFAULTS.put("error-too-large", "<prefix><red>Image is too big (<white><detail></white>). Resize it before uploading.");
        DEFAULTS.put("error-corrupt", "<prefix><red>The image could not be decoded <dark_gray>(<gray><detail></gray>)</dark_gray>. If you pasted it in parts, make sure no part is missing.");
        DEFAULTS.put("error-io", "<prefix><red>Could not store the image on the server, see console.");
        DEFAULTS.put("error-download", "<prefix><red>Download failed: <white><detail></white>");
        DEFAULTS.put("url-disabled", "<prefix><red>Creating images from URLs is disabled on this server.");
        DEFAULTS.put("file-not-found", "<prefix><red>No file named <white><name></white> in the import folder.");
        DEFAULTS.put("upload-started", "<prefix><yellow>Started a multi-part upload <dark_gray>(<gray>part 1, <chars> chars</gray>)</dark_gray>. <gray>Keep pasting the next parts with <white>/mcimage [next part]</white>, then run <white>/mcimage done</white>. <white>/mcimage cancel</white> aborts.");
        DEFAULTS.put("upload-part", "<prefix><gray>Received part <white><part></white> <dark_gray>(<gray><chars> chars total</gray>)</dark_gray>. Continue pasting or run <white>/mcimage done</white>.");
        DEFAULTS.put("upload-status", "<prefix><gray>Upload in progress: <white><part></white> part(s), <white><chars></white> chars. Finish with <white>/mcimage done</white> or <white>/mcimage cancel</white>.");
        DEFAULTS.put("upload-none", "<prefix><yellow>You have no upload in progress. Paste the first part with <white>/mcimage [base64]</white> or run <white>/mcimage begin</white>.");
        DEFAULTS.put("upload-begin", "<prefix><yellow>Ready for a multi-part upload. Paste each part with <white>/mcimage [part]</white> and finish with <white>/mcimage done</white>.");
        DEFAULTS.put("upload-cancelled", "<prefix><gray>Upload cancelled.");
        DEFAULTS.put("upload-expired", "<prefix><yellow>Your previous upload expired, starting a new one.");
        DEFAULTS.put("info-none", "<prefix><yellow>Hold a map created by MCImage to see its ID.");
        DEFAULTS.put("info", "<prefix><gray>This map shows image <click:suggest_command:'/mcimage get <id>'><white>#<id></white></click> <dark_gray>(<gray>by <owner>, <width>x<height>, <date></gray>)</dark_gray>");
        DEFAULTS.put("list-header", "<prefix><gray>Images <dark_gray>(<gray><scope></gray>)</dark_gray> - page <white><page></white>/<white><pages></white>, <white><total></white> total");
        DEFAULTS.put("list-entry", "<dark_gray> - </dark_gray><click:suggest_command:'/mcimage get <id>'><hover:show_text:'<gray>Click to get a copy'><white>#<id></white></hover></click> <gray>by <owner> <dark_gray>(<date>)</dark_gray>");
        DEFAULTS.put("list-empty", "<prefix><gray>No images yet. Create one with <white>/mcimage [base64]</white>.");
        DEFAULTS.put("list-footer", "<dark_gray>Next page: <click:run_command:'<next-command>'><hover:show_text:'Click to show the next page'><gray><next-command></gray></hover></click>");
        DEFAULTS.put("deleted", "<prefix><green>Deleted image <white>#<id></white>. Existing maps of it will now be blank.");
        DEFAULTS.put("not-owner", "<prefix><red>Image <white>#<id></white> belongs to someone else.");
        DEFAULTS.put("reloaded", "<prefix><green>Configuration reloaded.");
        DEFAULTS.put("help-header", "<prefix><gray>Commands <dark_gray>(v<version>)</dark_gray>:");
        DEFAULTS.put("help-line", "<dark_gray> - </dark_gray><white><command></white> <gray><description>");
        DEFAULTS.put("help-footer", "<dark_gray>Vanilla chat only sends 256 characters per command: paste bigger images in parts and finish with <gray>/mcimage done</gray>, or use the encoder tool shipped with the plugin.");
        DEFAULTS.put("item-name", "<!italic><white>Image <gray>#<id>");
        DEFAULTS.put("item-lore", "<!italic><dark_gray>by <owner>");
    }

    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<String, String> templates = new HashMap<>(DEFAULTS);
    private final Logger logger;

    public Messages(ConfigurationSection section, Logger logger) {
        this.logger = logger;
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String value = section.getString(key);
                if (value != null) {
                    templates.put(key, value);
                }
            }
        }
    }

    /** All known message keys with their default templates (used to write the default config). */
    public static Map<String, String> defaults() {
        return new HashMap<>(DEFAULTS);
    }

    public String raw(String key) {
        return templates.getOrDefault(key, DEFAULTS.getOrDefault(key, key));
    }

    public Component get(String key, TagResolver... resolvers) {
        String template = templates.get(key);
        if (template == null) {
            template = DEFAULTS.get(key);
        }
        if (template == null) {
            return Component.text("Missing message: " + key);
        }
        TagResolver prefix = Placeholder.parsed("prefix", templates.getOrDefault("prefix", DEFAULTS.get("prefix")));
        TagResolver all = TagResolver.resolver(TagResolver.resolver(resolvers), prefix);
        try {
            return miniMessage.deserialize(template, all);
        } catch (RuntimeException ex) {
            logger.warning("Invalid MiniMessage for '" + key + "': " + ex.getMessage());
            return Component.text(template);
        }
    }

    public void send(CommandSender to, String key, TagResolver... resolvers) {
        if (to instanceof Player player && !player.isOnline()) {
            return; // left while an upload was still processing
        }
        to.sendMessage(get(key, resolvers));
    }

    /** Placeholder for an image id (safe alphabet, usable inside tag arguments). */
    public static TagResolver id(String id) {
        return Placeholder.parsed("id", id);
    }

    /** Placeholder for arbitrary user-controlled text (names, urls, ...). */
    public static TagResolver text(String key, String value) {
        return Placeholder.unparsed(key, value == null ? "" : value);
    }

    public static TagResolver number(String key, long value) {
        return Placeholder.parsed(key, Long.toString(value));
    }

    /**
     * A server-generated string such as a command that templates may also use inside tag
     * arguments, e.g. {@code <click:run_command:'<next-command>'>}. Characters that could
     * break out of a tag or an argument are stripped.
     */
    public static TagResolver command(String key, String value) {
        return Placeholder.parsed(key, value == null ? "" : value.replaceAll("[<>'\"\\\\]", ""));
    }
}
