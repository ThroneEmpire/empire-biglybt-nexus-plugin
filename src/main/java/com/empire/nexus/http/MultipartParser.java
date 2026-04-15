package com.empire.nexus.http;

import com.sun.net.httpserver.HttpExchange;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal multipart/form-data parser.
 *
 * Handles the subset used by VueTorrent's torrent upload:
 *   POST /api/v2/torrents/add
 *   Content-Type: multipart/form-data; boundary=<token>
 *
 * Each Part has a name, an optional filename, and raw bytes.
 * Text fields are parts with no filename; file uploads have a filename.
 */
public final class MultipartParser {

    private MultipartParser() {}

    public static class Part {
        public final String name;
        public final String filename;   // null for plain text fields
        public final byte[] data;

        Part(String name, String filename, byte[] data) {
            this.name     = name;
            this.filename = filename;
            this.data     = data;
        }

        /** Return data as UTF-8 text (for non-file fields). */
        public String text() {
            return new String(data, StandardCharsets.UTF_8);
        }

        public boolean isFile() {
            return filename != null;
        }
    }

    /**
     * Parse a multipart/form-data request.
     * Returns an empty list if the Content-Type is not multipart/form-data.
     */
    public static List<Part> parse(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.startsWith("multipart/form-data")) {
            return List.of();
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null) return List.of();

        byte[] body;
        try (InputStream in = exchange.getRequestBody()) {
            body = in.readAllBytes();
        }

        return splitParts(body, boundary);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static String extractBoundary(String contentType) {
        for (String token : contentType.split(";")) {
            String t = token.trim();
            if (t.startsWith("boundary=")) {
                return t.substring("boundary=".length()).trim().replace("\"", "");
            }
        }
        return null;
    }

    private static List<Part> splitParts(byte[] body, String boundary) {
        List<Part> parts = new ArrayList<>();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.ISO_8859_1);
        byte[] crlf      = "\r\n".getBytes(StandardCharsets.ISO_8859_1);

        int pos = indexOf(body, delimiter, 0);
        if (pos < 0) return parts;

        while (true) {
            // Skip past the boundary line (delimiter + CRLF)
            pos += delimiter.length;
            if (pos + 2 > body.length) break;
            // "--" suffix = final boundary
            if (body[pos] == '-' && body[pos + 1] == '-') break;
            // skip CRLF after boundary
            if (body[pos] == '\r') pos += 2; else pos += 1;

            // Read headers until blank line (\r\n\r\n)
            byte[] headerEnd = "\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1);
            int headersEnd = indexOf(body, headerEnd, pos);
            if (headersEnd < 0) break;

            String headers = new String(body, pos, headersEnd - pos, StandardCharsets.ISO_8859_1);
            pos = headersEnd + headerEnd.length;

            // Find next boundary
            int nextBoundary = indexOf(body, delimiter, pos);
            if (nextBoundary < 0) break;

            // Data ends just before CRLF before next boundary
            int dataEnd = nextBoundary;
            if (dataEnd >= 2 && body[dataEnd - 2] == '\r' && body[dataEnd - 1] == '\n') {
                dataEnd -= 2;
            }

            byte[] data = new byte[dataEnd - pos];
            System.arraycopy(body, pos, data, 0, data.length);

            Part part = parsePart(headers, data);
            if (part != null) parts.add(part);

            pos = nextBoundary;
        }

        return parts;
    }

    private static Part parsePart(String headers, byte[] data) {
        String name     = null;
        String filename = null;

        for (String line : headers.split("\r\n")) {
            String lower = line.toLowerCase();
            if (lower.startsWith("content-disposition:")) {
                name     = headerParam(line, "name");
                filename = headerParam(line, "filename");
            }
        }

        if (name == null) return null;
        return new Part(name, filename, data);
    }

    /** Extract a parameter value from a header like: form-data; name="foo"; filename="bar.torrent" */
    private static String headerParam(String header, String param) {
        String lower  = header.toLowerCase();
        String search = param.toLowerCase() + "=";
        int idx = lower.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();
        if (idx >= header.length()) return null;
        if (header.charAt(idx) == '"') {
            int end = header.indexOf('"', idx + 1);
            return end < 0 ? null : header.substring(idx + 1, end);
        }
        // unquoted
        int end = header.indexOf(';', idx);
        return (end < 0 ? header.substring(idx) : header.substring(idx, end)).trim();
    }

    /** Returns the first index of needle in haystack starting at fromIndex, or -1. */
    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
