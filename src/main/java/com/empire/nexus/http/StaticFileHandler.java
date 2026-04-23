package com.empire.nexus.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Map;

/**
 * Serves static files from a directory on disk.
 * <p>
 * Usage: point nexus.webui.path at your web UI dist/ folder, then open
 * http://localhost:8090/ — this handler serves index.html for every path that
 * doesn't match a real file (SPA history-mode routing).
 * <p>
 * Security: paths are canonicalized and checked to be inside the base dir
 * before opening, preventing path-traversal attacks.
 */
public class StaticFileHandler implements HttpHandler {

    private static final Map<String, String> MIME = Map.ofEntries(
            Map.entry("html", "text/html; charset=utf-8"),
            Map.entry("htm", "text/html; charset=utf-8"),
            Map.entry("js", "application/javascript; charset=utf-8"),
            Map.entry("mjs", "application/javascript; charset=utf-8"),
            Map.entry("css", "text/css; charset=utf-8"),
            Map.entry("json", "application/json; charset=utf-8"),
            Map.entry("svg", "image/svg+xml"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"),
            Map.entry("webp", "image/webp"),
            Map.entry("ico", "image/x-icon"),
            Map.entry("woff", "font/woff"),
            Map.entry("woff2", "font/woff2"),
            Map.entry("ttf", "font/ttf"),
            Map.entry("otf", "font/otf"),
            Map.entry("eot", "application/vnd.ms-fontobject"),
            Map.entry("txt", "text/plain; charset=utf-8"),
            Map.entry("xml", "application/xml; charset=utf-8"),
            Map.entry("map", "application/json; charset=utf-8")
    );

    private final File baseDir;
    private final File indexFile;

    public StaticFileHandler(String path) {
        this.baseDir = new File(path).getAbsoluteFile();
        this.indexFile = new File(baseDir, "index.html");
    }

    public String getResolvedBasePath() {
        try {
            return baseDir.getCanonicalPath();
        } catch (IOException e) {
            return baseDir.getAbsolutePath();
        }
    }

    public String getResolvedIndexPath() {
        try {
            return indexFile.getCanonicalPath();
        } catch (IOException e) {
            return indexFile.getAbsolutePath();
        }
    }

    public boolean isAvailable() {
        return indexFile.isFile();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendEmpty(exchange, 204);
            return;
        }

        String uriPath = exchange.getRequestURI().getPath();

        // Resolve to a real file inside baseDir
        File target = resolve(uriPath);

        // SPA fallback: any path that doesn't match a file → serve index.html
        if (target == null || !target.isFile()) {
            target = indexFile;
        }

        if (!target.isFile()) {
            HttpUtils.sendText(exchange, "Not Found", 404);
            return;
        }

        String mime = mimeFor(target.getName());
        byte[] bytes = Files.readAllBytes(target.toPath());

        exchange.getResponseHeaders().set("Content-Type", mime);
        exchange.getResponseHeaders().set("Cache-Control", "public, max-age=3600");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    /**
     * Resolve a URI path to a File inside baseDir, or null if it would escape.
     */
    private File resolve(String uriPath) {
        // Strip query and fragment
        String clean = uriPath.split("\\?")[0].split("#")[0];
        if (clean.isEmpty() || clean.equals("/")) clean = "/index.html";

        try {
            File candidate = new File(baseDir, clean).getCanonicalFile();
            // Security check: must still be inside baseDir
            if (!candidate.getPath().startsWith(baseDir.getCanonicalPath())) return null;
            return candidate;
        } catch (IOException e) {
            return null;
        }
    }

    private static String mimeFor(String filename) {
        int dot = filename.lastIndexOf('.');
        if (dot < 0) return "application/octet-stream";
        String ext = filename.substring(dot + 1).toLowerCase();
        return MIME.getOrDefault(ext, "application/octet-stream");
    }
}
