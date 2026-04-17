package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.disk.DiskManagerFileInfo;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAttribute;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.http.MultipartParser;
import com.empire.nexus.util.TorrentMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * /api/v2/torrents/* endpoints.
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
            case "pieceHashes" -> pieceHashes(exchange);
            case "categories"  -> categories(exchange);
            case "tags"        -> tags(exchange);
            case "count"       -> count(exchange);

            case "add"                  -> add(exchange);
            case "pause", "stop"        -> pause(exchange);   // stop = qBt 5.x alias
            case "resume", "start"      -> resume(exchange);  // start = qBt 5.x alias
            case "delete"        -> delete(exchange);
            case "recheck"       -> recheck(exchange);
            case "reannounce"    -> reannounce(exchange);
            case "rename"        -> rename(exchange);
            case "filePrio"      -> filePrio(exchange);

            case "setCategory"   -> setCategory(exchange);
            case "addTags"       -> addTags(exchange);
            case "removeTags"    -> removeTags(exchange);

            case "downloadLimit"    -> getDownloadLimit(exchange);
            case "uploadLimit"      -> getUploadLimit(exchange);
            case "setDownloadLimit" -> setDownloadLimit(exchange);
            case "setUploadLimit"   -> setUploadLimit(exchange);

            // Category management — no actual storage needed; categories are per-torrent
            case "createCategory",
                 "editCategory",
                 "removeCategories" -> HttpUtils.sendText(exchange, "Ok.");

            // Tag management — no-op stubs (tags stored per-torrent in addTags/removeTags)
            case "createTags",
                 "deleteTags"       -> HttpUtils.sendText(exchange, "Ok.");

            case "webseeds"      -> webseeds(exchange);
            case "setLocation",
                 "setSavePath",
                 "setDownloadPath" -> setLocation(exchange);
            case "topPrio"       -> topPrio(exchange);
            case "bottomPrio"    -> bottomPrio(exchange);
            case "increasePrio"  -> increasePrio(exchange);
            case "decreasePrio"  -> decreasePrio(exchange);

            case "toggleSequentialDownload" -> toggleSequentialDownload(exchange);

            case "setForceStart" -> setForceStart(exchange);

            case "addTrackers"    -> addTrackers(exchange);
            case "removeTrackers" -> removeTrackers(exchange);
            case "editTracker"    -> editTracker(exchange);
            case "addPeers"       -> addPeers(exchange);
            case "exportTorrent"  -> exportTorrent(exchange);

            // Misc accepted but no-op
            case "setAutoManagement",
                 "toggleFirstLastPiecePrio",
                 "setSuperSeeding",
                 "renameFile",
                 "renameFolder",
                 "setShareLimits"   -> HttpUtils.sendText(exchange, "Ok.");

            default -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    // ── GET /api/v2/torrents/info ─────────────────────────────────────────────

    private void torrentList(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.queryParams(exchange);
        String filter   = params.getOrDefault("filter",   "all");
        String hashes   = params.getOrDefault("hashes",   "");
        int    limit    = parseInt(params.getOrDefault("limit",  "0"), 0);
        int    offset   = parseInt(params.getOrDefault("offset", "0"), 0);

        Download[] downloads = pi.getDownloadManager().getDownloads();
        JsonArray arr = new JsonArray();

        int skipped = 0;
        for (Download dl : downloads) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
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

        String savePath = "";
        try { savePath = stats.getDownloadDirectory(); } catch (Exception ignored) {}
        if (savePath == null) savePath = "";

        JsonObject o = new JsonObject();
        o.addProperty("hash",               hash);
        o.addProperty("infohash_v1",        hash);
        o.addProperty("infohash_v2",        "");
        o.addProperty("name",               t.getName());
        o.addProperty("total_size",         t.getSize());
        o.addProperty("comment",            comment);
        o.addProperty("created_by",         createdBy);
        o.addProperty("creation_date",      creationDate);
        o.addProperty("private",            isPrivate);
        long wasted = 0;
        try { wasted = stats.getDiscarded(); } catch (Exception ignored) {}
        o.addProperty("total_wasted",       wasted);
        o.addProperty("total_uploaded",     ulTotal);
        o.addProperty("total_downloaded",   dlTotal);
        o.addProperty("uploaded_session",   0L);
        o.addProperty("downloaded_session", 0L);
        o.addProperty("share_ratio",        ratio);
        long addedOn = 0;
        com.biglybt.core.download.DownloadManagerState dms = TorrentMapper.getDownloadState(dl);
        if (dms != null) {
            long addedMs = dms.getLongParameter(com.biglybt.core.download.DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME);
            if (addedMs > 0) addedOn = addedMs / 1000L;
        }
        if (addedOn <= 0 && timeStarted > 0) addedOn = timeStarted / 1000L;
        o.addProperty("addition_date", addedOn);
        long seedStartMs = 0;
        try { seedStartMs = stats.getTimeStartedSeeding(); } catch (Exception ignored) {}
        long completionOn = 0;
        if (dms != null) {
            long ct = dms.getLongParameter(com.biglybt.core.download.DownloadManagerState.PARAM_DOWNLOAD_COMPLETED_TIME);
            if (ct > 0) completionOn = ct / 1000L;
        }
        if (completionOn <= 0 && seedStartMs > 0) completionOn = seedStartMs / 1000L;
        o.addProperty("completion_date", completionOn);
        o.addProperty("seen_complete",      0L);
        o.addProperty("reannounce",         0L);
        o.addProperty("has_metadata",       true);
        o.addProperty("popularity",         0.0);
        int piecesNum = 0, piecesHave = 0;
        long pieceSize = 0;
        try { piecesNum = (int) t.getPieceCount(); } catch (Exception ignored) {}
        try { pieceSize = t.getPieceSize();        } catch (Exception ignored) {}
        try {
            DiskManagerFileInfo[] pf = dl.getDiskManagerFileInfo();
            if (pf != null) {
                for (DiskManagerFileInfo fi : pf)
                    piecesHave += (int)(fi.getDownloaded() / Math.max(1, pieceSize > 0 ? pieceSize : 262144));
                piecesHave = Math.min(piecesHave, piecesNum);
            }
        } catch (Exception ignored) {}
        o.addProperty("pieces_num",         piecesNum);
        o.addProperty("pieces_have",        piecesHave);
        o.addProperty("piece_size",         pieceSize);
        o.addProperty("nb_connections",     peersConnected);
        o.addProperty("nb_connections_limit", -1);
        o.addProperty("seeds",              seedsConnected);
        o.addProperty("seeds_total",        -1);
        o.addProperty("peers",              peersConnected);
        o.addProperty("peers_total",        -1);
        o.addProperty("dl_speed",           stats.getDownloadAverage());
        o.addProperty("dl_speed_avg",       stats.getDownloadAverage());
        o.addProperty("up_speed",           stats.getUploadAverage());
        o.addProperty("up_speed_avg",       stats.getUploadAverage());
        int dlLim = 0, ulLim = 0;
        try { dlLim = dl.getDownloadRateLimitBytesPerSecond(); } catch (Exception ignored) {}
        try { ulLim = dl.getUploadRateLimitBytesPerSecond();   } catch (Exception ignored) {}
        o.addProperty("dl_limit",           dlLim > 0 ? dlLim : -1);
        o.addProperty("up_limit",           ulLim > 0 ? ulLim : -1);
        o.addProperty("eta",                stats.getETASecs() < 0 ? -1L : stats.getETASecs());
        long seedingTime = 0;
        try { seedingTime = stats.getSecondsOnlySeeding(); } catch (Exception ignored) {}
        o.addProperty("seeding_time",       seedingTime);
        long timeElapsed = timeStarted > 0 ? (System.currentTimeMillis() - timeStarted) / 1000L : 0L;
        o.addProperty("time_elapsed",       timeElapsed);
        o.addProperty("save_path",          savePath);
        o.addProperty("download_path",      savePath);

        HttpUtils.sendJson(exchange, o.toString());
    }

    // ── GET /api/v2/torrents/trackers ─────────────────────────────────────────

    private void trackers(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }

        // qBittorrent tracker status: 0=disabled, 1=not contacted, 2=working, 3=updating, 4=not working
        int announceStatus = 1;
        String announceMsg = "";
        int numPeers = -1, numSeeds = -1, numLeeches = -1;
        try {
            com.biglybt.pif.download.DownloadAnnounceResult ann = dl.getLastAnnounceResult();
            if (ann != null) {
                if (ann.getResponseType() == com.biglybt.pif.download.DownloadAnnounceResult.RT_SUCCESS) {
                    announceStatus = 2;
                    numPeers   = ann.getReportedPeerCount();
                    numSeeds   = ann.getSeedCount();
                    numLeeches = ann.getNonSeedCount();
                } else {
                    announceStatus = 4;
                    String err = ann.getError();
                    announceMsg = err != null ? err : "";
                }
            }
        } catch (Exception ignored) {}
        if (dl.isPaused()) announceStatus = 0;

        JsonArray arr = new JsonArray();
        addSyntheticTracker(arr, "** [DHT] **", -1);
        addSyntheticTracker(arr, "** [PeX] **", -1);
        addSyntheticTracker(arr, "** [LSD] **", -1);

        try {
            com.biglybt.pif.torrent.TorrentAnnounceURLListSet[] sets =
                    dl.getTorrent().getAnnounceURLList().getSets();
            if (sets != null && sets.length > 0) {
                for (int tier = 0; tier < sets.length; tier++) {
                    for (java.net.URL url : sets[tier].getURLs()) {
                        arr.add(trackerObj(url.toString(), tier, announceStatus, numPeers, numSeeds, numLeeches, announceMsg));
                    }
                }
            } else {
                java.net.URL url = dl.getTorrent().getAnnounceURL();
                if (url != null) arr.add(trackerObj(url.toString(), 0, announceStatus, numPeers, numSeeds, numLeeches, announceMsg));
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

    private JsonObject trackerObj(String url, int tier, int status, int numPeers, int numSeeds, int numLeeches, String msg) {
        JsonObject tr = new JsonObject();
        tr.addProperty("url",            url);
        tr.addProperty("status",         status);
        tr.addProperty("tier",           tier);
        tr.addProperty("num_peers",      numPeers);
        tr.addProperty("num_seeds",      numSeeds);
        tr.addProperty("num_leeches",    numLeeches);
        tr.addProperty("num_downloaded", -1);
        tr.addProperty("msg",            msg);
        return tr;
    }

    // ── GET /api/v2/torrents/files ────────────────────────────────────────────

    private void files(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }

        String saveDir = "";
        try { String sd = dl.getStats().getDownloadDirectory(); if (sd != null) saveDir = sd; } catch (Exception ignored) {}
        String basePath = saveDir.isEmpty() ? "" : new File(saveDir).getAbsolutePath();
        if (!basePath.isEmpty() && !basePath.endsWith(File.separator)) basePath += File.separator;

        JsonArray arr = new JsonArray();
        try {
            DiskManagerFileInfo[] diskFiles = dl.getDiskManagerFileInfo();
            if (diskFiles == null) { HttpUtils.sendJson(exchange, "[]"); return; }

            for (int i = 0; i < diskFiles.length; i++) {
                DiskManagerFileInfo f = diskFiles[i];
                long len  = f.getLength();
                long done = f.getDownloaded();
                double progress = len > 0 ? (double) done / len : 0.0;

                String name = "";
                try {
                    File file = f.getFile(false);
                    if (file != null) {
                        String abs = file.getAbsolutePath();
                        if (!basePath.isEmpty() && abs.startsWith(basePath)) {
                            name = abs.substring(basePath.length()).replace(File.separatorChar, '/');
                        } else {
                            name = file.getName();
                        }
                    }
                } catch (Exception ignored) {}

                // qBittorrent priority: 0=skip, 1=normal, 6=high, 7=max
                int priority = f.isSkipped() ? 0 : f.getNumericPriority() > 1 ? 6 : 1;

                JsonObject fo = new JsonObject();
                fo.addProperty("index",        i);
                fo.addProperty("name",         name);
                fo.addProperty("size",         len);
                fo.addProperty("progress",     progress);
                fo.addProperty("priority",     priority);
                fo.addProperty("is_seed",      dl.getStats().getCompleted() >= 1000);
                fo.addProperty("availability", progress >= 1.0 ? 1.0 : progress);
                JsonArray pr = new JsonArray();
                pr.add(f.getFirstPieceNumber());
                pr.add(f.getFirstPieceNumber() + f.getNumPieces() - 1);
                fo.add("piece_range", pr);
                arr.add(fo);
            }
        } catch (Exception ignored) {}

        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── GET /api/v2/torrents/peers ────────────────────────────────────────────

    private void peers(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }

        JsonObject result = new JsonObject();
        result.addProperty("full_update", true);
        result.addProperty("rid", 1);
        result.add("peers", TorrentMapper.buildPeersJson(dl));
        HttpUtils.sendJson(exchange, result.toString());
    }

    // ── GET /api/v2/torrents/categories ──────────────────────────────────────

    private void categories(HttpExchange exchange) throws IOException {
        JsonObject cats = new JsonObject();
        Set<String> seen = new HashSet<>();
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            try {
                String cat = dl.getCategoryName();
                if (cat != null && !cat.isEmpty() && seen.add(cat)) {
                    JsonObject obj = new JsonObject();
                    obj.addProperty("name",     cat);
                    obj.addProperty("savePath", "");
                    cats.add(cat, obj);
                }
            } catch (Exception ignored) {}
        }
        HttpUtils.sendJson(exchange, cats.toString());
    }

    // ── GET /api/v2/torrents/tags ─────────────────────────────────────────────

    private void tags(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        Set<String> seen = new HashSet<>();
        TorrentAttribute attr = TorrentMapper.getTagsAttr();
        if (attr == null) { HttpUtils.sendJson(exchange, arr.toString()); return; }

        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            try {
                String[] stored = dl.getListAttribute(attr);
                if (stored != null) {
                    for (String t : stored) {
                        if (t != null && !t.isEmpty() && seen.add(t)) arr.add(t);
                    }
                }
            } catch (Exception ignored) {}
        }
        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── GET /api/v2/torrents/count ────────────────────────────────────────────

    private void count(HttpExchange exchange) throws IOException {
        int n = 0;
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (TorrentMapper.isUserDownload(dl)) n++;
        }
        HttpUtils.sendJson(exchange, String.valueOf(n));
    }

    // ── POST /api/v2/torrents/add ─────────────────────────────────────────────

    private void add(HttpExchange exchange) throws IOException {
        String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
        if (contentType != null && contentType.startsWith("multipart/form-data")) {
            addMultipart(exchange);
        } else {
            addFromParams(exchange, HttpUtils.formParams(exchange));
        }
    }

    private void addMultipart(HttpExchange exchange) throws IOException {
        List<MultipartParser.Part> parts = MultipartParser.parse(exchange);

        // Collect all non-file fields first
        Map<String, String> fields = new java.util.HashMap<>();
        List<MultipartParser.Part> torrentFiles = new java.util.ArrayList<>();

        for (MultipartParser.Part part : parts) {
            if (part.isFile()) {
                torrentFiles.add(part);
            } else {
                fields.put(part.name, part.text());
            }
        }

        if (!torrentFiles.isEmpty()) {
            for (MultipartParser.Part tp : torrentFiles) {
                try {
                    Torrent torrent = pi.getTorrentManager().createFromBEncodedData(tp.data);
                    Download dl = addTorrentWithOptions(torrent, fields);
                    applyPostAddOptions(dl, fields);
                } catch (Exception e) {
                    HttpUtils.sendText(exchange, "Fails.");
                    return;
                }
            }
            HttpUtils.sendText(exchange, "Ok.");
            return;
        }

        addFromParams(exchange, fields);
    }

    private void addFromParams(HttpExchange exchange, Map<String, String> params) throws IOException {
        String urls = params.getOrDefault("urls", "").trim();
        if (urls.isEmpty()) { HttpUtils.sendText(exchange, "Ok."); return; }

        for (String line : urls.split("\n")) {
            String url = line.trim();
            if (url.isEmpty()) continue;
            try {
                String savePath = params.getOrDefault("savepath", "").trim();
                if (!savePath.isEmpty()) {
                    pi.getDownloadManager().addDownload(new URL(url),
                            Map.of("save_path", savePath));
                } else {
                    pi.getDownloadManager().addDownload(new URL(url));
                }
            } catch (Exception e) {
                HttpUtils.sendText(exchange, "Fails.");
                return;
            }
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    private Download addTorrentWithOptions(Torrent torrent, Map<String, String> params) throws Exception {
        String savePath = params.getOrDefault("savepath", "").trim();
        boolean paused  = "true".equalsIgnoreCase(params.getOrDefault("paused", "false"));

        Download dl;
        if (!savePath.isEmpty()) {
            File saveDir = new File(savePath);
            if (paused) {
                dl = pi.getDownloadManager().addDownloadStopped(torrent, null, saveDir);
            } else {
                dl = pi.getDownloadManager().addDownload(torrent, null, saveDir);
            }
        } else {
            if (paused) {
                dl = pi.getDownloadManager().addDownloadStopped(torrent, null, null);
            } else {
                dl = pi.getDownloadManager().addDownload(torrent);
            }
        }
        return dl;
    }

    private void applyPostAddOptions(Download dl, Map<String, String> params) {
        if (dl == null) return;
        String category = params.getOrDefault("category", "").trim();
        if (!category.isEmpty()) {
            try { dl.setCategory(category); } catch (Exception ignored) {}
        }
        String tagsParam = params.getOrDefault("tags", "").trim();
        if (!tagsParam.isEmpty()) {
            TorrentAttribute attr = TorrentMapper.getTagsAttr();
            if (attr != null) {
                try {
                    String[] newTags = Arrays.stream(tagsParam.split(","))
                            .map(String::trim).filter(t -> !t.isEmpty()).toArray(String[]::new);
                    dl.setListAttribute(attr, newTags);
                } catch (Exception ignored) {}
            }
        }
    }

    // ── POST /api/v2/torrents/pause ───────────────────────────────────────────

    private void pause(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> { try { dl.pause(); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/resume ──────────────────────────────────────────

    private void resume(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> {
            try {
                if (dl.isPaused()) dl.resume();
                else               dl.restart();
            } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/delete ──────────────────────────────────────────

    private void delete(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        boolean deleteFiles = "true".equalsIgnoreCase(params.getOrDefault("deleteFiles", "false"));
        forEachMatchingHashes(params.getOrDefault("hashes", "all"), dl -> {
            try { dl.stop(); dl.remove(false, deleteFiles); } catch (Exception ignored) {}
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
        forEachMatching(exchange, dl -> { try { dl.requestTrackerAnnounce(true); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/rename ──────────────────────────────────────────

    private void rename(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String name = params.getOrDefault("name", "").trim();
        Download dl = findByHash(params.get("hash"));
        if (dl != null && !name.isEmpty()) {
            try { dl.renameDownload(name); } catch (Exception ignored) {}
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/filePrio ────────────────────────────────────────

    private void filePrio(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        Download dl = findByHash(params.get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Ok."); return; }

        String idStr    = params.getOrDefault("id", "");
        int    priority = parseInt(params.getOrDefault("priority", "1"), 1);

        try {
            DiskManagerFileInfo[] diskFiles = dl.getDiskManagerFileInfo();
            if (diskFiles == null) { HttpUtils.sendText(exchange, "Ok."); return; }

            Set<Integer> indices = new HashSet<>();
            for (String s : idStr.split("\\|")) {
                try { indices.add(Integer.parseInt(s.trim())); } catch (Exception ignored) {}
            }

            for (DiskManagerFileInfo f : diskFiles) {
                if (!indices.isEmpty() && !indices.contains(f.getIndex())) continue;
                if (priority == 0) {
                    f.setSkipped(true);
                } else {
                    f.setSkipped(false);
                    // qBt: 1=normal, 6=high, 7=max → BiglyBT: PRIORITY_NORMAL, PRIORITY_HIGH
                    f.setNumericPriority(priority >= 6
                            ? DiskManagerFileInfo.PRIORITY_HIGH
                            : DiskManagerFileInfo.PRIORITY_NORMAL);
                }
            }
        } catch (Exception ignored) {}
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/setCategory ────────────────────────────────────

    private void setCategory(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String cat    = params.getOrDefault("category", "").trim();
        String hashes = params.getOrDefault("hashes", "");
        forEachMatchingHashes(hashes, dl -> {
            try { dl.setCategory(cat); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/addTags ─────────────────────────────────────────

    private void addTags(HttpExchange exchange) throws IOException {
        TorrentAttribute attr = TorrentMapper.getTagsAttr();
        Map<String, String> params = HttpUtils.formParams(exchange);
        String hashes   = params.getOrDefault("hashes", "");
        String tagsParam = params.getOrDefault("tags", "").trim();

        if (attr == null || tagsParam.isEmpty()) { HttpUtils.sendText(exchange, "Ok."); return; }

        String[] toAdd = Arrays.stream(tagsParam.split(","))
                .map(String::trim).filter(t -> !t.isEmpty()).toArray(String[]::new);

        forEachMatchingHashes(hashes, dl -> {
            try {
                String[] existing = dl.getListAttribute(attr);
                LinkedHashSet<String> set = new LinkedHashSet<>();
                if (existing != null) set.addAll(Arrays.asList(existing));
                set.addAll(Arrays.asList(toAdd));
                dl.setListAttribute(attr, set.toArray(new String[0]));
            } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/removeTags ──────────────────────────────────────

    private void removeTags(HttpExchange exchange) throws IOException {
        TorrentAttribute attr = TorrentMapper.getTagsAttr();
        Map<String, String> params = HttpUtils.formParams(exchange);
        String hashes    = params.getOrDefault("hashes", "");
        String tagsParam = params.getOrDefault("tags", "").trim();

        if (attr == null || tagsParam.isEmpty()) { HttpUtils.sendText(exchange, "Ok."); return; }

        Set<String> toRemove = new HashSet<>(Arrays.asList(tagsParam.split(",")));
        toRemove.removeIf(String::isEmpty);

        forEachMatchingHashes(hashes, dl -> {
            try {
                String[] existing = dl.getListAttribute(attr);
                if (existing == null) return;
                LinkedHashSet<String> set = new LinkedHashSet<>(Arrays.asList(existing));
                set.removeAll(toRemove);
                dl.setListAttribute(attr, set.toArray(new String[0]));
            } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── GET /api/v2/torrents/pieceHashes ─────────────────────────────────────

    private void pieceHashes(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendJson(exchange, "[]"); return; }
        JsonArray arr = new JsonArray();
        try {
            byte[][] pieces = dl.getTorrent().getPieces();
            if (pieces != null) {
                for (byte[] piece : pieces) arr.add(TorrentMapper.hashHex(piece));
            }
        } catch (Exception ignored) {}
        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── POST /api/v2/torrents/toggleSequentialDownload ────────────────────────

    private void toggleSequentialDownload(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> {
            try {
                boolean current = dl.getFlag(Download.FLAG_SEQUENTIAL_DOWNLOAD);
                dl.setFlag(Download.FLAG_SEQUENTIAL_DOWNLOAD, !current);
            } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/setForceStart ──────────────────────────────────

    private void setForceStart(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        boolean value = "true".equalsIgnoreCase(params.getOrDefault("value", "false"));
        forEachMatchingHashes(params.getOrDefault("hashes", "all"), dl -> {
            try { dl.setForceStart(value); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── GET /api/v2/torrents/webseeds ─────────────────────────────────────────

    private void webseeds(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendJson(exchange, "[]"); return; }
        JsonArray arr = new JsonArray();
        try {
            Object urlList = dl.getTorrent().getAdditionalProperty("url-list");
            java.util.List<String> urls = new java.util.ArrayList<>();
            if (urlList instanceof byte[]) {
                urls.add(new String((byte[]) urlList, java.nio.charset.StandardCharsets.UTF_8).trim());
            } else if (urlList instanceof java.util.List) {
                for (Object item : (java.util.List<?>) urlList) {
                    if (item instanceof byte[]) urls.add(new String((byte[]) item, java.nio.charset.StandardCharsets.UTF_8).trim());
                    else if (item instanceof String) urls.add(((String) item).trim());
                }
            }
            for (String u : urls) {
                if (!u.isEmpty()) {
                    JsonObject seed = new JsonObject();
                    seed.addProperty("url", u);
                    seed.addProperty("remaining", 0L);
                    arr.add(seed);
                }
            }
        } catch (Exception ignored) {}
        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── POST /api/v2/torrents/downloadLimit (read) ────────────────────────────

    private void getDownloadLimit(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String hashes = params.getOrDefault("hashes", "all");
        JsonObject result = new JsonObject();
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            String dlHash = TorrentMapper.hashHex(dl.getTorrent().getHash());
            if (!"all".equalsIgnoreCase(hashes)) {
                boolean match = false;
                for (String h : hashes.split("\\|"))
                    if (h.trim().equalsIgnoreCase(dlHash)) { match = true; break; }
                if (!match) continue;
            }
            int lim = 0;
            try { lim = dl.getDownloadRateLimitBytesPerSecond(); } catch (Exception ignored) {}
            result.addProperty(dlHash, lim);
        }
        HttpUtils.sendJson(exchange, result.toString());
    }

    // ── POST /api/v2/torrents/uploadLimit (read) ──────────────────────────────

    private void getUploadLimit(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String hashes = params.getOrDefault("hashes", "all");
        JsonObject result = new JsonObject();
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            String dlHash = TorrentMapper.hashHex(dl.getTorrent().getHash());
            if (!"all".equalsIgnoreCase(hashes)) {
                boolean match = false;
                for (String h : hashes.split("\\|"))
                    if (h.trim().equalsIgnoreCase(dlHash)) { match = true; break; }
                if (!match) continue;
            }
            int lim = 0;
            try { lim = dl.getUploadRateLimitBytesPerSecond(); } catch (Exception ignored) {}
            result.addProperty(dlHash, lim);
        }
        HttpUtils.sendJson(exchange, result.toString());
    }

    // ── POST /api/v2/torrents/setLocation ─────────────────────────────────────

    private void setLocation(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String location = params.getOrDefault("location",
                          params.getOrDefault("savepath", "")).trim();
        if (!location.isEmpty()) {
            File dest = new File(location);
            forEachMatchingHashes(params.getOrDefault("hashes", "all"), dl -> {
                try { dl.moveDataFiles(dest); } catch (Exception ignored) {}
            });
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/topPrio / bottomPrio / increasePrio / decreasePrio

    private void topPrio(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> { try { dl.setPosition(1); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    private void bottomPrio(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> { try { dl.setPosition(Integer.MAX_VALUE); } catch (Exception ignored) {} });
        HttpUtils.sendText(exchange, "Ok.");
    }

    private void increasePrio(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> {
            try { dl.setPosition(Math.max(1, dl.getPosition() - 1)); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    private void decreasePrio(HttpExchange exchange) throws IOException {
        forEachMatching(exchange, dl -> {
            try { dl.setPosition(dl.getPosition() + 1); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/setDownloadLimit ────────────────────────────────

    private void setDownloadLimit(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        int limit = parseInt(params.getOrDefault("limit", "0"), 0);
        forEachMatchingHashes(params.getOrDefault("hashes", "all"), dl -> {
            try { dl.setDownloadRateLimitBytesPerSecond(limit); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/setUploadLimit ──────────────────────────────────

    private void setUploadLimit(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        int limit = parseInt(params.getOrDefault("limit", "0"), 0);
        forEachMatchingHashes(params.getOrDefault("hashes", "all"), dl -> {
            try { dl.setUploadRateLimitBytesPerSecond(limit); } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/addTrackers ────────────────────────────────────

    private void addTrackers(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        Download dl = findByHash(params.get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Ok."); return; }
        String urlsParam = params.getOrDefault("urls", "").trim();
        if (!urlsParam.isEmpty()) {
            com.biglybt.pif.torrent.TorrentAnnounceURLList list = dl.getTorrent().getAnnounceURLList();
            for (String line : urlsParam.split("[\r\n]+")) {
                String url = line.trim();
                if (url.isEmpty()) continue;
                try { list.addSet(new java.net.URL[]{new java.net.URL(url)}); } catch (Exception ignored) {}
            }
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/removeTrackers ──────────────────────────────────

    private void removeTrackers(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        Download dl = findByHash(params.get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Ok."); return; }
        String urlsParam = params.getOrDefault("urls", "").trim();
        if (!urlsParam.isEmpty()) {
            Set<String> toRemove = new HashSet<>(Arrays.asList(urlsParam.split("\\|")));
            toRemove.removeIf(String::isEmpty);
            try {
                com.biglybt.pif.torrent.TorrentAnnounceURLList list = dl.getTorrent().getAnnounceURLList();
                com.biglybt.pif.torrent.TorrentAnnounceURLListSet[] sets = list.getSets();
                if (sets != null) {
                    java.util.List<com.biglybt.pif.torrent.TorrentAnnounceURLListSet> kept = new java.util.ArrayList<>();
                    for (com.biglybt.pif.torrent.TorrentAnnounceURLListSet set : sets) {
                        boolean hasRemoved = false;
                        for (java.net.URL u : set.getURLs()) {
                            if (toRemove.contains(u.toString())) { hasRemoved = true; break; }
                        }
                        if (!hasRemoved) kept.add(set);
                    }
                    list.setSets(kept.toArray(new com.biglybt.pif.torrent.TorrentAnnounceURLListSet[0]));
                }
            } catch (Exception ignored) {}
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/editTracker ─────────────────────────────────────

    private void editTracker(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        Download dl = findByHash(params.get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Ok."); return; }
        String origUrl = params.getOrDefault("origUrl", "").trim();
        String newUrl  = params.getOrDefault("newUrl",  "").trim();
        if (!origUrl.isEmpty() && !newUrl.isEmpty()) {
            try {
                com.biglybt.pif.torrent.TorrentAnnounceURLList list = dl.getTorrent().getAnnounceURLList();
                com.biglybt.pif.torrent.TorrentAnnounceURLListSet[] sets = list.getSets();
                if (sets != null) {
                    java.util.List<com.biglybt.pif.torrent.TorrentAnnounceURLListSet> kept = new java.util.ArrayList<>();
                    for (com.biglybt.pif.torrent.TorrentAnnounceURLListSet set : sets) {
                        boolean hasOrig = false;
                        for (java.net.URL u : set.getURLs()) {
                            if (u.toString().equals(origUrl)) { hasOrig = true; break; }
                        }
                        if (!hasOrig) kept.add(set);
                    }
                    list.setSets(kept.toArray(new com.biglybt.pif.torrent.TorrentAnnounceURLListSet[0]));
                }
                list.addSet(new java.net.URL[]{new java.net.URL(newUrl)});
            } catch (Exception ignored) {}
        }
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── POST /api/v2/torrents/addPeers ────────────────────────────────────────

    private void addPeers(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String peersParam = params.getOrDefault("peers", "").trim();
        forEachMatchingHashes(params.getOrDefault("hashes", "all"), dl -> {
            try {
                com.biglybt.pif.peers.PeerManager pm = dl.getPeerManager();
                if (pm == null) return;
                for (String peer : peersParam.split("\\|")) {
                    peer = peer.trim();
                    int colon = peer.lastIndexOf(':');
                    if (colon < 0) continue;
                    String ip = peer.substring(0, colon);
                    try {
                        int port = Integer.parseInt(peer.substring(colon + 1));
                        pm.addPeer(ip, port);
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── GET /api/v2/torrents/exportTorrent ────────────────────────────────────

    private void exportTorrent(HttpExchange exchange) throws IOException {
        Download dl = findByHash(HttpUtils.queryParams(exchange).get("hash"));
        if (dl == null) { HttpUtils.sendText(exchange, "Not Found", 404); return; }
        try {
            byte[] data = dl.getTorrent().writeToBEncodedData();
            String filename = dl.getTorrent().getName() + ".torrent";
            exchange.getResponseHeaders().set("Content-Type", "application/x-bittorrent");
            exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + filename + "\"");
            exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        } catch (Exception e) {
            HttpUtils.sendText(exchange, "Error", 500);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private Download findByHash(String hash) {
        if (hash == null) return null;
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (TorrentMapper.isUserDownload(dl) &&
                hash.equalsIgnoreCase(TorrentMapper.hashHex(dl.getTorrent().getHash()))) {
                return dl;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface DownloadConsumer { void accept(Download dl); }

    /** Parses form params, runs action on each matching download, returns params. */
    private Map<String, String> forEachMatching(HttpExchange exchange, DownloadConsumer action) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        forEachMatchingHashes(params.getOrDefault("hashes", "all"), action);
        return params;
    }

    /** Runs action on each download matching the pipe-separated hashes string (or "all"). */
    private void forEachMatchingHashes(String hashes, DownloadConsumer action) {
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            if (hashes == null || hashes.isEmpty() || "all".equalsIgnoreCase(hashes)) {
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
