package com.empire.nexus.api.transmission;

import com.biglybt.pif.PluginInterface;
import com.empire.nexus.http.HttpUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;

/**
 * Stub for the Transmission RPC protocol (POST /transmission/rpc).
 *
 * Transmission clients (Flood, Tremotesf, Transmission-Qt, etc.) POST JSON-RPC
 * messages here.  The CSRF token handshake works like this:
 *
 *   1. Client sends a request without the X-Transmission-Session-Id header.
 *   2. Server replies 409 with X-Transmission-Session-Id: <token>.
 *   3. Client retries the same request with that token in the header.
 *   4. Server validates the token and processes the RPC call.
 *
 * This stub returns the 409 response so clients at least get the right error
 * rather than a random 404.  Implement dispatch() to fill in real behaviour.
 *
 * TODO:
 *   - Parse the JSON-RPC envelope: {"method":"torrent-get","arguments":{…}}
 *   - Implement torrent-get, torrent-add, torrent-start, torrent-stop,
 *     torrent-remove, session-get, session-stats
 */
public class TransmissionRouter implements HttpHandler {

    /** CSRF token — fixed for simplicity; rotate per-session if needed. */
    private static final String SESSION_ID = "nexus-transmission-session-00000000";

    private final PluginInterface pi;

    public TransmissionRouter(PluginInterface pi) {
        this.pi = pi;
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        // CORS pre-flight
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            HttpUtils.sendEmpty(exchange, 204);
            return;
        }

        // Validate CSRF token
        String token = exchange.getRequestHeaders().getFirst("X-Transmission-Session-Id");
        if (!SESSION_ID.equals(token)) {
            exchange.getResponseHeaders().set("X-Transmission-Session-Id", SESSION_ID);
            HttpUtils.sendText(exchange,
                    "<html><h1>409: Conflict</h1><p>X-Transmission-Session-Id required.</p></html>",
                    409);
            return;
        }

        // TODO: parse body, dispatch to method handlers
        HttpUtils.sendJson(exchange,
                "{\"result\":\"error\",\"arguments\":{},\"tag\":0}",
                200);
    }
}
