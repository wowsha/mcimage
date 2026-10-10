package io.github.wowsha.mcimage.net;

import io.github.wowsha.mcimage.config.PluginSettings.UrlSettings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;

/**
 * Downloads images for {@code /mcimage url} with the usual server-side safety rails:
 * http(s) only, no private/loopback/link-local targets (unless allowed), redirects are
 * re-validated hop by hop, and the body is capped at a configurable size.
 * <p>
 * Blocking - call from an async task only.
 */
public final class ImageDownloader {

    /** Thrown with a short, user-presentable reason. */
    public static final class DownloadException extends Exception {
        private static final long serialVersionUID = 1L;

        public DownloadException(String message) {
            super(message);
        }

        public DownloadException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final int MAX_REDIRECTS = 5;

    private volatile HttpClient client;
    private volatile int clientTimeout = -1;

    public byte[] download(String rawUrl, UrlSettings settings) throws DownloadException {
        URI uri = parse(rawUrl);
        HttpClient http = client(settings.timeoutSeconds());

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            validateTarget(uri, settings);

            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofSeconds(settings.timeoutSeconds()))
                    .header("User-Agent", settings.userAgent())
                    .header("Accept", "image/*,*/*;q=0.5")
                    .GET()
                    .build();

            HttpResponse<InputStream> response;
            try {
                response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException ex) {
                throw new DownloadException("connection failed (" + shortReason(ex) + ")", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new DownloadException("interrupted", ex);
            } catch (IllegalArgumentException ex) {
                throw new DownloadException("invalid URL", ex);
            }

            int status = response.statusCode();
            try (InputStream body = response.body()) {
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    Optional<String> location = response.headers().firstValue("Location");
                    if (location.isEmpty()) {
                        throw new DownloadException("redirect without location");
                    }
                    try {
                        uri = uri.resolve(location.get().trim());
                    } catch (IllegalArgumentException ex) {
                        throw new DownloadException("invalid redirect target", ex);
                    }
                    continue;
                }
                if (status != 200) {
                    throw new DownloadException("HTTP " + status);
                }

                long declared = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
                if (declared > settings.maxBytes()) {
                    throw new DownloadException("file is too large (" + declared + " bytes, limit " + settings.maxBytes() + ")");
                }

                String contentType = response.headers().firstValue("Content-Type").orElse("").toLowerCase(Locale.ROOT);
                if (contentType.startsWith("text/html")) {
                    throw new DownloadException("the URL points to a web page, not an image file");
                }

                return readCapped(body, settings.maxBytes());
            } catch (IOException ex) {
                throw new DownloadException("read failed (" + shortReason(ex) + ")", ex);
            }
        }
        throw new DownloadException("too many redirects");
    }

    private HttpClient client(int timeoutSeconds) {
        HttpClient current = client;
        if (current == null || clientTimeout != timeoutSeconds) {
            synchronized (this) {
                if (client == null || clientTimeout != timeoutSeconds) {
                    client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NEVER)
                            .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                            .build();
                    clientTimeout = timeoutSeconds;
                }
                current = client;
            }
        }
        return current;
    }

    private static URI parse(String raw) throws DownloadException {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) {
            throw new DownloadException("no URL given");
        }
        URI uri;
        try {
            uri = new URI(s);
        } catch (URISyntaxException ex) {
            throw new DownloadException("invalid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new DownloadException("only http:// and https:// URLs are allowed");
        }
        if (uri.getHost() == null || uri.getHost().isEmpty()) {
            throw new DownloadException("URL has no host");
        }
        if (uri.getUserInfo() != null) {
            throw new DownloadException("URLs with credentials are not allowed");
        }
        return uri;
    }

    private static void validateTarget(URI uri, UrlSettings settings) throws DownloadException {
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new DownloadException("redirected to a non-http URL");
        }

        if (!settings.allowedHosts().isEmpty()) {
            boolean allowed = false;
            for (String entry : settings.allowedHosts()) {
                String e = entry.toLowerCase(Locale.ROOT).trim();
                if (e.isEmpty()) {
                    continue;
                }
                if (host.equals(e) || host.endsWith("." + e)) {
                    allowed = true;
                    break;
                }
            }
            if (!allowed) {
                throw new DownloadException("host " + host + " is not on the allowed-hosts list");
            }
        }

        if (settings.allowPrivateAddresses()) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new DownloadException("unknown host " + host);
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                throw new DownloadException("host " + host + " resolves to a private or local address");
            }
        }
    }

    static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (address instanceof Inet4Address && raw.length == 4) {
            int first = raw[0] & 0xFF;
            int second = raw[1] & 0xFF;
            if (first == 0) {
                return true; // 0.0.0.0/8
            }
            if (first == 100 && second >= 64 && second <= 127) {
                return true; // 100.64.0.0/10 carrier-grade NAT
            }
            if (first == 192 && second == 0 && (raw[2] & 0xFF) == 0) {
                return true; // 192.0.0.0/24
            }
            return first == 198 && (second == 18 || second == 19); // 198.18.0.0/15 benchmarking
        }
        if (address instanceof Inet6Address && raw.length == 16) {
            return (raw[0] & 0xFE) == 0xFC; // fc00::/7 unique local
        }
        return false;
    }

    private static byte[] readCapped(InputStream in, long max) throws IOException, DownloadException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > max) {
                throw new DownloadException("file is too large (limit " + max + " bytes)");
            }
            out.write(buffer, 0, read);
        }
        if (total == 0) {
            throw new DownloadException("empty response");
        }
        return out.toByteArray();
    }

    private static String shortReason(Throwable t) {
        String msg = t.getMessage();
        if (msg == null || msg.isBlank()) {
            return t.getClass().getSimpleName();
        }
        return msg.length() > 80 ? msg.substring(0, 77) + "..." : msg;
    }
}
