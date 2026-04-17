package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.http.NexusServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * Dispatches /api/v2/* requests to the correct handler.
 * <p>
 * /api/v2/auth/*        → AuthHandler
 * /api/v2/app/*         → AppHandler
 * /api/v2/log/*         → LogHandler
 * /api/v2/torrents/*    → TorrentsHandler
 * /api/v2/transfer/*    → TransferHandler
 * /api/v2/sync/*        → SyncHandler
 */
public class QbtRouter implements HttpHandler {

    private final AuthHandler auth;
    private final AppHandler app;
    private final LogHandler log;
    private final TorrentsHandler torrents;
    private final TransferHandler transfer;
    private final SyncHandler sync;
    private final NexusServer server;

    public QbtRouter(PluginInterface pi, NexusServer server) {
        this.server = server;
        this.auth = new AuthHandler(server);
        this.app = new AppHandler(pi, server);
        this.log = new LogHandler();
        this.torrents = new TorrentsHandler(pi);
        this.transfer = new TransferHandler(pi);
        this.sync = new SyncHandler(pi);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS pre-flight
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendEmpty(exchange, 204);
            return;
        }

        String path = exchange.getRequestURI().getPath();

        // Auth endpoints are always accessible (needed to log in)
        if (path.startsWith("/api/v2/auth/")) {
            auth.handle(exchange);
            return;
        }

        // Everything else requires a valid session
        if (!server.isAuthenticated(exchange)) {
            HttpUtils.sendText(exchange, "Forbidden", 403);
            return;
        }

        if (path.startsWith("/api/v2/app/")) app.handle(exchange);
        else if (path.startsWith("/api/v2/log/")) log.handle(exchange);
        else if (path.startsWith("/api/v2/torrents/")) torrents.handle(exchange);
        else if (path.startsWith("/api/v2/transfer/")) transfer.handle(exchange);
        else if (path.startsWith("/api/v2/sync/")) sync.handle(exchange);
        else HttpUtils.sendText(exchange, "Not Found", 404);
    }
}
