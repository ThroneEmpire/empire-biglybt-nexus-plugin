package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.torrent.Torrent;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.http.MultipartParser;
import com.empire.nexus.util.TorrentMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.Map;

/**
 * /api/v2/torrents/* endpoints.
 *
 * GET  info              — list all torrents
 * GET  properties        — single torrent details
 * GET  trackers          — tracker list
 * GET  files             — file list
 * GET  peers             — peer list (stub)
 * GET  pieceStates       — piece states (stub)
 * GET  pieceHashes       — piece hashes (stub)
 * GET  categories        — category map
 * GET  tags              — tag list
 * GET  count             — torrent count
 * POST add               — add torrent (.torrent file or URL/magnet)
 * POST pause             — pause torrent(s)
 * POST resume            — resume torrent(s)
 * POST delete            — delete torrent(s)
 * POST recheck           — force data recheck
 * POST reannounce        — reannounce to trackers
 * POST setCategory       — change category
 * POST createCategory    — create new category
 * POST editCategory      — edit category
 * POST removeCategories  — remove categories
 * POST addTags           — add tags
 * POST removeTags        — remove tags
 * POST createTags        — create tags
 * POST deleteTags        — delete tags
 * POST + others          — accepted, no-op for now
 */
public class TorrentsHandler {

    private final PluginInterface pi;

    public TorrentsHandler(PluginInterface pi) {
        this.pi = pi;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "info"        -> torrentList(exchange);
            case "properties"  -> properties(exchange);
            case "trackers"    -> trackers(exchange);
            case "files"       -> files(exchange);
            case "peers"       -> peers(exchange);
            case "pieceStates" -> HttpUtils.sendJson(exchange, "[]");
            case "pieceHashes" -> HttpUtils.sendJson(exchange, "[]");
            case "categories"  -> categories(exchange);
            case "tags"        -> tags(exchange);
            case "count"       -> count(exchange);

            case "add"         -> add(exchange);
            case "pause"       -> pause(exchange);
            case "resume"      -> resume(exchange);
            case "delete"      -> delete(exchange);
            case "recheck"     -> recheck(exchange);
            case "reannounce"  -> reannounce(exchange);

            // Category management — no-op stubs
            case "setCategory",
                 "createCategory",
                 "editCategory",
                 "removeCategories"
                               -> HttpUtils.sendText(exchange, "Ok.");

            // Tag management — no-op stubs
            case "addTags",
                 "removeTags",
                 "createTags",
                 "deleteTags"  -> HttpUtils.sendText(exchange, "Ok.");

            // Download-limit / upload-limit / share-limit / rename / move — accepted
            case "setAutoManagement",
                 "toggleSequentialDownload",
                 "toggleFirstLastPiecePrio",
                 "setForceStart",
                 "setSuperSeeding",
                 "setLocation",
                 "rename",
                 "renameFile",
                 "renameFolder",
                 "setDownloadLimit",
                 "setUploadLimit",
                 "setShareLimits",
                 "setDownloadPath",
                 "setSavePath",
                 "addTrackers",
                 "editTracker",
                 "removeTrackers",
                 "topPrio",
                 "bottomPrio",
                 "increasePrio",
                 "decreasePrio",
                 "filePrio",
                 "exportTorrent"
                               -> HttpUtils.sendText(exchange, "Ok.");

            default            -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    // ── GET /api/v2/torrents/info ─────────────────────────────────────────────

    private void torrentList(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.queryParams(exchange);
        String filter   = params.getOrDefault("filter",   "all");
        String hashes   = params.getOrDefault("hashes",   "");
        int    limit    = parseInt(params.getOrDefault("limit",  "0"),  0);
        int    offset   = parseInt(params.getOrDefault("offset", "0"),  0);

        Download[] downloads = pi.getDownloadManager().getDownloads();
        JsonArray arr = new JsonArray();

        int skipped = 0;
        for (Download dl : downloads) {
            if (dl.getTorrent() == null) continue;
            if (!matchesFilter(dl, filter, hashes)) continue;
            if (skipped++ < offset) continue;
            arr.add(TorrentMapper.toQbtInfo(dl));
            if (limit > 0 && arr.size() >= limit) break;
        }

        HttpUtils.sendJson(exchange, arr.toString());
    }

    private boolean matchesFilter(Download dl, String filter, String hashes) {
        if (!hashes.isEmpty()) {
            String dlHash = TorrentMapper.hashHex(dl.getTorrent().getHash());
            boolean found = false;
            for (String h : hashes.split("\\|")) {
                if (h.trim().equalsIgnoreCase(dlHash)) { found = true; break; }
            }
            if (!found) return false;
        }
        return switch (filter) {
            case "downloading"  -> dl.getState() == Download.ST_DOWNLOADING && !dl.isPaused();
            case "seeding"      -> dl.getState() == Download.ST_SEEDING     && !dl.isPaused();
            case "completed"    -> dl.getStats().getCompleted() >= 1000;
            case "paused"       -> dl.isPaused();
            case "stopped"      -> dl.getState() == Download.ST_STOPPED;
            case "active"       -> dl.getStats().getDownloadAverage() > 0
                                 || dl.getStats().getUploadAverage()  > 0;
            case "inactive"     -> dl.getStats().getDownloadAverage() == 0
                                 && dl.getStats().getUploadAverage()  == 0;
            case "stalled"      -> dl.getState() == Download.ST_STOPPED;
            case "stalled_downloading" -> dl.getState() == Download.ST_STOPPED && !dl.isPaused()
                                 && dl.getStats().getCompleted() < 1000;
            case "stalled_uploading" -> dl.getState() == Download.ST_SEEDING
                                 && dl.getStats().getUploadAverage() == 0;
            case "checking"     -> dl.getState() == Download.ST_PREPARING;
            case "moving"       -> false;
            case "errored"      -> dl.getState() == Download.ST_ERROR;
            default             -> true; // "all"
        };
    }

    // ── GET /api/v2/torrents/properties ──────────────────────────────────────

    private void properties(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }

        Torrent t     = dl.getTorrent();
        var     stats = dl.getStats();
        String  hash  = TorrentMapper.hashHex(t.getHash());

        String comment = "";
        try { comment = t.getComment(); } catch (Exception ignored) {}
        String createdBy = "";
        try { createdBy = t.getCreatedBy(); } catch (Exception ignored) {}
        long creationDate = 0;
        try { creationDate = t.getCreationDate(); } catch (Exception ignored) {}
        boolean isPrivate = false;
        try { isPrivate = t.isPrivate(); } catch (Exception ignored) {}

        long dlTotal = stats.getDownloaded(false);
        long ulTotal = stats.getUploaded(false);
        double ratio = (dlTotal > 0) ? (double) ulTotal / dlTotal : 0.0;

        long timeStarted = 0;
        try { timeStarted = stats.getTimeStarted(); } catch (Exception ignored) {}

        int seedsConnected = 0, peersConnected = 0;
        try {
            com.biglybt.pif.peers.PeerManager pm = dl.getPeerManager();
            if (pm != null) {
                seedsConnected = pm.getStats().getConnectedSeeds();
                peersConnected = pm.getStats().getConnectedLeechers();
            }
        } catch (Exception ignored) {}

        JsonObject o = new JsonObject();
        o.addProperty("hash",             hash);
        o.addProperty("infohash_v1",      hash);
        o.addProperty("infohash_v2",      "");
        o.addProperty("name",             t.getName());
        o.addProperty("total_size",       t.getSize());
        o.addProperty("comment",          comment);
        o.addProperty("created_by",       createdBy);
        o.addProperty("creation_date",    creationDate);
        o.addProperty("private",          isPrivate);
        o.addProperty("total_wasted",     0L);
        o.addProperty("total_uploaded",   ulTotal);
        o.addProperty("total_downloaded", dlTotal);
        o.addProperty("uploaded_session", 0L);
        o.addProperty("downloaded_session", 0L);
        o.addProperty("share_ratio",      ratio);
        o.addProperty("addition_date",    timeStarted > 0 ? timeStarted / 1000L : 0L);
        o.addProperty("completion_date",  stats.getCompleted() >= 1000 ? System.currentTimeMillis() / 1000L : 0L);
        o.addProperty("seen_complete",    0L);
        o.addProperty("reannounce",       0L);
        o.addProperty("has_metadata",     true);
        o.addProperty("popularity",       0.0);
        o.addProperty("pieces_num",       0);
        o.addProperty("pieces_have",      0);
        o.addProperty("piece_size",       0L);
        o.addProperty("nb_connections",   peersConnected);
        o.addProperty("nb_connections_limit", -1);
        o.addProperty("seeds",            seedsConnected);
        o.addProperty("seeds_total",      -1);
        o.addProperty("peers",            peersConnected);
        o.addProperty("peers_total",      -1);
        o.addProperty("dl_speed",         stats.getDownloadAverage());
        o.addProperty("dl_speed_avg",     stats.getDownloadAverage());
        o.addProperty("up_speed",         stats.getUploadAverage());
        o.addProperty("up_speed_avg",     stats.getUploadAverage());
        o.addProperty("dl_limit",         -1);
        o.addProperty("up_limit",         -1);
        o.addProperty("eta",              stats.getETASecs() < 0 ? -1L : stats.getETASecs());
        o.addProperty("seeding_time",     0L);
        o.addProperty("time_elapsed",     0L);
        o.addProperty("save_path",        "");
        o.addProperty("download_path",    "");

        HttpUtils.sendJson(exchange, o.toString());
    }

    // ── GET /api/v2/torrents/trackers ─────────────────────────────────────────

    private void trackers(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }

        JsonArray arr = new JsonArray();

        // qBittorrent always includes these two synthetic "trackers"
        addSyntheticTracker(arr, "** [DHT] **",    -1);
        addSyntheticTracker(arr, "** [PeX] **",    -1);
        addSyntheticTracker(arr, "** [LSD] **",    -1);

        try {
            com.biglybt.pif.torrent.TorrentAnnounceURLListSet[] sets =
                    dl.getTorrent().getAnnounceURLList().getSets();
            if (sets != null && sets.length > 0) {
                for (int tier = 0; tier < sets.length; tier++) {
                    for (java.net.URL url : sets[tier].getURLs()) {
                        JsonObject tr = new JsonObject();
                        tr.addProperty("url",            url.toString());
                        tr.addProperty("status",         2);
                        tr.addProperty("tier",           tier);
                        tr.addProperty("num_peers",      -1);
                        tr.addProperty("num_seeds",      -1);
                        tr.addProperty("num_leeches",    -1);
                        tr.addProperty("num_downloaded", -1);
                        tr.addProperty("msg",            "");
                        arr.add(tr);
                    }
                }
            } else {
                java.net.URL url = dl.getTorrent().getAnnounceURL();
                if (url != null) {
                    JsonObject tr = new JsonObject();
                    tr.addProperty("url",            url.toString());
                    tr.addProperty("status",         2);
                    tr.addProperty("tier",           0);
                    tr.addProperty("num_peers",      -1);
                    tr.addProperty("num_seeds",      -1);
                    tr.addProperty("num_leeches",    -1);
                    tr.addProperty("num_downloaded", -1);
                    tr.addProperty("msg",            "");
                    arr.add(tr);
                }
            }
        } catch (Exception ignored) {}

        HttpUtils.sendJson(exchange, arr.toString());
    }

    private void addSyntheticTracker(JsonArray arr, String label, int tier) {
        JsonObject tr = new JsonObject();
        tr.addProperty("url",            label);
        tr.addProperty("status",         2);
        tr.addProperty("tier",           tier);
        tr.addProperty("num_peers",      -1);
        tr.addProperty("num_seeds",      -1);
        tr.addProperty("num_leeches",    -1);
        tr.addProperty("num_downloaded", -1);
        tr.addProperty("msg",            "");
        arr.add(tr);
    }

    // ── GET /api/v2/torrents/files ────────────────────────────────────────────

    private void files(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }

        JsonArray arr = new JsonArray();
        try {
            var diskFiles = dl.getDiskManagerFileInfo();
            if (diskFiles == null) { HttpUtils.sendJson(exchange, "[]"); return; }

            for (int i = 0; i < diskFiles.length; i++) {
                var f = diskFiles[i];
                long len  = f.getLength();
                long done = f.getDownloaded();
                double progress = len > 0 ? (double) done / len : 0.0;

                // Resolve the file name relative to the torrent root
                String name = "";
                try {
                    java.io.File file = f.getFile(false);
                    if (file != null) name = file.getName();
                } catch (Exception ignored) {}

                // priority: 0=skip, 1=normal, 6=high, 7=max
                // BiglyBT uses 0=skip, positive=include
                int priority = f.isSkipped() ? 0 : 1;

                JsonObject fo = new JsonObject();
                fo.addProperty("index",        i);
                fo.addProperty("name",         name);
                fo.addProperty("size",         len);
                fo.addProperty("progress",     progress);
                fo.addProperty("priority",     priority);
                fo.addProperty("is_seed",      i == 0);
                fo.addProperty("availability", progress >= 1.0 ? 1.0 : progress);
                // piece_range as JSON array
                JsonArray pr = new JsonArray();
                pr.add(0); pr.add(0);
                fo.add("piece_range", pr);
                arr.add(fo);
            }
        } catch (Exception ignored) {}

        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── GET /api/v2/torrents/peers ────────────────────────────────────────────

    private void peers(HttpExchange exchange) throws IOException {
        // Full peer details require the internal BiglyBT API — return empty for now
        HttpUtils.sendJson(exchange, "{\"full_update\":true,\"peers\":{},\"rid\":1}");
    }

    // ── GET /api/v2/torrents/categories ──────────────────────────────────────

    private void categories(HttpExchange exchange) throws IOException {
        // Return an empty object — no categories configured yet.
        // TODO: mirror BiglyBT categories here once category management is wired up.
        // Shape: { "catName": { "name": "catName", "savePath": "" }, ... }
        HttpUtils.sendJson(exchange, "{}");
    }

    // ── GET /api/v2/torrents/tags ─────────────────────────────────────────────

    private void tags(HttpExchange exchange) throws IOException {
        // Return empty array — no tags yet.
        HttpUtils.sendJson(exchange, "[]");
    }

    // ── GET /api/v2/torrents/count ────────────────────────────────────────────

    private void count(HttpExchange exchange) throws IOException {
        int n = 0;
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (dl.getTorrent() != null) n++;
        }
        HttpUtils.sendJson(exchange, String.valueOf(n));
    }

    // ── POST /api/v2/torrents/add ─────────────────────────────────────────────

    private void add(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");

        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            addMultipart(exchange);
        } else {
            // application/x-www-form-urlencoded with urls=
            addFromUrls(exchange, HttpUtils.formParams(exchange));
        }
    }

    private void addMultipart(HttpExchange exchange) throws IOException {
        List<MultipartParser.Part> parts = MultipartParser.parse(exchange);
        boolean added = false;

        for (MultipartParser.Part part : parts) {
            if ("urls".equals(part.name) && !part.isFile()) {
                addFromUrls(exchange, Map.of("urls", part.text()));
                return;
            }
            if ("torrents".equals(part.name) && part.isFile()) {
                try {
                    Torrent torrent = pi.getTorrentManager().createFromBEncodedData(part.data);
                    pi.getDownloadManager().addDownload(torrent);
                    added = true;
                } catch (Exception e) {
                    HttpUtils.sendText(exchange, "Fails.");
                    return;
                }
            }
        }

        HttpUtils.sendText(exchange, "Ok.");
    }

    private void addFromUrls(HttpExchange exchange, Map<String, String> params) throws IOException {
        String urls = params.getOrDefault("urls", "").trim();
        if (urls.isEmpty()) { HttpUtils.sendText(exchange, "Ok."); return; }

        for (String line : urls.split("\n")) {
            String url = line.trim();
            if (url.isEmpty()) continue;
            try {
                pi.getDownloadManager().addDownload(new URL(url));
            } catch (Exception e) {
                HttpUtils.sendText(exchange, "Fails.");
                return;
            }
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/pause ───────────────────────────────────────────

    private void pause(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> { try { dl.pause(); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/resume ──────────────────────────────────────────

    private void resume(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> { try { dl.resume(); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/delete ──────────────────────────────────────────

    private void delete(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        boolean deleteFiles = "true".equalsIgnoreCase(params.getOrDefault("deleteFiles", "false"));
        forEachMatching(exchange, dl -> {
            try { dl.stop(); dl.remove(deleteFiles, false); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/recheck ─────────────────────────────────────────

    private void recheck(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> { try { dl.recheckData(); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/reannounce ──────────────────────────────────────

    private void reannounce(HttpExchange exchange) throws IOException {
        // BiglyBT plugin API doesn't expose a direct reannounce method on Download.
        // TODO: use internal DownloadManager to force a scrape/announce
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Download findByHash(String hash) {
        if (hash == null) return null;
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (dl.getTorrent() != null &&
                hash.equalsIgnoreCase(TorrentMapper.hashHex(dl.getTorrent().getHash()))) {
                return dl;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface DownloadConsumer { void accept(Download dl); }

    private void forEachMatching(HttpExchange exchange, DownloadConsumer action) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String hashes = params.getOrDefault("hashes", "all");
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (dl.getTorrent() == null) continue;
            if ("all".equalsIgnoreCase(hashes)) {
                action.accept(dl);
            } else {
                String dlHash = TorrentMapper.hashHex(dl.getTorrent().getHash());
                for (String h : hashes.split("\\|")) {
                    if (h.trim().equalsIgnoreCase(dlHash)) { action.accept(dl); break; }
                }
            }
        }
    }

    private static int parseInt(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }
}
