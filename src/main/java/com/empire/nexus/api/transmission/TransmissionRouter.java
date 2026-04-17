package com.empire.nexus.api.transmission;

import com.biglybt.pif.PluginInterface;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.http.NexusServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Handles the Transmission RPC protocol at POST /transmission/rpc.
 * <p>
 * CSRF token handshake:
 * 1. Client sends request without X-Transmission-Session-Id.
 * 2. Server replies 409 with X-Transmission-Session-Id: <token>.
 * 3. Client retries with the token. Server processes normally.
 * <p>
 * Authentication: HTTP Basic Auth (same username/password as the qBittorrent API).
 * Envelope:  {"method":"torrent-get","arguments":{…},"tag":1}
 * Response:  {"result":"success","arguments":{…},"tag":1}
 */
public class TransmissionRouter implements HttpHandler {

    /**
     * CSRF token — fixed; sufficient for single-user use.
     */
    static final String SESSION_ID = "nexus-transmission-session-00000000";

    private final NexusServer server;
    private final TrSessionMethods session;
    private final TrTorrentMethods torrent;

    public TransmissionRouter(PluginInterface pi, NexusServer server) {
        this.server = server;
        this.session = new TrSessionMethods(pi);
        this.torrent = new TrTorrentMethods(pi);
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("X-Transmission-Session-Id", SESSION_ID);
            HttpUtils.sendEmpty(exchange, 204);
            return;
        }

        // Basic-Auth check (mirrors the qBittorrent side — same username/password)
        if (!server.isBypassAuth() && !checkBasicAuth(exchange)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"Transmission\"");
            exchange.getResponseHeaders().set("X-Transmission-Session-Id", SESSION_ID);
            HttpUtils.sendText(exchange, "Unauthorized", 401);
            return;
        }

        // CSRF token handshake
        String token = exchange.getRequestHeaders().getFirst("X-Transmission-Session-Id");
        if (!SESSION_ID.equals(token)) {
            exchange.getResponseHeaders().set("X-Transmission-Session-Id", SESSION_ID);
            HttpUtils.sendText(exchange,
                    "<html><h1>409: Conflict</h1><p>X-Transmission-Session-Id required.</p></html>",
                    409);
            return;
        }

        // Parse JSON-RPC envelope
        String body = HttpUtils.readBody(exchange);
        JsonObject request;
        try {
            request = JsonParser.parseString(body).getAsJsonObject();
        } catch (Exception e) {
            HttpUtils.sendJson(exchange,
                    "{\"result\":\"invalid request\",\"arguments\":{}}", 400);
            return;
        }

        // Normalize method name: some UIs send "session_get", spec says "session-get"
        String method = request.has("method") ? request.get("method").getAsString().replace('_', '-') : "";
        // Support both "arguments" (Transmission RPC spec) and "params" (JSON-RPC 2.0 style)
        JsonObject args = request.has("arguments") ? request.getAsJsonObject("arguments")
                : request.has("params") ? request.getAsJsonObject("params")
                  : new JsonObject();
        // Support both "tag" (Transmission RPC spec) and "id" (JSON-RPC 2.0 style)
        JsonElement tagEl = request.has("tag") ? request.get("tag")
                : request.has("id") ? request.get("id") : null;

        JsonObject result;
        try {
            result = dispatch(method, args);
        } catch (Exception e) {
            result = error("server error: " + e.getMessage());
        }

        // Always include session ID in response — real Transmission daemon does this on every reply
        exchange.getResponseHeaders().set("X-Transmission-Session-Id", SESSION_ID);

        if (request.has("jsonrpc")) {
            // JSON-RPC 2.0 response format (modern Transmission web UI)
            // Web UI reads: response.result (success) or response.error (failure)
            JsonObject resp = new JsonObject();
            resp.addProperty("jsonrpc", request.get("jsonrpc").getAsString());
            if (tagEl != null && !tagEl.isJsonNull()) resp.add("id", tagEl);

            String resultStr = result.has("result") ? result.get("result").getAsString() : "";
            if ("success".equals(resultStr)) {
                // Put the arguments directly as "result" with keys converted to underscores
                JsonElement data = result.has("arguments") ? result.get("arguments") : new JsonObject();
                resp.add("result", hyphenKeysToUnderscores(data));
            } else {
                JsonObject err = new JsonObject();
                err.addProperty("code", -1);
                err.addProperty("message", resultStr);
                resp.add("error", err);
            }
            HttpUtils.sendJson(exchange, resp.toString());
        } else {
            // Classic Transmission RPC format (Tremotesf, Transmission-Qt, etc.)
            if (tagEl != null && !tagEl.isJsonNull()) result.add("tag", tagEl);
            HttpUtils.sendJson(exchange, result.toString());
        }
    }

    private JsonObject dispatch(String method, JsonObject args) {
        return switch (method) {
            case "session-get" -> session.get(args);
            case "session-set" -> session.set(args);
            case "session-stats" -> session.stats();
            case "torrent-get" -> torrent.get(args);
            case "torrent-add" -> torrent.add(args);
            case "torrent-remove" -> torrent.remove(args);
            case "torrent-start",
                 "torrent-start-now" -> torrent.start(args);
            case "torrent-stop" -> torrent.stop(args);
            case "torrent-set" -> torrent.set(args);
            case "torrent-verify" -> torrent.verify(args);
            case "torrent-reannounce" -> torrent.reannounce(args);
            case "torrent-set-location" -> torrent.setLocation(args);
            case "torrent-rename-path" -> torrent.renamePath(args);
            case "free-space" -> torrent.freeSpace(args);
            case "port-test" -> {
                JsonObject a = new JsonObject();
                a.addProperty("port-is-open", true);
                yield success(a);
            }
            case "blocklist-update" -> success(new JsonObject());
            case "queue-move-top" -> torrent.queueMove(args, "top");
            case "queue-move-bottom" -> torrent.queueMove(args, "bottom");
            case "queue-move-up" -> torrent.queueMove(args, "up");
            case "queue-move-down" -> torrent.queueMove(args, "down");
            default -> error("method not found: " + method);
        };
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private boolean checkBasicAuth(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Basic ")) return false;
        try {
            String decoded = new String(Base64.getDecoder().decode(header.substring(6)),
                    StandardCharsets.UTF_8);
            int sep = decoded.indexOf(':');
            if (sep < 0) return false;
            return decoded.substring(0, sep).equals(server.getUsername())
                    && decoded.substring(sep + 1).equals(server.getPassword());
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Recursively convert JSON keys (camelCase or hyphen-case) to underscore_case for JSON-RPC 2.0 clients.
     */
    private static JsonElement hyphenKeysToUnderscores(JsonElement element) {
        if (element.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : element.getAsJsonObject().entrySet())
                out.add(camelToUnderscore(entry.getKey()), hyphenKeysToUnderscores(entry.getValue()));
            return out;
        } else if (element.isJsonArray()) {
            JsonArray out = new JsonArray();
            for (JsonElement e : element.getAsJsonArray()) out.add(hyphenKeysToUnderscores(e));
            return out;
        }
        return element;
    }

    /**
     * Convert camelCase or hyphen-case key to underscore_case: "percentDone" → "percent_done".
     */
    private static String camelToUnderscore(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '-') {
                sb.append('_');
                continue;
            }
            if (Character.isUpperCase(c)) {
                if (sb.length() > 0) sb.append('_');
                sb.append(Character.toLowerCase(c));
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static JsonObject success(JsonObject arguments) {
        JsonObject r = new JsonObject();
        r.addProperty("result", "success");
        r.add("arguments", arguments);
        return r;
    }

    static JsonObject error(String message) {
        JsonObject r = new JsonObject();
        r.addProperty("result", message != null ? message : "error");
        r.add("arguments", new JsonObject());
        return r;
    }
}
