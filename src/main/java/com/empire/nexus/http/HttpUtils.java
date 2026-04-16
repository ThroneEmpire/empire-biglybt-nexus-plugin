package com.empire.nexus.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Static helpers for reading requests and writing responses. */
public final class HttpUtils {

    private HttpUtils() {}

    // ── Response helpers ──────────────────────────────────────────────────────

    public static void sendJson(HttpExchange ex, String body) throws IOException {
        sendJson(ex, body, 200);
    }

    public static void sendJson(HttpExchange ex, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendText(HttpExchange ex, String body) throws IOException {
        sendText(ex, body, 200);
    }

    public static void sendText(HttpExchange ex, String body, int status) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        addCorsHeaders(ex);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (var out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    public static void sendEmpty(HttpExchange ex, int status) throws IOException {
        addCorsHeaders(ex);
        ex.sendResponseHeaders(status, -1);
        ex.getResponseBody().close();
    }

    private static void addCorsHeaders(HttpExchange ex) {
        ex.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS");
        ex.getResponseHeaders().set("Access-Control-Allow-Headers",
                "Content-Type, Accept, Cookie, X-Requested-With, X-Transmission-Session-Id, Authorization");
        ex.getResponseHeaders().set("Access-Control-Expose-Headers",
                "X-Transmission-Session-Id");
    }

    // ── Request helpers ───────────────────────────────────────────────────────

    /** Parse ?key=value&… query string. Returns empty map if none. */
    public static Map<String, String> queryParams(HttpExchange ex) {
        String raw = ex.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) return Collections.emptyMap();
        return parseKV(raw);
    }

    /** Read and parse an application/x-www-form-urlencoded request body. */
    public static Map<String, String> formParams(HttpExchange ex) throws IOException {
        String body = readBody(ex);
        if (body.isBlank()) return Collections.emptyMap();
        return parseKV(body);
    }

    /** Read the request body as a UTF-8 string. */
    public static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Extract the SID cookie value, or null if absent. */
    public static String sidCookie(HttpExchange ex) {
        String header = ex.getRequestHeaders().getFirst("Cookie");
        if (header == null) return null;
        for (String part : header.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith("SID=")) return trimmed.substring(4);
        }
        return null;
    }

    /** Last path segment: "/api/v2/torrents/info" → "info". */
    public static String pathSegment(HttpExchange ex) {
        String path = ex.getRequestURI().getPath().replaceAll("/+$", "");
        int slash = path.lastIndexOf('/');
        return slash >= 0 ? path.substring(slash + 1) : path;
    }

    private static Map<String, String> parseKV(String raw) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) continue;
            try {
                String k = URLDecoder.decode(pair.substring(0, eq), "UTF-8");
                String v = URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
                map.put(k, v);
            } catch (Exception ignored) {}
        }
        return map;
    }
}
