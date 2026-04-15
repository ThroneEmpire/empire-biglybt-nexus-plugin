package com.empire.nexus.http;

import com.biglybt.pif.PluginInterface;
import com.empire.nexus.api.qbt.QbtRouter;
import com.empire.nexus.api.transmission.TransmissionRouter;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

public class NexusServer {

    private final int port;
    private final boolean bypassAuth;
    private final String webuiPath;
    private final PluginInterface pi;

    /** Active SID tokens (UUID strings). */
    private final Set<String> activeSessions = Collections.newSetFromMap(new ConcurrentHashMap<>());

    // Diagnostic info shown in the fallback page
    private String diagConfiguredPath = "";
    private String diagResolvedPath   = "";
    private String diagIndexPath      = "";
    private boolean diagIndexExists   = false;

    private HttpServer server;

    public NexusServer(int port, boolean bypassAuth, String webuiPath, PluginInterface pi) {
        this.port       = port;
        this.bypassAuth = bypassAuth;
        this.webuiPath  = webuiPath == null ? "" : webuiPath.trim();
        this.pi         = pi;
    }

    public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("0.0.0.0", port), 0);
        server.setExecutor(Executors.newCachedThreadPool());

        // qBittorrent WebUI API — VueTorrent, qBittorrent-Web, etc.
        server.createContext("/api/v2", new QbtRouter(pi, this));

        // Transmission RPC — skeleton for future Flood UI / Tremotesf support
        server.createContext("/transmission/rpc", new TransmissionRouter(pi));

        // Static file serving (VueTorrent or any other SPA)
        diagConfiguredPath = webuiPath.isEmpty() ? "(empty — not configured)" : webuiPath;

        if (!webuiPath.isEmpty()) {
            StaticFileHandler staticHandler = new StaticFileHandler(webuiPath);

            diagResolvedPath = staticHandler.getResolvedBasePath();
            diagIndexPath    = staticHandler.getResolvedIndexPath();
            diagIndexExists  = staticHandler.isAvailable();

            if (diagIndexExists) {
                server.createContext("/", staticHandler);
            } else {
                server.createContext("/", this::rootFallback);
            }
        } else {
            server.createContext("/", this::rootFallback);
        }

        server.start();
    }

    public void stop() {
        if (server != null) server.stop(1);
    }

    /** Returns true when the request carries a valid session (or auth is bypassed). */
    public boolean isAuthenticated(HttpExchange exchange) {
        if (bypassAuth) return true;
        String sid = HttpUtils.sidCookie(exchange);
        return sid != null && activeSessions.contains(sid);
    }

    public Set<String> getActiveSessions() { return activeSessions; }
    public boolean isBypassAuth()          { return bypassAuth; }

    private void rootFallback(HttpExchange exchange) throws java.io.IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendEmpty(exchange, 204);
            return;
        }

        // Show a diagnostic HTML page so the user can see exactly what went wrong
        // without needing to open BiglyBT's log.
        String html = "<!DOCTYPE html><html><head><title>Nexus — Web UI not loaded</title>"
                + "<style>body{font-family:monospace;padding:2em;background:#1a1a2e;color:#eee}"
                + "h1{color:#e94560}table{border-collapse:collapse;margin-top:1em}"
                + "td{padding:.4em 1em;border:1px solid #444}"
                + ".ok{color:#4ecca3}.bad{color:#e94560}</style></head><body>"
                + "<h1>Nexus — Web UI not loaded</h1>"
                + "<p>The API is running fine at <code>http://localhost:" + port + "/api/v2/</code>.</p>"
                + "<p>VueTorrent could not be served. Diagnostic info:</p>"
                + "<table>"
                + row("nexus.webui.path (from config)", diagConfiguredPath)
                + row("Resolved base directory",         diagResolvedPath.isEmpty() ? "(n/a)" : diagResolvedPath)
                + row("Looking for index.html at",       diagIndexPath.isEmpty()    ? "(n/a)" : diagIndexPath)
                + row("index.html found?",               diagIndexExists
                        ? "<span class='ok'>YES</span>"
                        : "<span class='bad'>NO — check the path above</span>")
                + "</table>"
                + "<h2>Common fixes</h2><ul>"
                + "<li>Download a VueTorrent release ZIP from GitHub and <strong>extract</strong> it.</li>"
                + "<li>The folder you point to must contain <code>index.html</code> directly — not inside a subfolder.</li>"
                + "<li>In BiglyBT: <em>Tools → Options → Plugins → Nexus</em> → set <em>VueTorrent dist/ folder</em>.</li>"
                + "<li>Restart BiglyBT after changing the path.</li>"
                + "</ul></body></html>";

        byte[] bytes = html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, bytes.length);
        try (var out = exchange.getResponseBody()) { out.write(bytes); }
    }

    private static String row(String label, String value) {
        return "<tr><td>" + label + "</td><td>" + value + "</td></tr>";
    }
}
