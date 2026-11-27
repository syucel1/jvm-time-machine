package io.github.syucel1.tm.cli;

import io.github.syucel1.tm.core.analysis.FlameGraph;
import io.github.syucel1.tm.core.analysis.RecordingInfo;
import io.github.syucel1.tm.core.analysis.ThreadLanes;
import io.github.syucel1.tm.core.analysis.Timeline;
import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.analysis.WindowQuery;
import io.github.syucel1.tm.core.json.Json;
import io.github.syucel1.tm.core.model.JvmRecording;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.concurrent.Executors;

/**
 * Serves one recording: the timeline UI and a small JSON API (JDK built-in HTTP server, no
 * dependencies).
 *
 * <pre>
 * GET /                                   timeline UI (bundled from tm-ui), or the API index page
 * GET /api                                API index page
 * GET /api/recording                      metadata: file, start, duration, event types, threads
 * GET /api/overview[?from=&amp;to=]           per-second buckets: CPU, heap, threads, samples,
 *                                          allocation, GC pauses, lock waits
 * GET /api/window?from=2&amp;to=5[&amp;top=10]    hot methods, allocations, contention, GC, threads
 * GET /api/flamegraph?kind=cpu|alloc|lock[&amp;from=&amp;to=&amp;thread=&amp;minPercent=0.05]
 *                                          merged call tree for a flame graph
 * GET /api/threads[?from=&amp;to=&amp;limit=50]      per-thread, per-second running/blocked/waiting lanes
 * </pre>
 *
 * {@code from} and {@code to} are seconds since the recording start; both are optional.
 * The server binds to 127.0.0.1 by default and sends no CORS headers. When bound to loopback it
 * only answers requests whose {@code Host} is a loopback name, so a web page cannot reach it
 * through DNS rebinding.
 */
public final class TmServer implements AutoCloseable {

    static final int DEFAULT_TOP = 10;
    static final int MAX_TOP = 1000;
    static final int DEFAULT_THREADS = 50;

    /** Classpath folder the tm-ui build is bundled into. */
    static final String UI_ROOT = "ui";

    private static final Pattern SAFE_PATH = Pattern.compile("(/[A-Za-z0-9_][A-Za-z0-9._-]*)+");
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "[::1]");
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
            + "img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'";

    private final JvmRecording recording;
    private final Timeline timeline;
    private final RecordingInfo info;
    private final HttpServer server;
    private final boolean loopbackOnly;

    public TmServer(JvmRecording recording, String host, int port) throws IOException {
        this.recording = recording;
        this.timeline = Timeline.of(recording);
        this.info = RecordingInfo.of(recording);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
        this.loopbackOnly = server.getAddress().getAddress().isLoopbackAddress();
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
    }

    public void start() {
        server.start();
    }

    /** The bound port (useful when started with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    public String url() {
        return "http://" + server.getAddress().getHostString() + ":" + port() + "/";
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            if (!ex.getRequestMethod().equals("GET") && !ex.getRequestMethod().equals("HEAD")) {
                send(ex, 405, "application/json", error("only GET is supported"));
                return;
            }
            if (loopbackOnly && !loopbackHost(ex.getRequestHeaders().getFirst("Host"))) {
                send(ex, 403, "application/json", error("Host header must be localhost or 127.0.0.1"));
                return;
            }
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI());
            if (!path.equals("/api") && !path.startsWith("/api/")) {
                serveUi(ex, path);
                return;
            }
            switch (path) {
                case "/api", "/api/" -> send(ex, 200, "text/html; charset=utf-8", indexPage());
                case "/api/recording" -> send(ex, 200, "application/json", Json.write(info));
                case "/api/overview" -> {
                    Window w = window(q);
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("fromSeconds", w.fromOffsetSeconds(recording));
                    body.put("toSeconds", w.toOffsetSeconds(recording));
                    body.put("bucketSeconds", 1);
                    body.put("buckets", timeline.slice(w));
                    send(ex, 200, "application/json", Json.write(body));
                }
                case "/api/window" -> {
                    int top = intParam(q, "top", DEFAULT_TOP);
                    if (top < 1 || top > MAX_TOP) {
                        throw new IllegalArgumentException("top must be between 1 and " + MAX_TOP);
                    }
                    send(ex, 200, "application/json", Json.write(WindowQuery.run(recording, window(q), top)));
                }
                case "/api/flamegraph" -> {
                    FlameGraph.Kind kind = FlameGraph.Kind.parse(q.getOrDefault("kind", "cpu"));
                    Double min = doubleParam(q, "minPercent");
                    double minPercent = min == null ? FlameGraph.DEFAULT_MIN_FRACTION * 100 : min;
                    if (minPercent < 0 || minPercent >= 100) {
                        throw new IllegalArgumentException("minPercent must be at least 0 and below 100");
                    }
                    String thread = q.get("thread");
                    send(ex, 200, "application/json", Json.write(FlameGraph.build(recording, window(q), kind,
                            thread == null || thread.isBlank() ? null : thread, minPercent / 100)));
                }
                case "/api/threads" -> {
                    int limit = intParam(q, "limit", DEFAULT_THREADS);
                    if (limit < 1 || limit > MAX_TOP) {
                        throw new IllegalArgumentException("limit must be between 1 and " + MAX_TOP);
                    }
                    send(ex, 200, "application/json", Json.write(ThreadLanes.of(recording, window(q), limit)));
                }
                default -> send(ex, 404, "application/json", error("not found: " + path));
            }
        } catch (IllegalArgumentException bad) {
            send(ex, 400, "application/json", error(bad.getMessage()));
        } catch (RuntimeException e) {
            send(ex, 500, "application/json", error("internal error: " + e));
        } finally {
            ex.close();
        }
    }

    /** Serves the bundled UI; falls back to the API index page when tm-cli was built without it. */
    private void serveUi(HttpExchange ex, String path) throws IOException {
        String file = path.equals("/") ? "/index.html" : path;
        if (!SAFE_PATH.matcher(file).matches() || file.contains("..")) {
            send(ex, 404, "application/json", error("not found: " + path));
            return;
        }
        byte[] body = resource(UI_ROOT + file);
        if (body == null) {
            if (file.equals("/index.html")) {
                send(ex, 200, "text/html; charset=utf-8", indexPage());
            } else {
                send(ex, 404, "application/json", error("not found: " + path));
            }
            return;
        }
        ex.getResponseHeaders().set("Content-Security-Policy", CSP);
        ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        // Vite puts content hashes in asset file names, so they never change.
        String cache = file.startsWith("/assets/") ? "public, max-age=31536000, immutable" : "no-store";
        send(ex, 200, contentType(file), body, cache);
    }

    /** {@code true} if the tm-ui build was bundled into this tm-cli. */
    static boolean uiBundled() {
        return TmServer.class.getClassLoader().getResource(UI_ROOT + "/index.html") != null;
    }

    private static byte[] resource(String name) throws IOException {
        try (InputStream in = TmServer.class.getClassLoader().getResourceAsStream(name)) {
            return in == null ? null : in.readAllBytes();
        }
    }

    static String contentType(String file) {
        String ext = file.substring(file.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return switch (ext) {
            case "html" -> "text/html; charset=utf-8";
            case "js", "mjs" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            case "json", "map" -> "application/json";
            case "svg" -> "image/svg+xml";
            case "png" -> "image/png";
            case "ico" -> "image/x-icon";
            case "woff2" -> "font/woff2";
            case "txt" -> "text/plain; charset=utf-8";
            default -> "application/octet-stream";
        };
    }

    /** {@code true} for {@code localhost}, {@code 127.0.0.1} or {@code [::1]}, with or without a port. */
    static boolean loopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("[")) {
            int end = h.indexOf(']');
            h = end < 0 ? h : h.substring(0, end + 1);
        } else if (h.indexOf(':') >= 0) {
            h = h.substring(0, h.indexOf(':'));
        }
        return LOOPBACK_HOSTS.contains(h);
    }

    private Window window(Map<String, String> q) {
        Double from = doubleParam(q, "from");
        Double to = doubleParam(q, "to");
        if (from != null && to != null && to < from) {
            throw new IllegalArgumentException("'to' must not be before 'from'");
        }
        return Window.ofOffsets(recording, from, to);
    }

    private static Double doubleParam(Map<String, String> q, String name) {
        String v = q.get(name);
        if (v == null || v.isBlank()) {
            return null;
        }
        try {
            double d = Double.parseDouble(v);
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new NumberFormatException();
            }
            return d;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' must be a number of seconds: " + v);
        }
    }

    private static int intParam(Map<String, String> q, String name, int def) {
        String v = q.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("'" + name + "' must be an integer: " + v);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> out = new HashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return out;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String k = eq < 0 ? pair : pair.substring(0, eq);
            String v = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String error(String message) {
        return Json.write(Map.of("error", message == null ? "" : message));
    }

    private static void send(HttpExchange ex, int status, String contentType, String body) throws IOException {
        send(ex, status, contentType, body.getBytes(StandardCharsets.UTF_8), "no-store");
    }

    private static void send(HttpExchange ex, int status, String contentType, byte[] bytes, String cache)
            throws IOException {
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", cache);
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        boolean head = ex.getRequestMethod().equals("HEAD");
        ex.sendResponseHeaders(status, head ? -1 : bytes.length);
        if (!head) {
            try (OutputStream out = ex.getResponseBody()) {
                out.write(bytes);
            }
        }
    }

    private String indexPage() {
        String file = escapeHtml(info.source());
        List<String[]> links = List.of(
                new String[] {"api/recording", "Recording metadata"},
                new String[] {"api/overview", "Per-second overview"},
                new String[] {"api/window", "Whole recording: hot methods, allocations, locks, GC, threads"},
                new String[] {"api/window?from=0&to=2", "First 2 seconds"},
                new String[] {"api/flamegraph?kind=cpu", "CPU flame graph (also kind=alloc, kind=lock)"},
                new String[] {"api/threads", "Per-thread lanes: running, blocked, waiting"});
        StringBuilder li = new StringBuilder();
        for (String[] l : links) {
            li.append("<li><a href=\"/").append(l[0]).append("\"><code>/").append(escapeHtml(l[0]))
                    .append("</code></a> ").append(l[1]).append("</li>");
        }
        return """
                <!doctype html><html lang="en"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>JVM Time Machine</title>
                <style>body{font:15px/1.6 system-ui,sans-serif;max-width:720px;margin:40px auto;padding:0 16px}
                code{background:#eef1f5;padding:1px 6px;border-radius:5px}@media(prefers-color-scheme:dark){
                body{background:#0f1115;color:#e8ebf0}code{background:#1f252e}a{color:#7c9dff}}</style></head>
                <body><h1>JVM Time Machine</h1><p>Serving <b>%s</b> (%.1f s, %,d events).
                %s</p><ul>%s</ul>
                <p><code>from</code> and <code>to</code> are seconds since the recording start.</p></body></html>
                """.formatted(file, info.durationSeconds(), info.eventCount(), uiBundled()
                ? "Open the <a href=\"/\">timeline UI</a>, or use the JSON API:"
                : "This tm-cli was built without the UI (<code>-Pui.skip=true</code>); use the JSON API:", li);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
