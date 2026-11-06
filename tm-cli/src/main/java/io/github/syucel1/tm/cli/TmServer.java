package io.github.syucel1.tm.cli;

import io.github.syucel1.tm.core.analysis.RecordingInfo;
import io.github.syucel1.tm.core.analysis.Timeline;
import io.github.syucel1.tm.core.analysis.Window;
import io.github.syucel1.tm.core.analysis.WindowQuery;
import io.github.syucel1.tm.core.json.Json;
import io.github.syucel1.tm.core.model.JvmRecording;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Serves one recording over a small JSON API (JDK built-in HTTP server, no dependencies).
 *
 * <pre>
 * GET /api/recording                      metadata: file, start, duration, event types, threads
 * GET /api/overview[?from=&amp;to=]           per-second buckets: CPU, heap, threads, samples,
 *                                          allocation, GC pauses, lock waits
 * GET /api/window?from=2&amp;to=5[&amp;top=10]    hot methods, allocations, contention, GC, threads
 * </pre>
 *
 * {@code from} and {@code to} are seconds since the recording start; both are optional.
 * The server binds to 127.0.0.1 by default and sends no CORS headers.
 */
public final class TmServer implements AutoCloseable {

    static final int DEFAULT_TOP = 10;
    static final int MAX_TOP = 1000;

    private final JvmRecording recording;
    private final Timeline timeline;
    private final RecordingInfo info;
    private final HttpServer server;

    public TmServer(JvmRecording recording, String host, int port) throws IOException {
        this.recording = recording;
        this.timeline = Timeline.of(recording);
        this.info = RecordingInfo.of(recording);
        this.server = HttpServer.create(new InetSocketAddress(host, port), 0);
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
            String path = ex.getRequestURI().getPath();
            Map<String, String> q = query(ex.getRequestURI());
            switch (path) {
                case "/", "/index.html" -> send(ex, 200, "text/html; charset=utf-8", indexPage());
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
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", contentType);
        ex.getResponseHeaders().set("Cache-Control", "no-store");
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
                new String[] {"api/window?from=0&to=2", "First 2 seconds"});
        StringBuilder li = new StringBuilder();
        for (String[] l : links) {
            li.append("<li><a href=\"").append(l[0]).append("\"><code>/").append(escapeHtml(l[0]))
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
                The timeline UI arrives in Phase 4; until then, use the JSON API:</p><ul>%s</ul>
                <p><code>from</code> and <code>to</code> are seconds since the recording start.</p></body></html>
                """.formatted(file, info.durationSeconds(), info.eventCount(), li);
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
