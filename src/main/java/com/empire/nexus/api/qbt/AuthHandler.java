package com.empire.nexus.api.qbt;

import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.http.NexusServer;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

/**
 * POST /api/v2/auth/login
 * Form params: username, password
 * Response: "Ok." + Set-Cookie: SID=<token>  |  "Fails."
 * <p>
 * POST /api/v2/auth/logout
 * Response: "Ok."
 * <p>
 * Credentials are read from the plugin settings (nexus.auth.username / nexus.auth.password).
 * When nexus.auth.bypass=true every login attempt succeeds without checking credentials.
 */
public class AuthHandler {

    private final NexusServer server;

    public AuthHandler(NexusServer server) {
        this.server = server;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "login" -> login(exchange);
            case "logout" -> logout(exchange);
            default -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    private void login(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String username = params.getOrDefault("username", "");
        String password = params.getOrDefault("password", "");

        boolean ok = server.isBypassAuth()
                || (server.getUsername().equals(username) && server.getPassword().equals(password));

        if (!ok) {
            HttpUtils.sendText(exchange, "Fails.", 401);
            return;
        }

        String sid = UUID.randomUUID().toString().replace("-", "");
        server.getActiveSessions().add(sid);

        exchange.getResponseHeaders().add(
                "Set-Cookie", "SID=" + sid + "; Path=/; HttpOnly; SameSite=Strict");
        HttpUtils.sendText(exchange, "Ok.");
    }

    private void logout(HttpExchange exchange) throws IOException {
        String sid = HttpUtils.sidCookie(exchange);
        if (sid != null) server.getActiveSessions().remove(sid);
        HttpUtils.sendText(exchange, "Ok.");
    }
}
