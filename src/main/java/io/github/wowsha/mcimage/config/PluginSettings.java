package io.github.wowsha.mcimage.config;

import java.awt.Color;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/**
 * Immutable snapshot of {@code config.yml}.
 */
public record PluginSettings(
        int idLength,
        int cooldownSeconds,
        int maxImagesPerPlayer,
        int maxBase64Length,
        int maxImageDimension,
        long maxImagePixels,
        int uploadSessionTimeoutSeconds,
        ResizeMode resizeMode,
        Color background,
        boolean dithering,
        boolean preloadMapsOnStartup,
        int listPageSize,
        UrlSettings url,
        long maxFileBytes,
        UploadSettings upload
) {

    /** How a non-square image is placed on the 128x128 map. */
    public enum ResizeMode {
        /** Keep the aspect ratio and letterbox with the background. */
        FIT,
        /** Ignore the aspect ratio and fill the whole map. */
        STRETCH,
        /** Keep the aspect ratio and cut off the overflowing edges. */
        CROP
    }

    /** Settings for {@code /mcimage url}. */
    public record UrlSettings(
            boolean enabled,
            int timeoutSeconds,
            long maxBytes,
            boolean allowPrivateAddresses,
            List<String> allowedHosts,
            String userAgent
    ) {
    }

    /** Settings for temporary browser-upload links and the embedded HTTP listener. */
    public record UploadSettings(
            boolean enabled,
            String bindAddress,
            int port,
            String publicUrl,
            int tokenLifetimeSeconds,
            long maxBytes
    ) {
    }

    public static PluginSettings load(FileConfiguration config, Logger logger) {
        ResizeMode mode = ResizeMode.FIT;
        String modeName = config.getString("resize-mode", "fit");
        try {
            mode = ResizeMode.valueOf(modeName.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            logger.warning("Unknown resize-mode '" + modeName + "', using 'fit'.");
        }

        Color background = parseColor(config.getString("background", "transparent"), logger);

        ConfigurationSection urlSection = config.getConfigurationSection("url");
        UrlSettings url;
        if (urlSection == null) {
            url = new UrlSettings(true, 10, 5_000_000L, false, List.of(), "MCImage/1.0 (Paper plugin)");
        } else {
            url = new UrlSettings(
                    urlSection.getBoolean("enabled", true),
                    clamp(urlSection.getInt("timeout-seconds", 10), 1, 120),
                    Math.max(1024L, urlSection.getLong("max-bytes", 5_000_000L)),
                    urlSection.getBoolean("allow-private-addresses", false),
                    List.copyOf(urlSection.getStringList("allowed-hosts")),
                    urlSection.getString("user-agent", "MCImage/1.0 (Paper plugin)")
            );
        }

        ConfigurationSection uploadSection = config.getConfigurationSection("upload");
        UploadSettings upload;
        if (uploadSection == null) {
            upload = new UploadSettings(true, "127.0.0.1", 8765, "", 600, 5_000_000L);
        } else {
            upload = new UploadSettings(
                    uploadSection.getBoolean("enabled", true),
                    uploadSection.getString("bind-address", "127.0.0.1"),
                    clamp(uploadSection.getInt("port", 8765), 1024, 65535),
                    uploadSection.getString("public-url", ""),
                    clamp(uploadSection.getInt("token-lifetime-seconds", 600), 60, 3600),
                    Math.max(1024L, Math.min(50_000_000L, uploadSection.getLong("max-bytes", 5_000_000L)))
            );
        }

        return new PluginSettings(
                clamp(config.getInt("id-length", 6), 4, 32),
                Math.max(0, config.getInt("cooldown-seconds", 5)),
                Math.max(0, config.getInt("max-images-per-player", 100)),
                clamp(config.getInt("max-base64-length", 1_000_000), 1_000, 50_000_000),
                clamp(config.getInt("max-image-dimension", 4096), 1, 65_535),
                Math.max(1L, config.getLong("max-image-pixels", 16_777_216L)),
                clamp(config.getInt("upload-session-timeout-seconds", 600), 10, 86_400),
                mode,
                background,
                config.getBoolean("dithering", true),
                config.getBoolean("preload-maps-on-startup", true),
                clamp(config.getInt("list-page-size", 8), 1, 50),
                url,
                Math.max(1024L, config.getLong("max-file-bytes", 20_000_000L)),
                upload
        );
    }

    /**
     * Parses {@code transparent} or a hex color such as {@code #ffffff}.
     *
     * @return the color, or {@code null} for transparent
     */
    static Color parseColor(String value, Logger logger) {
        if (value == null) {
            return null;
        }
        String v = value.trim().toLowerCase(Locale.ROOT);
        if (v.isEmpty() || v.equals("transparent") || v.equals("none")) {
            return null;
        }
        if (v.startsWith("#")) {
            v = v.substring(1);
        }
        if (v.length() == 3) {
            v = "" + v.charAt(0) + v.charAt(0) + v.charAt(1) + v.charAt(1) + v.charAt(2) + v.charAt(2);
        }
        if (v.length() == 6) {
            try {
                int rgb = Integer.parseInt(v, 16);
                return new Color(rgb);
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        logger.warning("Invalid background color '" + value + "', using transparent.");
        return null;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
