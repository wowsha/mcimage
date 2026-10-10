package io.github.wowsha.mcimage.upload;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.github.wowsha.mcimage.MCImagePlugin;
import io.github.wowsha.mcimage.config.PluginSettings.UploadSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Small, dependency-free HTTP endpoint for short-lived browser upload links.
 * Put it behind an HTTPS reverse proxy for public use. Tokens are random,
 * single-use bearer credentials and are never persisted to disk.
 */
public final class UploadWebServer {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int TOKEN_BYTES = 32;
    private static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";

    private static final String UPLOAD_PAGE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width, initial-scale=1">
              <meta name="referrer" content="no-referrer">
              <title>Upload image · MCImage</title>
              <style>
                :root{color-scheme:dark;font:16px system-ui,sans-serif;background:#10131a;color:#eef2f8}
                *{box-sizing:border-box}body{margin:0;min-height:100vh;display:grid;place-items:center;padding:24px}
                main{width:min(100%,520px);background:#1b202b;border:1px solid #30394a;border-radius:18px;padding:28px}
                h1{margin:0 0 8px;font-size:1.6rem}p{color:#b9c3d3;line-height:1.5}
                .drop{display:block;margin:22px 0;padding:30px 18px;border:2px dashed #596780;border-radius:14px;text-align:center;cursor:pointer}
                .drop:hover,.drop:focus-within{border-color:#70d6c4;background:#202b35}
                input[type=file]{display:block;width:100%;margin-top:14px}
                button{width:100%;padding:13px 16px;border:0;border-radius:10px;background:#70d6c4;color:#10221f;font-weight:700;font-size:1rem;cursor:pointer}
                button:disabled{opacity:.55;cursor:wait}.note{font-size:.88rem}.status{margin-top:18px;overflow-wrap:anywhere}
                .error{color:#ff9d9d}.success{color:#8ce5b8}
              </style>
            </head>
            <body>
              <main>
                <h1>Upload an image to Minecraft</h1>
                <p>Choose an image file. MCImage will convert it to a 128 × 128 map and deliver it to you in-game.</p>
                <form id="form">
                  <label class="drop" for="file">
                    <strong>Choose an image</strong>
                    <input id="file" name="file" type="file" accept="image/*" required>
                  </label>
                  <p class="note">Maximum upload: <strong id="max">__MAX_BYTES__</strong> bytes. The link expires and can only be used once.</p>
                  <button id="submit" type="submit">Upload image</button>
                </form>
                <p id="status" class="status" role="status" aria-live="polite"></p>
              </main>
              <script>
                (function(){
                  const form=document.getElementById('form');
                  const fileInput=document.getElementById('file');
                  const button=document.getElementById('submit');
                  const status=document.getElementById('status');
                  const max=__MAX_BYTES__;
                  function show(message,kind){status.textContent=message;status.className='status '+(kind||'');}
                  form.addEventListener('submit',async function(event){
                    event.preventDefault();
                    const file=fileInput.files&&fileInput.files[0];
                    if(!file){show('Choose an image first.','error');return;}
                    if(file.size>max){show('That file is too large. Choose a smaller image.','error');return;}
                    if(file.size===0){show('The selected file is empty.','error');return;}
                    button.disabled=true;show('Uploading…');
                    try{
                      const response=await fetch(location.href,{method:'POST',headers:{'Content-Type':file.type||'application/octet-stream'},body:file,cache:'no-store',credentials:'omit'});
                      const result=await response.json();
                      if(!response.ok){show(result.message||'Upload failed. Request a new link in Minecraft.','error');button.disabled=false;return;}
                      show(result.message||'Upload accepted. Return to Minecraft to receive your map.','success');
                      fileInput.disabled=true;
                    }catch(e){show('Could not reach the server. Check your connection and request a new link in Minecraft if needed.','error');button.disabled=false;}
                  });
                })();
              </script>
            </body>
            </html>
            """;

    private final MCImagePlugin plugin;
    private final Map<String, Ticket> tickets = new ConcurrentHashMap<>();
    private volatile HttpServer server;
    private volatile ThreadPoolExecutor executor;
    private volatile String publicBaseUrl;

    private static final class Ticket {
        private final UUID playerId;
        private final long expiresAt;
        private final AtomicBoolean inFlight = new AtomicBoolean(false);
        private volatile boolean consumed;

        private Ticket(UUID playerId, long expiresAt) {
            this.playerId = playerId;
            this.expiresAt = expiresAt;
        }

        private boolean expired() {
            return System.currentTimeMillis() >= expiresAt;
        }
    }

    public UploadWebServer(MCImagePlugin plugin) {
        this.plugin = plugin;
    }

    /** Start the HTTP listener if browser upload has a public HTTPS URL configured. */
    public synchronized void start() {
        if (server != null) {
            return;
        }
        UploadSettings config = plugin.settings().upload();
        if (!config.enabled()) {
            return;
        }

        String baseUrl = normalizePublicUrl(config.publicUrl());
        if (baseUrl == null) {
            plugin.getLogger().warning("Browser upload is not available: set upload.public-url to the HTTPS URL of an HTTPS reverse proxy.");
            return;
        }

        ThreadPoolExecutor pool = null;
        HttpServer listener = null;
        try {
            InetAddress bindAddress = InetAddress.getByName(config.bindAddress());
            listener = HttpServer.create(new InetSocketAddress(bindAddress, config.port()), 32);
            ThreadFactory threadFactory = task -> {
                Thread thread = new Thread(task, "MCImage-upload-http");
                thread.setDaemon(true);
                return thread;
            };
            pool = new ThreadPoolExecutor(
                    2, 4, 30L, TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(64), threadFactory,
                    new ThreadPoolExecutor.AbortPolicy());
            listener.createContext("/", this::handle);
            listener.setExecutor(pool);
            publicBaseUrl = baseUrl;
            executor = pool;
            server = listener;
            listener.start();
            plugin.getLogger().info("Browser upload listener bound to " + config.bindAddress() + ":" + config.port()
                    + " (public URL: " + baseUrl + "). Use an HTTPS reverse proxy for public access.");
        } catch (IOException | IllegalArgumentException ex) {
            if (listener != null) {
                listener.stop(0);
            }
            if (pool != null) {
                pool.shutdownNow();
            }
            publicBaseUrl = null;
            executor = null;
            server = null;
            plugin.getLogger().log(Level.SEVERE, "Could not start the MCImage browser upload listener: " + ex.getMessage(), ex);
        }
    }

    /** Restart after config reload. */
    public synchronized void restart() {
        stop();
        start();
    }

    public synchronized void stop() {
        HttpServer current = server;
        server = null;
        if (current != null) {
            current.stop(0);
        }
        ThreadPoolExecutor pool = executor;
        executor = null;
        if (pool != null) {
            pool.shutdownNow();
        }
        tickets.clear();
        publicBaseUrl = null;
    }

    public boolean isRunning() {
        return server != null;
    }

    public int tokenLifetimeSeconds() {
        return plugin.settings().upload().tokenLifetimeSeconds();
    }

    /** Generate a new single-use URL, invalidating any previous URL for this player. */
    public synchronized String createLink(UUID playerId) {
        if (!isRunning() || publicBaseUrl == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        tickets.entrySet().removeIf(entry -> entry.getValue().expired() || entry.getValue().playerId.equals(playerId));
        if (tickets.size() >= 10_000) {
            plugin.getLogger().warning("Browser upload ticket limit reached; refusing to issue another link.");
            return null;
        }

        long expiresAt = now + tokenLifetimeSeconds() * 1000L;
        byte[] random = new byte[TOKEN_BYTES];
        String token;
        do {
            RANDOM.nextBytes(random);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        } while (tickets.putIfAbsent(token, new Ticket(playerId, expiresAt)) != null);
        return publicBaseUrl + "/" + token;
    }

    /** Invalidate any outstanding upload link for a player. */
    public void revoke(UUID playerId) {
        tickets.entrySet().removeIf(entry -> entry.getValue().playerId.equals(playerId));
    }

    private void handle(HttpExchange exchange) throws IOException {
        addSecurityHeaders(exchange);
        try {
            String token = tokenFromPath(exchange.getRequestURI().getPath());
            if (token == null) {
                sendJson(exchange, 404, "Invalid upload link.");
                return;
            }
            Ticket ticket = tickets.get(token);
            if (ticket == null || ticket.expired() || ticket.consumed) {
                if (ticket != null && ticket.expired()) {
                    tickets.remove(token, ticket);
                }
                sendJson(exchange, 410, "This upload link is invalid or has expired. Request a new one in Minecraft.");
                return;
            }

            String method = exchange.getRequestMethod();
            if ("GET".equalsIgnoreCase(method)) {
                byte[] page = UPLOAD_PAGE.replace("__MAX_BYTES__", Long.toString(plugin.settings().upload().maxBytes()))
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store, max-age=0");
                send(exchange, 200, page);
                return;
            }
            if (!"POST".equalsIgnoreCase(method)) {
                exchange.getResponseHeaders().set("Allow", "GET, POST");
                sendJson(exchange, 405, "Only GET and POST are supported.");
                return;
            }
            handlePost(exchange, token, ticket);
        } catch (IOException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            plugin.getLogger().log(Level.WARNING, "Unexpected browser upload request error", ex);
            if (exchange.getResponseCode() < 0) {
                sendJson(exchange, 500, "The upload could not be processed. Request a new link in Minecraft.");
            }
        } finally {
            exchange.close();
        }
    }

    private void handlePost(HttpExchange exchange, String token, Ticket ticket) throws IOException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin != null && !sameOrigin(origin, publicBaseUrl)) {
            sendJson(exchange, 403, "Upload origin rejected.");
            return;
        }

        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null) {
            String mediaType = contentType.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (!mediaType.startsWith("image/") && !mediaType.equals("application/octet-stream")) {
                sendJson(exchange, 415, "Choose an image file and try again.");
                return;
            }
        }

        long maxBytes = plugin.settings().upload().maxBytes();
        String contentLength = exchange.getRequestHeaders().getFirst("Content-Length");
        if (contentLength != null) {
            try {
                long length = Long.parseLong(contentLength);
                if (length < 0) {
                    sendJson(exchange, 400, "Invalid content length.");
                    return;
                }
                if (length > maxBytes) {
                    sendJson(exchange, 413, "That file is too large. Choose a smaller image.");
                    return;
                }
            } catch (NumberFormatException ex) {
                sendJson(exchange, 400, "Invalid content length.");
                return;
            }
        }

        if (!ticket.inFlight.compareAndSet(false, true)) {
            sendJson(exchange, 409, "This upload is already being processed.");
            return;
        }

        byte[] bytes;
        try {
            bytes = readLimited(exchange.getRequestBody(), maxBytes);
        } catch (UploadTooLargeException ex) {
            ticket.inFlight.set(false);
            sendJson(exchange, 413, "That file is too large. Choose a smaller image.");
            return;
        } catch (IOException ex) {
            ticket.inFlight.set(false);
            throw ex;
        }

        if (bytes.length == 0) {
            ticket.inFlight.set(false);
            sendJson(exchange, 400, "The selected file is empty.");
            return;
        }
        if (ticket.expired()) {
            tickets.remove(token, ticket);
            sendJson(exchange, 410, "This upload link has expired. Request a new one in Minecraft.");
            return;
        }

        ticket.consumed = true;
        tickets.remove(token, ticket);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!plugin.isEnabled()) {
                return;
            }
            Player player = Bukkit.getPlayer(ticket.playerId);
            if (player == null || !player.isOnline()) {
                plugin.getLogger().info("Browser upload received for an offline player; discarded.");
                return;
            }
            if (!plugin.startBrowserCreation(player)) {
                return;
            }
            plugin.creator().createFromBytes(player, player.getUniqueId(), bytes, "browser");
        });

        sendJson(exchange, 200, "Upload received. Return to Minecraft; your map will be delivered if the image is valid.");
    }

    private static byte[] readLimited(InputStream input, long maxBytes) throws IOException {
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            long total = 0L;
            int read;
            while ((read = in.read(buffer)) != -1) {
                total += read;
                if (total > maxBytes) {
                    throw new UploadTooLargeException();
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static String tokenFromPath(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        String token = path.substring(slash + 1);
        return token.matches(TOKEN_PATTERN) ? token : null;
    }

    private static boolean sameOrigin(String origin, String publicUrl) {
        try {
            URI candidate = URI.create(origin);
            URI expected = URI.create(publicUrl);
            return candidate.getHost() != null
                    && expected.getHost() != null
                    && Objects.equals(candidate.getScheme(), expected.getScheme())
                    && candidate.getHost().equalsIgnoreCase(expected.getHost())
                    && effectivePort(candidate) == effectivePort(expected);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static String normalizePublicUrl(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            String trimmed = value.trim();
            URI uri = URI.create(trimmed);
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(scheme.equals("http") && isLoopbackHost(uri.getHost()))) {
                return null;
            }
            if (uri.getRawPath() != null && uri.getRawPath().contains("..")) {
                return null;
            }
            String result = uri.toASCIIString();
            while (result.endsWith("/")) {
                result = result.substring(0, result.length() - 1);
            }
            return result;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private static boolean isLoopbackHost(String host) {
        String normalized = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        if (normalized.equals("localhost")) {
            return true;
        }
        try {
            return InetAddress.getByName(normalized).isLoopbackAddress();
        } catch (IOException ex) {
            return false;
        }
    }

    private static void addSecurityHeaders(HttpExchange exchange) {
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        exchange.getResponseHeaders().set("Cache-Control", "no-store, max-age=0");
        exchange.getResponseHeaders().set("Content-Security-Policy",
                "default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; connect-src 'self'; form-action 'self'; base-uri 'none'; frame-ancestors 'none'");
    }

    private static void sendJson(HttpExchange exchange, int status, String message) throws IOException {
        String json = "{\"message\":\"" + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        send(exchange, status, json.getBytes(StandardCharsets.UTF_8));
    }

    private static void send(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.sendResponseHeaders(status, body.length);
        try (var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static final class UploadTooLargeException extends IOException {
        private static final long serialVersionUID = 1L;
    }
}
