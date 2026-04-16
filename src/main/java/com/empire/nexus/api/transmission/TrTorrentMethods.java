package com.empire.nexus.api.transmission;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadScrapeResult;
import com.biglybt.pif.download.DownloadStats;
import com.biglybt.pif.disk.DiskManagerFileInfo;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.peers.PeerStats;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pif.torrent.TorrentAnnounceURLListSet;
import com.empire.nexus.util.TorrentMapper;
import com.google.gson.*;

import java.io.File;
import java.net.URL;
import java.util.*;

/**
 * Implements Transmission RPC torrent-* methods for the modern Transmission 4.x web UI.
 *
 * All request field names use snake_case (as sent by the web UI).
 * All response field names use snake_case (as expected by the web UI).
 *
 * torrent-get always returns table format:
 *   torrents: [ [key1, key2, ...], [val1, val2, ...], ... ]
 */
class TrTorrentMethods {

    private final PluginInterface pi;

    private static final int TR_STATUS_STOPPED       = 0;
    private static final int TR_STATUS_CHECK_WAIT    = 1;
    private static final int TR_STATUS_CHECK         = 2;
    private static final int TR_STATUS_DOWNLOAD_WAIT = 3;
    private static final int TR_STATUS_DOWNLOAD      = 4;
    private static final int TR_STATUS_SEED_WAIT     = 5;
    private static final int TR_STATUS_SEED          = 6;

    /** IDs removed since last torrent-get; cleared after each torrent-get response. */
    private final Set<Integer> recentlyRemovedIds = Collections.synchronizedSet(new HashSet<>());

    TrTorrentMethods(PluginInterface pi) {
        this.pi = pi;
    }

    // ── torrent-get ───────────────────────────────────────────────────────────

    JsonObject get(JsonObject args) {
        // Parse requested IDs
        Set<Integer> requestedIds = null;
        if (args.has("ids")) {
            JsonElement idsEl = args.get("ids");
            if (idsEl.isJsonPrimitive()) {
                String s = idsEl.getAsString();
                boolean isRecentlyActive = "recently-active".equals(s) || "recently_active".equals(s);
                if (!isRecentlyActive) {
                    try {
                        requestedIds = new HashSet<>();
                        requestedIds.add(Integer.parseInt(s));
                    } catch (NumberFormatException ignored) {}
                }
            } else if (idsEl.isJsonArray()) {
                JsonArray arr = idsEl.getAsJsonArray();
                if (arr.size() > 0) {
                    requestedIds = new HashSet<>();
                    for (JsonElement e : arr) {
                        try { requestedIds.add(e.getAsInt()); } catch (Exception ignored) {}
                    }
                }
            }
        }

        // Parse requested fields (web UI always sends these in snake_case)
        List<String> fieldList;
        if (args.has("fields")) {
            fieldList = new ArrayList<>();
            for (JsonElement e : args.getAsJsonArray("fields")) fieldList.add(e.getAsString());
        } else {
            fieldList = List.of("id", "name", "status", "percent_done",
                    "rate_download", "rate_upload", "eta",
                    "total_size", "have_valid", "left_until_done", "download_dir");
        }

        boolean tableFormat = !args.has("format") || "table".equals(args.get("format").getAsString());

        // Build per-torrent objects
        List<JsonObject> torrentObjects = new ArrayList<>();
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            int id = torrentId(dl);
            if (requestedIds != null && !requestedIds.contains(id)) continue;
            torrentObjects.add(buildTorrentFields(dl, id, fieldList));
        }

        JsonObject a = new JsonObject();
        if (tableFormat) {
            // Table format: first element is keys array, remaining are data row arrays.
            // Parsed by web UI as: keys = table.shift(); for (row of table) map keys[i]->row[i]
            JsonArray table = new JsonArray();
            JsonArray keys = new JsonArray();
            fieldList.forEach(keys::add);
            table.add(keys);
            for (JsonObject obj : torrentObjects) {
                JsonArray row = new JsonArray();
                for (String k : fieldList) row.add(obj.has(k) ? obj.get(k) : JsonNull.INSTANCE);
                table.add(row);
            }
            a.add("torrents", table);
        } else {
            JsonArray arr = new JsonArray();
            torrentObjects.forEach(arr::add);
            a.add("torrents", arr);
        }
        JsonArray removed = new JsonArray();
        synchronized (recentlyRemovedIds) {
            recentlyRemovedIds.forEach(removed::add);
            recentlyRemovedIds.clear();
        }
        a.add("removed", removed);
        return TransmissionRouter.success(a);
    }

    private JsonObject buildTorrentFields(Download dl, int id, List<String> fields) {
        Torrent       torrent = dl.getTorrent();
        DownloadStats stats   = dl.getStats();
        JsonObject    t       = new JsonObject();
        for (String field : fields) {
            try { addField(t, field, dl, torrent, stats, id); } catch (Exception ignored) {}
        }
        return t;
    }

    @SuppressWarnings("DuplicatedCode")
    private void addField(JsonObject t, String f, Download dl, Torrent torrent,
                          DownloadStats stats, int id) {
        switch (f) {
            // ── Identity ──────────────────────────────────────────────────────
            case "id"          -> t.addProperty("id", id);
            case "hash_string" -> t.addProperty("hash_string", TorrentMapper.hashHex(torrent.getHash()));
            case "name"        -> t.addProperty("name", torrent.getName());

            // ── Status / error ────────────────────────────────────────────────
            case "status"       -> t.addProperty("status", toTrStatus(dl));
            case "error"        -> t.addProperty("error", dl.getState() == Download.ST_ERROR ? 3 : 0);
            case "error_string" -> t.addProperty("error_string", dl.getState() == Download.ST_ERROR ? "Error" : "");
            case "is_stalled"   -> t.addProperty("is_stalled", isStalled(dl, stats));
            case "is_finished"  -> t.addProperty("is_finished", stats.getCompleted() >= 1000);
            case "is_private"   -> { boolean p = false; try { p = torrent.isPrivate(); } catch (Exception ignored) {} t.addProperty("is_private", p); }

            // ── Sizes / progress ──────────────────────────────────────────────
            case "total_size"                -> t.addProperty("total_size", torrent.getSize());
            case "size_when_done"            -> t.addProperty("size_when_done", torrent.getSize());
            case "have_valid"                -> t.addProperty("have_valid", (long)(torrent.getSize() * stats.getCompleted() / 1000.0));
            case "have_unchecked"            -> t.addProperty("have_unchecked", 0L);
            case "left_until_done"           -> { long left = torrent.getSize() - (long)(torrent.getSize() * stats.getCompleted() / 1000.0); t.addProperty("left_until_done", Math.max(0L, left)); }
            case "desired_available"         -> { long left = torrent.getSize() - (long)(torrent.getSize() * stats.getCompleted() / 1000.0); t.addProperty("desired_available", Math.max(0L, left)); }
            case "percent_done"              -> t.addProperty("percent_done", stats.getCompleted() / 1000.0);
            case "recheck_progress"          -> t.addProperty("recheck_progress", 0.0);
            case "metadata_percent_complete" -> t.addProperty("metadata_percent_complete", 1.0);
            case "file_count"                -> { int fc = 0; try { DiskManagerFileInfo[] fi = dl.getDiskManagerFileInfo(); fc = fi != null ? fi.length : 0; } catch (Exception ignored) {} t.addProperty("file_count", fc); }
            case "primary_mime_type"         -> t.addProperty("primary_mime_type", "application/x-bittorrent");

            // ── Speeds / transfer ─────────────────────────────────────────────
            case "rate_download"    -> t.addProperty("rate_download", stats.getDownloadAverage());
            case "rate_upload"      -> t.addProperty("rate_upload", stats.getUploadAverage());
            case "uploaded_ever"    -> t.addProperty("uploaded_ever", stats.getUploaded(false));
            case "downloaded_ever"  -> t.addProperty("downloaded_ever", stats.getDownloaded(false));
            case "corrupt_ever"     -> t.addProperty("corrupt_ever", 0L);
            case "upload_ratio"     -> {
                long downloaded = stats.getDownloaded(false), uploaded = stats.getUploaded(false);
                t.addProperty("upload_ratio", downloaded > 0 ? (double) uploaded / downloaded : (uploaded > 0 ? -2.0 : -1.0));
            }

            // ── ETA ───────────────────────────────────────────────────────────
            case "eta" -> { long e = stats.getETASecs(); t.addProperty("eta", (e == Long.MAX_VALUE || e < 0) ? -1L : e); }

            // ── Timestamps ───────────────────────────────────────────────────
            case "added_date"    -> { long ts = 0; try { ts = stats.getTimeStarted() / 1000L; } catch (Exception ignored) {} t.addProperty("added_date", ts); }
            case "activity_date" -> t.addProperty("activity_date", System.currentTimeMillis() / 1000L);
            case "start_date"    -> { long ts = 0; try { ts = stats.getTimeStarted() / 1000L; } catch (Exception ignored) {} t.addProperty("start_date", ts); }
            case "date_created"  -> { long dc = 0; try { dc = torrent.getCreationDate(); } catch (Exception ignored) {} t.addProperty("date_created", dc); }

            // ── Paths ─────────────────────────────────────────────────────────
            case "download_dir" -> { String dir = ""; try { dir = stats.getDownloadDirectory(); } catch (Exception ignored) {} t.addProperty("download_dir", dir != null ? dir : ""); }
            case "comment"      -> { String c = ""; try { c = torrent.getComment();   } catch (Exception ignored) {} t.addProperty("comment",  c != null ? c : ""); }
            case "creator"      -> { String c = ""; try { c = torrent.getCreatedBy(); } catch (Exception ignored) {} t.addProperty("creator",  c != null ? c : ""); }
            case "magnet_link"  -> t.addProperty("magnet_link", "magnet:?xt=urn:btih:" + TorrentMapper.hashHex(torrent.getHash()) + "&dn=" + urlEncode(torrent.getName()));

            // ── Queue / priority ──────────────────────────────────────────────
            case "queue_position" -> t.addProperty("queue_position", dl.getPosition());

            // ── Per-torrent limits ────────────────────────────────────────────
            case "download_limit"   -> { int lim = 0; try { lim = dl.getDownloadRateLimitBytesPerSecond() / 1024; } catch (Exception ignored) {} t.addProperty("download_limit", lim); }
            case "download_limited" -> { boolean lim = false; try { lim = dl.getDownloadRateLimitBytesPerSecond() > 0; } catch (Exception ignored) {} t.addProperty("download_limited", lim); }
            case "upload_limit"     -> { int lim = 0; try { lim = dl.getUploadRateLimitBytesPerSecond() / 1024; } catch (Exception ignored) {} t.addProperty("upload_limit", lim); }
            case "upload_limited"   -> { boolean lim = false; try { lim = dl.getUploadRateLimitBytesPerSecond() > 0; } catch (Exception ignored) {} t.addProperty("upload_limited", lim); }

            // ── Seeding rules ─────────────────────────────────────────────────
            case "seed_ratio_mode"  -> t.addProperty("seed_ratio_mode", 0);
            case "seed_ratio_limit" -> t.addProperty("seed_ratio_limit", 2.0);

            // ── Peer counts ───────────────────────────────────────────────────
            case "peers_connected"      -> { int c = 0; try { PeerManager pm = dl.getPeerManager(); if (pm != null) c = pm.getStats().getConnectedSeeds() + pm.getStats().getConnectedLeechers(); } catch (Exception ignored) {} t.addProperty("peers_connected", c); }
            case "peers_sending_to_us"  -> { int c = 0; try { PeerManager pm = dl.getPeerManager(); if (pm != null) c = pm.getStats().getConnectedSeeds();    } catch (Exception ignored) {} t.addProperty("peers_sending_to_us",  c); }
            case "peers_getting_from_us"-> { int c = 0; try { PeerManager pm = dl.getPeerManager(); if (pm != null) c = pm.getStats().getConnectedLeechers(); } catch (Exception ignored) {} t.addProperty("peers_getting_from_us", c); }
            case "webseeds_sending_to_us" -> t.addProperty("webseeds_sending_to_us", 0);

            // ── Labels / trackers / files / peers ─────────────────────────────
            case "labels"        -> t.add("labels",       buildLabels(dl));
            case "trackers"      -> t.add("trackers",     buildTrackers(torrent));
            case "tracker_stats" -> t.add("tracker_stats", buildTrackerStats(dl, torrent));
            case "tracker_list"  -> t.addProperty("tracker_list", buildTrackerList(torrent));
            case "files"         -> t.add("files",        buildFiles(dl, stats));
            case "file_stats"    -> t.add("file_stats",   buildFileStats(dl));
            case "peers"         -> t.add("peers",        buildPeers(dl));
            case "webseeds"      -> t.add("webseeds",     new JsonArray());
            case "webseeds_ex"   -> t.add("webseeds_ex",  new JsonArray());

            // ── Piece info ────────────────────────────────────────────────────
            case "piece_count" -> t.addProperty("piece_count", Math.max(1, (int)(torrent.getSize() / 262144L) + 1));
            case "piece_size"  -> t.addProperty("piece_size", 262144L);
            case "pieces"      -> t.addProperty("pieces", "");

            default -> {}
        }
    }

    // ── torrent-add ───────────────────────────────────────────────────────────

    JsonObject add(JsonObject args) {
        // Web UI sends snake_case field names
        String  downloadDir = args.has("download_dir") ? args.get("download_dir").getAsString() : null;
        boolean paused      = args.has("paused") && args.get("paused").getAsBoolean();

        if (downloadDir == null || downloadDir.isEmpty()) {
            try {
                downloadDir = pi.getPluginconfig().getCoreStringParameter(
                        com.biglybt.pif.PluginConfig.CORE_PARAM_STRING_DEFAULT_SAVE_PATH);
            } catch (Exception ignored) {}
        }
        File saveDir = (downloadDir != null && !downloadDir.isEmpty()) ? new File(downloadDir) : null;

        try {
            Download dl;
            if (args.has("metainfo")) {
                byte[]  bytes   = java.util.Base64.getDecoder().decode(args.get("metainfo").getAsString());
                Torrent torrent = pi.getTorrentManager().createFromBEncodedData(bytes);
                dl = pi.getDownloadManager().addDownload(torrent, null, saveDir);
            } else if (args.has("filename")) {
                pi.getDownloadManager().addDownload(toURL(args.get("filename").getAsString()));
                return TransmissionRouter.success(new JsonObject());
            } else {
                return TransmissionRouter.error("no filename or metainfo provided");
            }

            if (dl == null) return TransmissionRouter.error("failed to add torrent");
            applyFilePriorities(dl, args);
            if (paused) {
                try { dl.stop(); } catch (Exception ignored) {}
            } else {
                try { dl.setForceStart(true); } catch (Exception ignored) {}
                try { dl.setForceStart(false); } catch (Exception ignored) {}
            }

            JsonObject added = new JsonObject();
            added.addProperty("id",          torrentId(dl));
            added.addProperty("name",        dl.getTorrent().getName());
            added.addProperty("hash_string", TorrentMapper.hashHex(dl.getTorrent().getHash()));

            JsonObject a = new JsonObject();
            a.add("torrent_added", added);
            return TransmissionRouter.success(a);

        } catch (Exception e) {
            return TransmissionRouter.error("could not add torrent: " + e.getMessage());
        }
    }

    // ── torrent-remove ────────────────────────────────────────────────────────

    JsonObject remove(JsonObject args) {
        boolean del = args.has("delete_local_data") && args.get("delete_local_data").getAsBoolean();
        for (Download dl : resolveIds(args)) {
            int id = torrentId(dl);
            try { dl.stop(); }              catch (Exception ignored) {}
            try { dl.remove(false, del); recentlyRemovedIds.add(id); } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-start / torrent-stop ──────────────────────────────────────────

    JsonObject start(JsonObject args) {
        for (Download dl : resolveIds(args)) {
            try {
                if (dl.isPaused()) dl.resume();
                else               dl.restart();
            } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    JsonObject stop(JsonObject args) {
        for (Download dl : resolveIds(args)) {
            try { dl.stop(); } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-set ───────────────────────────────────────────────────────────

    JsonObject set(JsonObject args) {
        for (Download dl : resolveIds(args)) {
            try {
                if (args.has("download_limit"))
                    dl.setDownloadRateLimitBytesPerSecond(args.get("download_limit").getAsInt() * 1024);
                if (args.has("download_limited") && !args.get("download_limited").getAsBoolean())
                    dl.setDownloadRateLimitBytesPerSecond(0);
                if (args.has("upload_limit"))
                    dl.setUploadRateLimitBytesPerSecond(args.get("upload_limit").getAsInt() * 1024);
                if (args.has("upload_limited") && !args.get("upload_limited").getAsBoolean())
                    dl.setUploadRateLimitBytesPerSecond(0);
                if (args.has("labels")) {
                    com.biglybt.pif.torrent.TorrentAttribute attr = TorrentMapper.getTagsAttr();
                    if (attr != null) {
                        List<String> labels = new ArrayList<>();
                        for (JsonElement e : args.getAsJsonArray("labels")) labels.add(e.getAsString());
                        dl.setListAttribute(attr, labels.toArray(new String[0]));
                    }
                }
                if (args.has("queue_position"))
                    dl.setPosition(args.get("queue_position").getAsInt());
                applyFilePriorities(dl, args);
            } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-verify ────────────────────────────────────────────────────────

    JsonObject verify(JsonObject args) {
        for (Download dl : resolveIds(args)) {
            try { dl.recheckData(); } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-reannounce ────────────────────────────────────────────────────

    JsonObject reannounce(JsonObject args) {
        for (Download dl : resolveIds(args)) {
            try { dl.requestTrackerScrape(true); } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-set-location ──────────────────────────────────────────────────

    JsonObject setLocation(JsonObject args) {
        String  location = args.has("location") ? args.get("location").getAsString() : null;
        boolean move     = !args.has("move") || args.get("move").getAsBoolean();
        if (location == null) return TransmissionRouter.error("location required");
        if (move) {
            File dest = new File(location);
            for (Download dl : resolveIds(args)) {
                try { dl.moveDataFiles(dest); } catch (Exception ignored) {}
            }
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-rename-path ───────────────────────────────────────────────────

    JsonObject renamePath(JsonObject args) {
        String name = args.has("name") ? args.get("name").getAsString() : "";
        JsonObject a = new JsonObject();
        a.addProperty("path", name);
        a.addProperty("name", name);
        List<Download> list = resolveIds(args);
        if (!list.isEmpty()) a.addProperty("id", torrentId(list.get(0)));
        return TransmissionRouter.success(a);
    }

    // ── free-space ────────────────────────────────────────────────────────────

    JsonObject freeSpace(JsonObject args) {
        String path = args.has("path") ? args.get("path").getAsString()
                                       : System.getProperty("user.home");
        long free = 0, total = 0;
        try { File f = new File(path); free = f.getFreeSpace(); total = f.getTotalSpace(); } catch (Exception ignored) {}
        JsonObject a = new JsonObject();
        a.addProperty("path",       path);
        a.addProperty("size_bytes", free);
        a.addProperty("total_size", total);
        return TransmissionRouter.success(a);
    }

    // ── queue-move ────────────────────────────────────────────────────────────

    JsonObject queueMove(JsonObject args, String direction) {
        for (Download dl : resolveIds(args)) {
            try {
                int pos = dl.getPosition();
                switch (direction) {
                    case "top"    -> dl.setPosition(1);
                    case "bottom" -> dl.setPosition(Integer.MAX_VALUE);
                    case "up"     -> dl.setPosition(Math.max(1, pos - 1));
                    case "down"   -> dl.setPosition(pos + 1);
                }
            } catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    static int torrentId(Download dl) {
        byte[] hash = dl.getTorrent().getHash();
        int id = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16)
               | ((hash[2] & 0xFF) <<  8) |  (hash[3] & 0xFF);
        id &= 0x7FFFFFFF;
        return id == 0 ? 1 : id;
    }

    private Download findById(int id) {
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (TorrentMapper.isUserDownload(dl) && torrentId(dl) == id) return dl;
        }
        return null;
    }

    private List<Download> resolveIds(JsonObject args) {
        List<Download> result = new ArrayList<>();
        if (!args.has("ids")) {
            for (Download dl : pi.getDownloadManager().getDownloads())
                if (TorrentMapper.isUserDownload(dl)) result.add(dl);
            return result;
        }
        JsonElement idsEl = args.get("ids");
        if (idsEl.isJsonPrimitive()) {
            String s = idsEl.getAsString();
            if ("recently-active".equals(s) || "recently_active".equals(s)) {
                for (Download dl : pi.getDownloadManager().getDownloads())
                    if (TorrentMapper.isUserDownload(dl)) result.add(dl);
            } else {
                try { Download dl = findById(Integer.parseInt(s)); if (dl != null) result.add(dl); } catch (Exception ignored) {}
            }
        } else if (idsEl.isJsonArray()) {
            for (JsonElement e : idsEl.getAsJsonArray()) {
                try { Download dl = findById(e.getAsInt()); if (dl != null) result.add(dl); } catch (Exception ignored) {}
            }
        }
        return result;
    }

    private int toTrStatus(Download dl) {
        if (dl.isPaused()) return TR_STATUS_STOPPED;
        return switch (dl.getState()) {
            case Download.ST_DOWNLOADING          -> TR_STATUS_DOWNLOAD;
            case Download.ST_SEEDING              -> TR_STATUS_SEED;
            case Download.ST_QUEUED,
                 Download.ST_WAITING,
                 Download.ST_READY                -> dl.getStats().getCompleted() >= 1000
                                                        ? TR_STATUS_SEED_WAIT
                                                        : TR_STATUS_DOWNLOAD_WAIT;
            case Download.ST_PREPARING            -> TR_STATUS_CHECK;
            default                               -> TR_STATUS_STOPPED;
        };
    }

    private boolean isStalled(Download dl, DownloadStats stats) {
        int state = dl.getState();
        if (state != Download.ST_DOWNLOADING && state != Download.ST_SEEDING) return false;
        return stats.getDownloadAverage() == 0 && stats.getUploadAverage() == 0;
    }

    // ── Sub-object builders ───────────────────────────────────────────────────

    private JsonArray buildLabels(Download dl) {
        JsonArray labels = new JsonArray();
        try {
            com.biglybt.pif.torrent.TorrentAttribute attr = TorrentMapper.getTagsAttr();
            if (attr != null) {
                String[] tags = dl.getListAttribute(attr);
                if (tags != null) for (String tag : tags) if (tag != null && !tag.isEmpty()) labels.add(tag);
            }
        } catch (Exception ignored) {}
        return labels;
    }

    private JsonArray buildTrackers(Torrent torrent) {
        JsonArray arr = new JsonArray();
        try {
            TorrentAnnounceURLListSet[] sets = torrent.getAnnounceURLList().getSets();
            if (sets != null && sets.length > 0) {
                int tid = 0;
                for (int tier = 0; tier < sets.length; tier++) {
                    for (URL url : sets[tier].getURLs()) arr.add(trackerEntry(url, tid++, tier));
                }
                return arr;
            }
        } catch (Exception ignored) {}
        try {
            URL url = torrent.getAnnounceURL();
            if (url != null) arr.add(trackerEntry(url, 0, 0));
        } catch (Exception ignored) {}
        return arr;
    }

    private JsonObject trackerEntry(URL url, int id, int tier) {
        JsonObject e = new JsonObject();
        e.addProperty("id",       id);
        e.addProperty("tier",     tier);
        e.addProperty("announce", url.toString());
        e.addProperty("scrape",   url.toString().replace("/announce", "/scrape"));
        e.addProperty("sitename", url.getHost());
        return e;
    }

    private JsonArray buildTrackerStats(Download dl, Torrent torrent) {
        int seeds = -1, leechers = -1;
        try {
            DownloadScrapeResult s = dl.getAggregatedScrapeResult(false);
            if (s != null && s.getResponseType() == DownloadScrapeResult.RT_SUCCESS) {
                seeds = s.getSeedCount(); leechers = s.getNonSeedCount();
            }
        } catch (Exception ignored) {}

        JsonArray arr = new JsonArray();
        try {
            TorrentAnnounceURLListSet[] sets = torrent.getAnnounceURLList().getSets();
            if (sets != null && sets.length > 0) {
                int tid = 0;
                for (int tier = 0; tier < sets.length; tier++) {
                    for (URL url : sets[tier].getURLs())
                        arr.add(trackerStatEntry(url, tid++, tier, seeds, leechers));
                }
                return arr;
            }
        } catch (Exception ignored) {}
        try {
            URL url = torrent.getAnnounceURL();
            if (url != null) arr.add(trackerStatEntry(url, 0, 0, seeds, leechers));
        } catch (Exception ignored) {}
        return arr;
    }

    private JsonObject trackerStatEntry(URL url, int id, int tier, int seeds, int leechers) {
        long now = System.currentTimeMillis() / 1000L;
        JsonObject e = new JsonObject();
        e.addProperty("id",                       id);
        e.addProperty("tier",                     tier);
        e.addProperty("is_backup",                tier > 0);
        e.addProperty("announce",                 url.toString());
        e.addProperty("scrape",                   url.toString().replace("/announce", "/scrape"));
        e.addProperty("host",                     url.getHost());
        e.addProperty("sitename",                 url.getHost());
        e.addProperty("announce_state",           1);
        e.addProperty("scrape_state",             1);
        e.addProperty("seeder_count",             seeds);
        e.addProperty("leecher_count",            leechers);
        e.addProperty("download_count",           -1);
        e.addProperty("has_announced",            true);
        e.addProperty("has_scraped",              seeds >= 0);
        e.addProperty("last_announce_succeeded",  true);
        e.addProperty("last_announce_result",     "Success");
        e.addProperty("last_announce_time",       now - 600);
        e.addProperty("last_announce_start_time", now - 600);
        e.addProperty("last_announce_timed_out",  false);
        e.addProperty("last_announce_peer_count", seeds >= 0 ? seeds + leechers : 0);
        e.addProperty("last_scrape_succeeded",    seeds >= 0);
        e.addProperty("last_scrape_result",       seeds >= 0 ? "Success" : "");
        e.addProperty("last_scrape_time",         now - 600);
        e.addProperty("last_scrape_start_time",   now - 600);
        e.addProperty("last_scrape_timed_out",    false);
        e.addProperty("next_announce_time",       now + 1800);
        e.addProperty("next_scrape_time",         now + 1800);
        return e;
    }

    private String buildTrackerList(Torrent torrent) {
        StringBuilder sb = new StringBuilder();
        try {
            TorrentAnnounceURLListSet[] sets = torrent.getAnnounceURLList().getSets();
            if (sets != null) {
                for (TorrentAnnounceURLListSet set : sets) {
                    for (URL u : set.getURLs()) sb.append(u).append("\n");
                    sb.append("\n");
                }
                return sb.toString().trim();
            }
        } catch (Exception ignored) {}
        try {
            URL url = torrent.getAnnounceURL();
            if (url != null) return url.toString();
        } catch (Exception ignored) {}
        return "";
    }

    private JsonArray buildFiles(Download dl, DownloadStats stats) {
        JsonArray arr = new JsonArray();
        try {
            DiskManagerFileInfo[] files = dl.getDiskManagerFileInfo();
            if (files == null) return arr;
            String dir = "";
            try { dir = stats.getDownloadDirectory(); } catch (Exception ignored) {}
            for (DiskManagerFileInfo fi : files) {
                try {
                    String name = "";
                    try {
                        String abs = fi.getFile(false).getAbsolutePath();
                        if (dir != null && !dir.isEmpty() && abs.startsWith(dir))
                            name = abs.substring(dir.length()).replaceAll("^[/\\\\]+", "");
                        else
                            name = fi.getFile(false).getName();
                    } catch (Exception ignored) {}
                    JsonObject file = new JsonObject();
                    file.addProperty("name",            name);
                    file.addProperty("length",          fi.getLength());
                    file.addProperty("bytes_completed", fi.getDownloaded());
                    file.addProperty("priority",        toPriority(fi.getNumericPriority()));
                    arr.add(file);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private JsonArray buildFileStats(Download dl) {
        JsonArray arr = new JsonArray();
        try {
            DiskManagerFileInfo[] files = dl.getDiskManagerFileInfo();
            if (files == null) return arr;
            for (DiskManagerFileInfo fi : files) {
                try {
                    JsonObject fs = new JsonObject();
                    fs.addProperty("bytes_completed", fi.getDownloaded());
                    fs.addProperty("wanted",          !fi.isSkipped());
                    fs.addProperty("priority",        toPriority(fi.getNumericPriority()));
                    arr.add(fs);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private JsonArray buildPeers(Download dl) {
        JsonArray arr = new JsonArray();
        try {
            PeerManager pm = dl.getPeerManager();
            if (pm == null) return arr;
            Peer[] peers = pm.getPeers();
            if (peers == null) return arr;
            for (Peer peer : peers) {
                try {
                    PeerStats ps = peer.getStats();
                    JsonObject p = new JsonObject();
                    p.addProperty("address",           peer.getIp());
                    p.addProperty("port",              peer.getTCPListenPort() > 0 ? peer.getTCPListenPort() : peer.getPort());
                    p.addProperty("client_name",       peer.getClient() != null ? peer.getClient() : "");
                    p.addProperty("progress",          peer.getPercentDoneInThousandNotation() / 1000.0);
                    p.addProperty("rate_to_client",    ps != null ? ps.getDownloadAverage() : 0);
                    p.addProperty("rate_to_peer",      ps != null ? ps.getUploadAverage()   : 0);
                    p.addProperty("flag_str",          "");
                    p.addProperty("is_utp",            false);
                    p.addProperty("is_incoming",       peer.isIncoming());
                    p.addProperty("is_encrypted",      false);
                    p.addProperty("is_downloading_from", ps != null && ps.getDownloadAverage() > 0);
                    p.addProperty("is_uploading_to",   ps != null && ps.getUploadAverage()   > 0);
                    arr.add(p);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private void applyFilePriorities(Download dl, JsonObject args) {
        DiskManagerFileInfo[] files;
        try { files = dl.getDiskManagerFileInfo(); } catch (Exception e) { return; }
        if (files == null) return;
        if (args.has("files_wanted")) {
            Set<Integer> wanted = toIntSet(args.getAsJsonArray("files_wanted"));
            for (int i = 0; i < files.length; i++) if (wanted.contains(i)) try { files[i].setSkipped(false); } catch (Exception ignored) {}
        }
        if (args.has("files_unwanted")) {
            Set<Integer> unwanted = toIntSet(args.getAsJsonArray("files_unwanted"));
            for (int i = 0; i < files.length; i++) if (unwanted.contains(i)) try { files[i].setSkipped(true); } catch (Exception ignored) {}
        }
        if (args.has("priority_high")) {
            Set<Integer> hi = toIntSet(args.getAsJsonArray("priority_high"));
            for (int i = 0; i < files.length; i++) if (hi.contains(i)) try { files[i].setNumericPriority(DiskManagerFileInfo.PRIORITY_HIGH); } catch (Exception ignored) {}
        }
        if (args.has("priority_normal")) {
            Set<Integer> normal = toIntSet(args.getAsJsonArray("priority_normal"));
            for (int i = 0; i < files.length; i++) if (normal.contains(i)) try { files[i].setNumericPriority(DiskManagerFileInfo.PRIORITY_NORMAL); } catch (Exception ignored) {}
        }
        if (args.has("priority_low")) {
            Set<Integer> lo = toIntSet(args.getAsJsonArray("priority_low"));
            for (int i = 0; i < files.length; i++) if (lo.contains(i)) try { files[i].setNumericPriority(DiskManagerFileInfo.PRIORITY_LOW); } catch (Exception ignored) {}
        }
    }

    private static int toPriority(int biglyBTPriority) {
        if (biglyBTPriority > 1)  return  1;
        if (biglyBTPriority < 0)  return -1;
        return 0;
    }

    private static Set<Integer> toIntSet(JsonArray arr) {
        Set<Integer> set = new HashSet<>();
        if (arr != null) for (JsonElement e : arr) try { set.add(e.getAsInt()); } catch (Exception ignored) {}
        return set;
    }

    private static URL toURL(String s) throws Exception {
        if (s.startsWith("magnet:")) {
            try { return new URL(s); } catch (java.net.MalformedURLException ignored) {}
            return new URL(null, s, new java.net.URLStreamHandler() {
                @Override
                protected java.net.URLConnection openConnection(URL u) throws java.io.IOException {
                    throw new java.io.IOException("magnet handled internally by BiglyBT");
                }
            });
        }
        return new URL(s);
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }
}
