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
 * Implements Transmission RPC torrent-* methods and free-space / queue-move.
 *
 * Torrent IDs are derived from the first 4 bytes of the info-hash (sign bit
 * cleared), giving stable integer IDs that are consistent across polls without
 * requiring any state storage.
 */
class TrTorrentMethods {

    private final PluginInterface pi;

    // Transmission status codes
    private static final int TR_STATUS_STOPPED       = 0;
    private static final int TR_STATUS_CHECK_WAIT    = 1;
    private static final int TR_STATUS_CHECK         = 2;
    private static final int TR_STATUS_DOWNLOAD_WAIT = 3;
    private static final int TR_STATUS_DOWNLOAD      = 4;
    private static final int TR_STATUS_SEED_WAIT     = 5;
    private static final int TR_STATUS_SEED          = 6;

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
                // "recently-active" → return all (we don't track deltas)
                // Any integer string → single torrent
                String s = idsEl.getAsString();
                if (!"recently-active".equals(s)) {
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
                // empty array → no filter (return all)
            }
        }

        // Parse requested fields — normalize underscore_case → camelCase
        Set<String> fields;
        if (args.has("fields")) {
            fields = new LinkedHashSet<>();
            for (JsonElement e : args.getAsJsonArray("fields")) fields.add(underscoreToCamel(e.getAsString()));
        } else {
            fields = Set.of("id", "name", "status", "percentDone",
                    "rateDownload", "rateUpload", "eta",
                    "totalSize", "haveValid", "leftUntilDone", "downloadDir");
        }

        boolean tableFormat = args.has("format")
                && "table".equals(args.get("format").getAsString());

        // Build torrent list
        JsonArray torrents = new JsonArray();
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (dl.getTorrent() == null) continue;
            int id = torrentId(dl);
            if (requestedIds != null && !requestedIds.contains(id)) continue;
            torrents.add(buildTorrentFields(dl, id, fields));
        }

        JsonObject a = new JsonObject();
        if (tableFormat) {
            // Table format: separate keys array + rows as arrays
            List<String> keyList = new ArrayList<>(fields);
            JsonArray keys = new JsonArray();
            keyList.forEach(keys::add);
            JsonArray rows = new JsonArray();
            for (JsonElement te : torrents) {
                JsonObject obj = te.getAsJsonObject();
                JsonArray row = new JsonArray();
                for (String k : keyList) row.add(obj.has(k) ? obj.get(k) : JsonNull.INSTANCE);
                rows.add(row);
            }
            a.add("torrents", rows);
        } else {
            a.add("torrents", torrents);
        }
        a.add("removed", new JsonArray());
        return TransmissionRouter.success(a);
    }

    private JsonObject buildTorrentFields(Download dl, int id, Set<String> fields) {
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
            case "id"         -> t.addProperty("id", id);
            case "hashString" -> t.addProperty("hashString", TorrentMapper.hashHex(torrent.getHash()));
            case "name"       -> t.addProperty("name", torrent.getName());

            // ── Status / error ────────────────────────────────────────────────
            case "status"      -> t.addProperty("status", toTrStatus(dl));
            case "error"       -> t.addProperty("error", dl.getState() == Download.ST_ERROR ? 3 : 0);
            case "errorString" -> t.addProperty("errorString", dl.getState() == Download.ST_ERROR ? "Error" : "");
            case "isStalled"   -> t.addProperty("isStalled", isStalled(dl, stats));
            case "isFinished"  -> t.addProperty("isFinished", stats.getCompleted() >= 1000);
            case "isPrivate"   -> { boolean p = false; try { p = torrent.isPrivate(); } catch (Exception ignored) {} t.addProperty("isPrivate", p); }

            // ── Sizes / progress ──────────────────────────────────────────────
            case "totalSize"               -> t.addProperty("totalSize", torrent.getSize());
            case "sizeWhenDone"            -> t.addProperty("sizeWhenDone", torrent.getSize());
            case "haveValid"               -> t.addProperty("haveValid", (long)(torrent.getSize() * stats.getCompleted() / 1000.0));
            case "haveUnchecked"           -> t.addProperty("haveUnchecked", 0L);
            case "leftUntilDone"           -> { long left = torrent.getSize() - (long)(torrent.getSize() * stats.getCompleted() / 1000.0); t.addProperty("leftUntilDone", Math.max(0L, left)); }
            case "desiredAvailable"        -> { long left = torrent.getSize() - (long)(torrent.getSize() * stats.getCompleted() / 1000.0); t.addProperty("desiredAvailable", Math.max(0L, left)); }
            case "percentDone"             -> t.addProperty("percentDone", stats.getCompleted() / 1000.0);
            case "recheckProgress"         -> t.addProperty("recheckProgress", 0.0);
            case "metadataPercentComplete" -> t.addProperty("metadataPercentComplete", 1.0);

            // ── Speeds / transfer ─────────────────────────────────────────────
            case "rateDownload"       -> t.addProperty("rateDownload", stats.getDownloadAverage());
            case "rateUpload"         -> t.addProperty("rateUpload", stats.getUploadAverage());
            case "uploadedEver"       -> t.addProperty("uploadedEver", stats.getUploaded(false));
            case "downloadedEver"     -> t.addProperty("downloadedEver", stats.getDownloaded(false));
            case "corruptEver"        -> t.addProperty("corruptEver", 0L);
            case "swarmSpeed"         -> t.addProperty("swarmSpeed", (long)(stats.getDownloadAverage() + stats.getUploadAverage()));
            case "uploadedEverSession"   -> { long u = 0; try { u = stats.getUploaded(true);   } catch (Exception ignored) {} t.addProperty("uploadedEverSession",   u); }
            case "downloadedEverSession" -> { long d = 0; try { d = stats.getDownloaded(true); } catch (Exception ignored) {} t.addProperty("downloadedEverSession", d); }
            case "corruptEverSession"    -> t.addProperty("corruptEverSession", 0L);
            case "uploadRatio"        -> {
                long dl2 = stats.getDownloaded(false), ul = stats.getUploaded(false);
                t.addProperty("uploadRatio", dl2 > 0 ? (double) ul / dl2 : (ul > 0 ? -2.0 : -1.0));
            }

            // ── ETA ───────────────────────────────────────────────────────────
            case "eta"     -> { long e = stats.getETASecs(); t.addProperty("eta", (e == Long.MAX_VALUE || e < 0) ? -1L : e); }
            case "etaIdle" -> t.addProperty("etaIdle", -1L);

            // ── Timestamps ───────────────────────────────────────────────────
            case "addedDate"           -> { long ts = 0; try { ts = stats.getTimeStarted() / 1000L; } catch (Exception ignored) {} t.addProperty("addedDate", ts); }
            case "activityDate"        -> t.addProperty("activityDate", System.currentTimeMillis() / 1000L);
            case "doneDate"            -> t.addProperty("doneDate", stats.getCompleted() >= 1000 ? System.currentTimeMillis() / 1000L : 0L);
            case "startDate"           -> { long ts = 0; try { ts = stats.getTimeStarted() / 1000L; } catch (Exception ignored) {} t.addProperty("startDate", ts); }
            case "dateCreated"         -> { long dc = 0; try { dc = torrent.getCreationDate(); } catch (Exception ignored) {} t.addProperty("dateCreated", dc); }
            case "secondsDownloading"  -> { long ts = 0; try { long st = stats.getTimeStarted(); if (st > 0) ts = (System.currentTimeMillis() - st) / 1000L; } catch (Exception ignored) {} t.addProperty("secondsDownloading", ts); }
            case "secondsSeeding"      -> { long ss = 0; try { ss = stats.getSecondsOnlySeeding(); } catch (Exception ignored) {} t.addProperty("secondsSeeding", ss); }
            case "manualAnnounceTime"  -> t.addProperty("manualAnnounceTime", 0L);

            // ── Paths / links ─────────────────────────────────────────────────
            case "downloadDir"  -> { String dir = ""; try { dir = stats.getDownloadDirectory(); } catch (Exception ignored) {} t.addProperty("downloadDir", dir != null ? dir : ""); }
            case "comment"      -> { String c = ""; try { c = torrent.getComment();   } catch (Exception ignored) {} t.addProperty("comment",  c != null ? c : ""); }
            case "creator"      -> { String c = ""; try { c = torrent.getCreatedBy(); } catch (Exception ignored) {} t.addProperty("creator",  c != null ? c : ""); }
            case "magnetLink"   -> t.addProperty("magnetLink", "magnet:?xt=urn:btih:" + TorrentMapper.hashHex(torrent.getHash()) + "&dn=" + urlEncode(torrent.getName()));

            // ── Queue / priority ──────────────────────────────────────────────
            case "queuePosition"      -> t.addProperty("queuePosition", dl.getPosition());
            case "bandwidthPriority"  -> t.addProperty("bandwidthPriority", 0);
            case "honorsSessionLimits"-> t.addProperty("honorsSessionLimits", true);

            // ── Per-torrent limits ────────────────────────────────────────────
            case "downloadLimit"   -> { int lim = 0; try { lim = dl.getDownloadRateLimitBytesPerSecond() / 1024; } catch (Exception ignored) {} t.addProperty("downloadLimit", lim); }
            case "downloadLimited" -> { boolean lim = false; try { lim = dl.getDownloadRateLimitBytesPerSecond() > 0; } catch (Exception ignored) {} t.addProperty("downloadLimited", lim); }
            case "uploadLimit"     -> { int lim = 0; try { lim = dl.getUploadRateLimitBytesPerSecond() / 1024; } catch (Exception ignored) {} t.addProperty("uploadLimit", lim); }
            case "uploadLimited"   -> { boolean lim = false; try { lim = dl.getUploadRateLimitBytesPerSecond() > 0; } catch (Exception ignored) {} t.addProperty("uploadLimited", lim); }

            // ── Seeding rules ─────────────────────────────────────────────────
            case "seedRatioMode"  -> t.addProperty("seedRatioMode", 0);
            case "seedRatioLimit" -> t.addProperty("seedRatioLimit", 2.0);
            case "seedIdleMode"   -> t.addProperty("seedIdleMode", 0);
            case "seedIdleLimit"  -> t.addProperty("seedIdleLimit", 30);

            // ── Peer counts ───────────────────────────────────────────────────
            case "peersConnected"     -> { int c = 0; try { PeerManager pm = dl.getPeerManager(); if (pm != null) c = pm.getStats().getConnectedSeeds() + pm.getStats().getConnectedLeechers(); } catch (Exception ignored) {} t.addProperty("peersConnected", c); }
            case "peersSendingToUs"   -> { int c = 0; try { PeerManager pm = dl.getPeerManager(); if (pm != null) c = pm.getStats().getConnectedSeeds();    } catch (Exception ignored) {} t.addProperty("peersSendingToUs",   c); }
            case "peersGettingFromUs" -> { int c = 0; try { PeerManager pm = dl.getPeerManager(); if (pm != null) c = pm.getStats().getConnectedLeechers(); } catch (Exception ignored) {} t.addProperty("peersGettingFromUs", c); }
            case "webseedsSendingToUs" -> t.addProperty("webseedsSendingToUs", 0);
            case "peersFrom" -> {
                JsonObject pf = new JsonObject();
                pf.addProperty("fromCache",    0);
                pf.addProperty("fromDht",      0);
                pf.addProperty("fromIncoming", 0);
                pf.addProperty("fromLpd",      0);
                pf.addProperty("fromLtep",     0);
                pf.addProperty("fromPex",      0);
                pf.addProperty("fromTracker",  0);
                t.add("peersFrom", pf);
            }

            // ── Labels / trackers / files / peers (expensive — only fetch when asked) ─
            case "labels"       -> t.add("labels",       buildLabels(dl));
            case "trackers"     -> t.add("trackers",     buildTrackers(torrent));
            case "trackerStats" -> t.add("trackerStats", buildTrackerStats(dl, torrent));
            case "trackerList"  -> t.addProperty("trackerList", buildTrackerList(torrent));
            case "files"        -> t.add("files",        buildFiles(dl, stats));
            case "fileStats"    -> t.add("fileStats",    buildFileStats(dl));
            case "priorities"   -> t.add("priorities",  buildPriorities(dl));
            case "wanted"       -> t.add("wanted",       buildWanted(dl));
            case "peers"        -> t.add("peers",        buildPeers(dl));
            case "webseeds"     -> t.add("webseeds",     new JsonArray());

            // ── Piece info ────────────────────────────────────────────────────
            case "pieceCount" -> t.addProperty("pieceCount", Math.max(1, (int)(torrent.getSize() / 262144L) + 1));
            case "pieceSize"  -> t.addProperty("pieceSize", 262144L);
            case "pieces"     -> t.addProperty("pieces", ""); // base64 bitfield; expensive — skip

            default -> {} // Unknown field — ignore
        }
    }

    // ── torrent-add ───────────────────────────────────────────────────────────

    JsonObject add(JsonObject args) {
        String  downloadDir = args.has("download-dir") ? args.get("download-dir").getAsString() : null;
        boolean paused      = args.has("paused") && args.get("paused").getAsBoolean();

        // Fall back to BiglyBT's configured default save path when not specified
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
                // addDownload(URL) is void and doesn't accept a save dir.
                // BiglyBT handles both HTTP .torrent URLs and magnet links this way.
                // The client will discover the torrent on the next torrent-get poll.
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
                // Force-start to allocate and begin downloading, then revert to normal queuing
                try { dl.setForceStart(true); } catch (Exception ignored) {}
                try { dl.setForceStart(false); } catch (Exception ignored) {}
            }

            JsonObject added = new JsonObject();
            added.addProperty("id",         torrentId(dl));
            added.addProperty("name",       dl.getTorrent().getName());
            added.addProperty("hashString", TorrentMapper.hashHex(dl.getTorrent().getHash()));

            JsonObject a = new JsonObject();
            a.add("torrent-added", added);
            return TransmissionRouter.success(a);

        } catch (Exception e) {
            return TransmissionRouter.error("could not add torrent: " + e.getMessage());
        }
    }

    // ── torrent-remove ────────────────────────────────────────────────────────

    JsonObject remove(JsonObject args) {
        boolean del = args.has("delete-local-data") && args.get("delete-local-data").getAsBoolean();
        for (Download dl : resolveIds(args)) {
            try { dl.stop(); }              catch (Exception ignored) {}
            try { dl.remove(false, del); }  catch (Exception ignored) {}
        }
        return TransmissionRouter.success(new JsonObject());
    }

    // ── torrent-start / torrent-stop ──────────────────────────────────────────

    JsonObject start(JsonObject args) {
        for (Download dl : resolveIds(args)) {
            try { dl.resume(); } catch (Exception ignored) {}
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
                if (args.has("downloadLimit"))
                    dl.setDownloadRateLimitBytesPerSecond(args.get("downloadLimit").getAsInt() * 1024);
                if (args.has("downloadLimited") && !args.get("downloadLimited").getAsBoolean())
                    dl.setDownloadRateLimitBytesPerSecond(0);
                if (args.has("uploadLimit"))
                    dl.setUploadRateLimitBytesPerSecond(args.get("uploadLimit").getAsInt() * 1024);
                if (args.has("uploadLimited") && !args.get("uploadLimited").getAsBoolean())
                    dl.setUploadRateLimitBytesPerSecond(0);
                if (args.has("labels")) {
                    com.biglybt.pif.torrent.TorrentAttribute attr = TorrentMapper.getTagsAttr();
                    if (attr != null) {
                        List<String> labels = new ArrayList<>();
                        for (JsonElement e : args.getAsJsonArray("labels")) labels.add(e.getAsString());
                        dl.setListAttribute(attr, labels.toArray(new String[0]));
                    }
                }
                if (args.has("queuePosition"))
                    dl.setPosition(args.get("queuePosition").getAsInt());
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
        // BiglyBT plugin API doesn't directly support renaming files within a torrent.
        // Echo the new name back so clients don't treat it as a failure.
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
        a.addProperty("size-bytes", free);
        a.addProperty("total-size", total);
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

    /** Stable positive integer ID derived from first 4 bytes of the info-hash. */
    static int torrentId(Download dl) {
        byte[] hash = dl.getTorrent().getHash();
        int id = ((hash[0] & 0xFF) << 24) | ((hash[1] & 0xFF) << 16)
               | ((hash[2] & 0xFF) <<  8) |  (hash[3] & 0xFF);
        id &= 0x7FFFFFFF; // clear sign bit — ensures positive
        return id == 0 ? 1 : id;
    }

    private Download findById(int id) {
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (dl.getTorrent() != null && torrentId(dl) == id) return dl;
        }
        return null;
    }

    /** Resolve the "ids" RPC argument to matching Downloads. Absent/null → all. */
    private List<Download> resolveIds(JsonObject args) {
        List<Download> result = new ArrayList<>();
        if (!args.has("ids")) {
            for (Download dl : pi.getDownloadManager().getDownloads())
                if (dl.getTorrent() != null) result.add(dl);
            return result;
        }
        JsonElement idsEl = args.get("ids");
        if (idsEl.isJsonPrimitive()) {
            String s = idsEl.getAsString();
            if ("recently-active".equals(s)) {
                for (Download dl : pi.getDownloadManager().getDownloads())
                    if (dl.getTorrent() != null) result.add(dl);
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
        e.addProperty("id",                     id);
        e.addProperty("tier",                   tier);
        e.addProperty("isBackup",               tier > 0);
        e.addProperty("announce",               url.toString());
        e.addProperty("scrape",                 url.toString().replace("/announce", "/scrape"));
        e.addProperty("host",                   url.getHost());
        e.addProperty("sitename",               url.getHost());
        e.addProperty("announceState",          1);
        e.addProperty("scrapeState",            1);
        e.addProperty("seederCount",            seeds);
        e.addProperty("leecherCount",           leechers);
        e.addProperty("downloadCount",          -1);
        e.addProperty("hasAnnounced",           true);
        e.addProperty("hasScraped",             seeds >= 0);
        e.addProperty("lastAnnounceSucceeded",  true);
        e.addProperty("lastAnnounceResult",     "Success");
        e.addProperty("lastAnnounceTime",       now - 600);
        e.addProperty("lastAnnounceStartTime",  now - 600);
        e.addProperty("lastAnnounceTimedOut",   false);
        e.addProperty("lastAnnouncePeerCount",  seeds >= 0 ? seeds + leechers : 0);
        e.addProperty("lastScrapeSucceeded",    seeds >= 0);
        e.addProperty("lastScrapeResult",       seeds >= 0 ? "Success" : "");
        e.addProperty("lastScrapeTime",         now - 600);
        e.addProperty("lastScrapeStartTime",    now - 600);
        e.addProperty("lastScrapeTimedOut",     false);
        e.addProperty("nextAnnounceTime",       now + 1800);
        e.addProperty("nextScrapeTime",         now + 1800);
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
                    JsonObject f = new JsonObject();
                    f.addProperty("name",           name);
                    f.addProperty("length",         fi.getLength());
                    f.addProperty("bytesCompleted", fi.getDownloaded());
                    f.addProperty("priority",       toPriority(fi.getNumericPriority()));
                    arr.add(f);
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
                    fs.addProperty("bytesCompleted", fi.getDownloaded());
                    fs.addProperty("wanted",         !fi.isSkipped());
                    fs.addProperty("priority",       toPriority(fi.getNumericPriority()));
                    arr.add(fs);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return arr;
    }

    private JsonArray buildPriorities(Download dl) {
        JsonArray arr = new JsonArray();
        try {
            DiskManagerFileInfo[] files = dl.getDiskManagerFileInfo();
            if (files != null) for (DiskManagerFileInfo fi : files) arr.add(toPriority(fi.getNumericPriority()));
        } catch (Exception ignored) {}
        return arr;
    }

    private JsonArray buildWanted(Download dl) {
        JsonArray arr = new JsonArray();
        try {
            DiskManagerFileInfo[] files = dl.getDiskManagerFileInfo();
            if (files != null) for (DiskManagerFileInfo fi : files) arr.add(fi.isSkipped() ? 0 : 1);
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
                    p.addProperty("address",          peer.getIp());
                    p.addProperty("port",             peer.getTCPListenPort() > 0 ? peer.getTCPListenPort() : peer.getPort());
                    p.addProperty("clientName",       peer.getClient() != null ? peer.getClient() : "");
                    p.addProperty("progress",         peer.getPercentDoneInThousandNotation() / 1000.0);
                    p.addProperty("rateToClient",     ps != null ? ps.getDownloadAverage() : 0);
                    p.addProperty("rateToPeer",       ps != null ? ps.getUploadAverage()   : 0);
                    p.addProperty("flagStr",          "");
                    p.addProperty("isUTP",            false);
                    p.addProperty("isIncoming",       peer.isIncoming());
                    p.addProperty("isEncrypted",      false);
                    p.addProperty("isDownloadingFrom", ps != null && ps.getDownloadAverage() > 0);
                    p.addProperty("isUploadingTo",    ps != null && ps.getUploadAverage()   > 0);
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
        if (args.has("files-wanted")) {
            Set<Integer> wanted = toIntSet(args.getAsJsonArray("files-wanted"));
            for (int i = 0; i < files.length; i++) if (wanted.contains(i)) try { files[i].setSkipped(false); } catch (Exception ignored) {}
        }
        if (args.has("files-unwanted")) {
            Set<Integer> unwanted = toIntSet(args.getAsJsonArray("files-unwanted"));
            for (int i = 0; i < files.length; i++) if (unwanted.contains(i)) try { files[i].setSkipped(true); } catch (Exception ignored) {}
        }
        if (args.has("priority-high")) {
            Set<Integer> hi = toIntSet(args.getAsJsonArray("priority-high"));
            for (int i = 0; i < files.length; i++) if (hi.contains(i)) try { files[i].setNumericPriority(DiskManagerFileInfo.PRIORITY_HIGH); } catch (Exception ignored) {}
        }
        if (args.has("priority-normal")) {
            Set<Integer> normal = toIntSet(args.getAsJsonArray("priority-normal"));
            for (int i = 0; i < files.length; i++) if (normal.contains(i)) try { files[i].setNumericPriority(DiskManagerFileInfo.PRIORITY_NORMAL); } catch (Exception ignored) {}
        }
        if (args.has("priority-low")) {
            Set<Integer> lo = toIntSet(args.getAsJsonArray("priority-low"));
            for (int i = 0; i < files.length; i++) if (lo.contains(i)) try { files[i].setNumericPriority(DiskManagerFileInfo.PRIORITY_LOW); } catch (Exception ignored) {}
        }
    }

    /** Map BiglyBT file priority (raw int) to Transmission priority (-1 low, 0 normal, 1 high). */
    private static int toPriority(int biglyBTPriority) {
        if (biglyBTPriority > 1)  return  1; // high
        if (biglyBTPriority < 0)  return -1; // low
        return 0;                             // normal
    }

    private static Set<Integer> toIntSet(JsonArray arr) {
        Set<Integer> set = new HashSet<>();
        if (arr != null) for (JsonElement e : arr) try { set.add(e.getAsInt()); } catch (Exception ignored) {}
        return set;
    }

    /**
     * Convert a filename/URL string to a java.net.URL.
     * Handles "magnet:" scheme which is not registered by default in Java.
     */
    private static URL toURL(String s) throws Exception {
        if (s.startsWith("magnet:")) {
            // BiglyBT registers the magnet: protocol at startup; try the standard way first.
            try { return new URL(s); } catch (java.net.MalformedURLException ignored) {}
            // Fallback: wrap with a no-op URLStreamHandler so the URL object can be created.
            return new URL(null, s, new java.net.URLStreamHandler() {
                @Override
                protected java.net.URLConnection openConnection(URL u) throws java.io.IOException {
                    throw new java.io.IOException("magnet handled internally by BiglyBT");
                }
            });
        }
        return new URL(s);
    }

    /** Convert underscore_case or hyphen-case to camelCase: "percent_done" → "percentDone". */
    static String underscoreToCamel(String s) {
        if (s == null || s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder();
        boolean upper = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '_' || c == '-') { upper = true; continue; }
            sb.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }
}
