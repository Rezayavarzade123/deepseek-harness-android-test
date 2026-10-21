package dev.dsh.harness;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * A parsed dsh Web endpoint.
 *
 * <p>Accepts what a user can paste — a bare {@code host}, {@code host:port}, a
 * full URL, or the tokenized startup URL printed by {@code dsh web} — and
 * normalizes it into a base URL plus an optional launch token. The class keeps
 * to {@code java.net} only so the parsing rules can be exercised on a plain
 * JVM by {@code tests/ServerTargetTest.java}.</p>
 */
public final class ServerTarget {

    /** Port {@code dsh web} listens on when the user does not pass {@code --port}. */
    public static final int DEFAULT_HTTP_PORT = 3080;
    public static final int DEFAULT_HTTPS_PORT = 443;

    public final String scheme;
    public final String host;
    public final int port;
    public final String path;
    public final String token;

    private ServerTarget(String scheme, String host, int port, String path, String token) {
        this.scheme = scheme;
        this.host = host;
        this.port = port;
        this.path = path;
        this.token = token;
    }

    /**
     * Parse user input into a target.
     *
     * @param input          address field text; a URL query token is honored
     * @param explicitToken  token field text, which wins over an input token
     * @return the target, or {@code null} when the input is not a usable address
     */
    public static ServerTarget parse(String input, String explicitToken) {
        if (input == null) {
            return null;
        }
        String raw = input.trim();
        if (raw.isEmpty()) {
            return null;
        }
        int schemeSeparator = raw.indexOf("://");
        if (schemeSeparator > 0) {
            if (!hasHttpScheme(raw.substring(0, schemeSeparator))) {
                return null;
            }
        } else {
            raw = "http://" + raw;
        }
        URI uri;
        try {
            uri = new URI(escapeSpaces(raw));
        } catch (URISyntaxException error) {
            return null;
        }
        String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return null;
        }
        String host = normalizeHost(uri.getHost());
        if (host == null || host.isEmpty()) {
            host = hostFromAuthority(uri.getRawAuthority());
        }
        if (host == null || host.isEmpty()) {
            return null;
        }
        int port = uri.getPort();
        if (port <= 0) {
            port = "https".equals(scheme) ? DEFAULT_HTTPS_PORT : DEFAULT_HTTP_PORT;
        }
        if (port > 65535) {
            return null;
        }
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        if (!path.endsWith("/")) {
            path = path + "/";
        }
        String token = cleanToken(explicitToken);
        if (token == null) {
            token = tokenFromQuery(uri.getRawQuery());
        }
        return new ServerTarget(scheme, host, port, path, token);
    }

    /** Rebuild a target from a stored base URL and token. */
    public static ServerTarget stored(String baseUrl, String token) {
        return parse(baseUrl, token);
    }

    /** @return {@code true} when the target carries a launch token. */
    public boolean hasToken() {
        return token != null && !token.isEmpty();
    }

    /** @return the host decorated for a URL (IPv6 literals gain brackets). */
    public String hostForUrl() {
        return host.indexOf(':') >= 0 ? "[" + host + "]" : host;
    }

    /** @return {@code true} when the port is the scheme default and may be omitted. */
    public boolean isDefaultPort() {
        return ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == DEFAULT_HTTPS_PORT);
    }

    /** @return {@code host[:port]} as used in a URL. */
    public String authority() {
        return hostForUrl() + (isDefaultPort() ? "" : ":" + port);
    }

    /** @return the server root, without any authentication input. */
    public String baseUrl() {
        return scheme + "://" + authority() + path;
    }

    /** @return the server root carrying the launch token, or the base URL without one. */
    public String tokenUrl() {
        return hasToken() ? baseUrl() + "?token=" + percentEncode(token) : baseUrl();
    }

    /** @return a compact, human-facing label such as {@code 192.168.1.20:3080}. */
    public String display() {
        return authority() + ("/".equals(path) ? "" : path);
    }

    /** @return the same target with a different token. */
    public ServerTarget withToken(String newToken) {
        return new ServerTarget(scheme, host, port, path, cleanToken(newToken));
    }

    @Override
    public String toString() {
        return baseUrl();
    }

    private static boolean hasHttpScheme(String scheme) {
        String prefix = scheme.toLowerCase(Locale.ROOT);
        return "http".equals(prefix) || "https".equals(prefix);
    }

    private static String escapeSpaces(String value) {
        return value.replace(" ", "%20");
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String value = host.trim();
        if (value.startsWith("[") && value.endsWith("]") && value.length() > 2) {
            value = value.substring(1, value.length() - 1);
        }
        return value;
    }

    /** Fallback for authorities {@link URI#getHost()} refuses, such as underscores in host names. */
    private static String hostFromAuthority(String authority) {
        if (authority == null || authority.isEmpty()) {
            return null;
        }
        String value = authority;
        int at = value.lastIndexOf('@');
        if (at >= 0) {
            value = value.substring(at + 1);
        }
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end > 1 ? value.substring(1, end) : null;
        }
        int colon = value.lastIndexOf(':');
        if (colon >= 0 && value.indexOf(':') == colon) {
            value = value.substring(0, colon);
        }
        return value;
    }

    private static String cleanToken(String token) {
        if (token == null) {
            return null;
        }
        String value = token.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (value.startsWith("token=")) {
            value = value.substring("token=".length());
        }
        return value.isEmpty() ? null : value;
    }

    private static String tokenFromQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            if ("token".equals(pair.substring(0, equals))) {
                String value = percentDecode(pair.substring(equals + 1));
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private static String percentDecode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        int index = 0;
        while (index < value.length()) {
            char current = value.charAt(index);
            if (current == '%' && index + 2 < value.length()) {
                int high = Character.digit(value.charAt(index + 1), 16);
                int low = Character.digit(value.charAt(index + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.append((char) ((high << 4) + low));
                    index += 3;
                    continue;
                }
            }
            out.append(current);
            index++;
        }
        return out.toString();
    }

    private static String percentEncode(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (byte raw : value.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            int current = raw & 0xFF;
            boolean unreserved = (current >= 'a' && current <= 'z')
                    || (current >= 'A' && current <= 'Z')
                    || (current >= '0' && current <= '9')
                    || current == '-' || current == '.' || current == '_' || current == '~';
            if (unreserved) {
                out.append((char) current);
            } else {
                out.append('%');
                out.append(Character.toUpperCase(Character.forDigit((current >> 4) & 0xF, 16)));
                out.append(Character.toUpperCase(Character.forDigit(current & 0xF, 16)));
            }
        }
        return out.toString();
    }
}
