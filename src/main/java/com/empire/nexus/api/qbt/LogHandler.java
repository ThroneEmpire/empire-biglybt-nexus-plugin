package com.empire.nexus.api.qbt;

import com.empire.nexus.http.HttpUtils;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * GET /api/v2/log/main?normal=true&info=true&warning=true&critical=true&last_known_id=-1
 *
 * Some web UIs have a log tab that polls this endpoint.  We return an empty array
 * because we don't expose BiglyBT's internal log through the API.
 */
public class LogHandler {

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "main", "peers" -> HttpUtils.sendJson(exchange, "[]");
            default              -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }
}
