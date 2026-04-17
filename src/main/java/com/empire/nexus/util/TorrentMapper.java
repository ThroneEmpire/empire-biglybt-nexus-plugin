package com.empire.nexus.util;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadScrapeResult;
import com.biglybt.pif.download.DownloadStats;
import com.biglybt.pif.peers.Peer;
import com.biglybt.pif.peers.PeerManager;
import com.biglybt.pif.peers.PeerStats;
import com.biglybt.pif.torrent.Torrent;
import com.biglybt.pifimpl.local.PluginCoreUtils;
import com.google.gson.JsonObject;

/**
 * Maps a BiglyBT {@link Download} to the JSON shape qBittorrent returns from
 * /api/v2/torrents/info — covering fields used by qBittorrent-compatible web UIs.
 *
 * Call {@link #init(PluginInterface)} once at plugin startup before any mapping.
 *
 * BiglyBT Download state constants:
 *   ST_WAITING=1  ST_PREPARING=2  ST_READY=3  ST_DOWNLOADING=4
 *   ST_SEEDING=6  ST_STOPPING=7  ST_STOPPED=8  ST_ERROR=9  ST_QUEUED=10
 */
public final class TorrentMapper {

    private TorrentMapper() {}

    public static void init(PluginInterface pi) {}

    private static com.biglybt.core.tag.TagType manualTagType() {
        try {
            return com.biglybt.core.tag.TagManagerFactory.getTagManager()
                    .getTagType(com.biglybt.core.tag.TagType.TT_DOWNLOAD_MANUAL);
        } catch (Exception ignored) { return null; }
    }

    private static com.biglybt.core.download.DownloadManager unwrap(Download dl) {
        try { return (com.biglybt.core.download.DownloadManager) PluginCoreUtils.unwrap(dl); }
        catch (Exception ignored) { return null; }
    }

    public static java.util.Set<String> getNativeTags(Download dl) {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        com.biglybt.core.download.DownloadManager dm = unwrap(dl);
        if (dm == null) return result;
        try {
            java.util.List<com.biglybt.core.tag.Tag> tags =
                    com.biglybt.core.tag.TagManagerFactory.getTagManager()
                            .getTagsForTaggable(com.biglybt.core.tag.TagType.TT_DOWNLOAD_MANUAL, dm);
            if (tags != null) for (com.biglybt.core.tag.Tag tag : tags) result.add(tag.getTagName(true));
        } catch (Exception ignored) {}
        return result;
    }

    public static java.util.Set<String> getAllUserTags() {
        java.util.Set<String> result = new java.util.LinkedHashSet<>();
        com.biglybt.core.tag.TagType tt = manualTagType();
        if (tt != null) for (com.biglybt.core.tag.Tag tag : tt.getTags()) result.add(tag.getTagName(true));
        return result;
    }

    public static void addTagsToDownload(Download dl, java.util.Collection<String> tagNames) {
        com.biglybt.core.download.DownloadManager dm = unwrap(dl);
        com.biglybt.core.tag.TagType tt = manualTagType();
        if (dm == null || tt == null) return;
        for (String name : tagNames) {
            if (name == null || name.isEmpty()) continue;
            try {
                com.biglybt.core.tag.Tag tag = tt.getTag(name, true);
                if (tag == null) tag = tt.createTag(name, true);
                if (tag != null && !tag.hasTaggable(dm)) tag.addTaggable(dm);
            } catch (Exception ignored) {}
        }
    }

    public static void removeTagsFromDownload(Download dl, java.util.Collection<String> tagNames) {
        com.biglybt.core.download.DownloadManager dm = unwrap(dl);
        com.biglybt.core.tag.TagType tt = manualTagType();
        if (dm == null || tt == null) return;
        for (String name : tagNames) {
            if (name == null || name.isEmpty()) continue;
            try {
                com.biglybt.core.tag.Tag tag = tt.getTag(name, true);
                if (tag != null && tag.hasTaggable(dm)) tag.removeTaggable(dm);
            } catch (Exception ignored) {}
        }
    }

    /**
     * Returns true if {@code name} is a user-defined category in BiglyBT.
     * Uses the internal CategoryManager as the authoritative source; the string
     * prefix check is a fallback for when the internal API is unavailable.
     */
    public static boolean isUserCategory(String name) {
        if (name == null || name.isEmpty()) return false;
        if (name.startsWith("Categories.")) return false;
        try {
            com.biglybt.core.category.Category cat =
                    com.biglybt.core.category.CategoryManager.getCategory(name);
            return cat != null && cat.getType() == com.biglybt.core.category.Category.TYPE_USER;
        } catch (Exception ignored) {}
        return true; // if internal API unavailable, trust non-prefixed name
    }

    public static com.biglybt.core.category.Category[] getUserCategories() {
        try {
            com.biglybt.core.category.Category[] all =
                    com.biglybt.core.category.CategoryManager.getCategories();
            if (all == null) return new com.biglybt.core.category.Category[0];
            java.util.List<com.biglybt.core.category.Category> result = new java.util.ArrayList<>(all.length);
            for (com.biglybt.core.category.Category c : all)
                if (c.getType() == com.biglybt.core.category.Category.TYPE_USER) result.add(c);
            return result.toArray(new com.biglybt.core.category.Category[0]);
        } catch (Exception ignored) {
            return new com.biglybt.core.category.Category[0];
        }
    }

    /** Unwrap plugin Download to internal DownloadManagerState for deeper access. Returns null on failure. */
    public static com.biglybt.core.download.DownloadManagerState getDownloadState(Download dl) {
        try {
            com.biglybt.core.download.DownloadManager dm =
                (com.biglybt.core.download.DownloadManager) PluginCoreUtils.unwrap(dl);
            return dm == null ? null : dm.getDownloadState();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Returns true if this download should be visible in the UI.
     * BiglyBT marks its internal plugin-update downloads with FLAG_LOW_NOISE;
     * those should never be shown to the user.
     */
    public static boolean isUserDownload(Download dl) {
        if (dl.getTorrent() == null) return false;
        try { if (dl.getFlag(Download.FLAG_LOW_NOISE)) return false; } catch (Exception ignored) {}
        return true;
    }

    // ── torrent info ──────────────────────────────────────────────────────────

    public static JsonObject toQbtInfo(Download dl) {
        JsonObject o = new JsonObject();

        Torrent       torrent = dl.getTorrent();
        DownloadStats stats   = dl.getStats();

        String hex      = hashHex(torrent.getHash());
        long   totalSize = torrent.getSize();

        // ── Identity ─────────────────────────────────────────────────────────
        o.addProperty("hash",        hex);
        o.addProperty("infohash_v1", hex);
        o.addProperty("infohash_v2", "");
        o.addProperty("name",        torrent.getName());

        // Magnet URI (minimal, tracker appended if available)
        String magnetUri = "magnet:?xt=urn:btih:" + hex + "&dn=" + urlEncode(torrent.getName());
        try {
            java.net.URL announceUrl = torrent.getAnnounceURL();
            if (announceUrl != null) magnetUri += "&tr=" + urlEncode(announceUrl.toString());
        } catch (Exception ignored) {}
        o.addProperty("magnet_uri", magnetUri);

        // ── Sizes ────────────────────────────────────────────────────────────
        int  permille  = stats.getCompleted();
        float progress = permille / 1000f;
        long  completed  = (long)(totalSize * progress);
        long  amountLeft = Math.max(0L, totalSize - completed);

        o.addProperty("size",        totalSize);
        o.addProperty("total_size",  totalSize);
        o.addProperty("progress",    progress);
        o.addProperty("completed",   completed);
        o.addProperty("amount_left", amountLeft);

        // ── Speeds / transfer ─────────────────────────────────────────────────
        long dlSpeed = stats.getDownloadAverage();
        long ulSpeed = stats.getUploadAverage();
        long dlTotal = stats.getDownloaded(false);
        long ulTotal = stats.getUploaded(false);

        long dlSession = 0, ulSession = 0;
        try { dlSession = stats.getDownloaded(true); } catch (Exception ignored) {}
        try { ulSession = stats.getUploaded(true);   } catch (Exception ignored) {}

        o.addProperty("dlspeed",             dlSpeed);
        o.addProperty("upspeed",             ulSpeed);
        o.addProperty("downloaded",          dlTotal);
        o.addProperty("uploaded",            ulTotal);
        o.addProperty("downloaded_session",  dlSession);
        o.addProperty("uploaded_session",    ulSession);
        o.addProperty("ratio",               safeRatio(dlTotal, ulTotal));

        // ── ETA ───────────────────────────────────────────────────────────────
        long etaSecs = stats.getETASecs();
        o.addProperty("eta", etaSecs == Long.MAX_VALUE || etaSecs < 0 ? -1L : etaSecs);

        // ── Time ──────────────────────────────────────────────────────────────
        long timeStarted = 0;
        try { timeStarted = stats.getTimeStarted(); } catch (Exception ignored) {}

        long nowSecs  = System.currentTimeMillis() / 1000L;

        // Use internal API for real addition time; fall back to session start time
        long addedOn = 0;
        com.biglybt.core.download.DownloadManagerState dms = getDownloadState(dl);
        if (dms != null) {
            long t = dms.getLongParameter(com.biglybt.core.download.DownloadManagerState.PARAM_DOWNLOAD_ADDED_TIME);
            if (t > 0) addedOn = t / 1000L;
        }
        if (addedOn <= 0 && timeStarted > 0) addedOn = timeStarted / 1000L;

        long timeActive = addedOn > 0 ? nowSecs - addedOn : 0L;

        o.addProperty("added_on",      addedOn);
        o.addProperty("last_activity", nowSecs);
        o.addProperty("time_active",   timeActive);

        long seedingTime = 0;
        try { seedingTime = stats.getSecondsOnlySeeding(); } catch (Exception ignored) {}
        o.addProperty("seeding_time",   seedingTime);
        // Use internal API for real completion time; fall back to seeding start time
        long completionOn = 0;
        if (dms != null) {
            long t = dms.getLongParameter(com.biglybt.core.download.DownloadManagerState.PARAM_DOWNLOAD_COMPLETED_TIME);
            if (t > 0) completionOn = t / 1000L;
        }
        if (completionOn <= 0) {
            try {
                long st = stats.getTimeStartedSeeding();
                if (st > 0) completionOn = st / 1000L;
            } catch (Exception ignored) {}
        }
        o.addProperty("completion_on", completionOn);

        // ── State ────────────────────────────────────────────────────────────
        o.addProperty("state",         toQbtState(dl));
        o.addProperty("force_start",   dl.isForceStart());
        o.addProperty("super_seeding", false);

        // ── Peers / seeds (connected) ─────────────────────────────────────────
        int seeds = 0, peers = 0;
        try {
            PeerManager pm = dl.getPeerManager();
            if (pm != null) {
                seeds = pm.getStats().getConnectedSeeds();
                peers = pm.getStats().getConnectedLeechers();
            }
        } catch (Exception ignored) {}
        o.addProperty("num_seeds",  seeds);
        o.addProperty("num_leechs", peers);

        // Global counts from tracker scrape
        int numComplete = -1, numIncomplete = -1;
        try {
            DownloadScrapeResult scrape = dl.getAggregatedScrapeResult(false);
            if (scrape != null && scrape.getResponseType() == DownloadScrapeResult.RT_SUCCESS) {
                numComplete   = scrape.getSeedCount();
                numIncomplete = scrape.getNonSeedCount();
            }
        } catch (Exception ignored) {}
        o.addProperty("num_complete",   numComplete);
        o.addProperty("num_incomplete", numIncomplete);

        // ── Tracker ───────────────────────────────────────────────────────────
        String trackerUrl  = "";
        int    trackerCount = 0;
        try {
            java.net.URL url = torrent.getAnnounceURL();
            if (url != null) { trackerUrl = url.toString(); trackerCount = 1; }
        } catch (Exception ignored) {}
        try {
            com.biglybt.pif.torrent.TorrentAnnounceURLListSet[] sets =
                    torrent.getAnnounceURLList().getSets();
            if (sets != null && sets.length > 0) trackerCount = sets.length;
        } catch (Exception ignored) {}
        o.addProperty("tracker",        trackerUrl);
        o.addProperty("trackers_count", trackerCount);

        // Tracker message from last announce
        String trackerMsg = "";
        try {
            var ann = dl.getLastAnnounceResult();
            if (ann != null) trackerMsg = ann.getError() != null ? ann.getError() : "";
        } catch (Exception ignored) {}
        o.addProperty("tracker_message", trackerMsg != null ? trackerMsg : "");

        // ── Paths ──────────────────────────────────────────────────────────────
        String savePath = "", contentPath = "";
        try {
            String sp = stats.getDownloadDirectory();
            if (sp != null) savePath = sp;
        } catch (Exception ignored) {}
        try {
            var diskFiles = dl.getDiskManagerFileInfo();
            if (diskFiles != null && diskFiles.length > 0) {
                var firstFile = diskFiles[0].getFile(false);
                if (firstFile != null) {
                    contentPath = (diskFiles.length == 1)
                            ? firstFile.getAbsolutePath()
                            : firstFile.getParentFile().getAbsolutePath();
                }
            }
        } catch (Exception ignored) {}
        o.addProperty("save_path",     savePath);
        o.addProperty("content_path",  contentPath);
        o.addProperty("root_path",     contentPath);
        o.addProperty("download_path", savePath);

        // ── Category ─────────────────────────────────────────────────────────
        String category = "";
        try { category = dl.getCategoryName(); } catch (Exception ignored) {}
        if (!isUserCategory(category)) category = "";
        o.addProperty("category", category);

        // ── Tags ──────────────────────────────────────────────────────────────
        o.addProperty("tags", String.join(",", getNativeTags(dl)));

        // ── Torrent metadata ──────────────────────────────────────────────────
        String comment = "";
        try { comment = torrent.getComment(); } catch (Exception ignored) {}
        o.addProperty("comment", comment);

        String createdBy = "";
        try { createdBy = torrent.getCreatedBy(); } catch (Exception ignored) {}
        o.addProperty("created_by", createdBy != null ? createdBy : "");

        boolean isPrivate = false;
        try { isPrivate = torrent.isPrivate(); } catch (Exception ignored) {}
        o.addProperty("private",      isPrivate);
        o.addProperty("has_metadata", true);
        o.addProperty("reannounce",   0);

        // ── Limits / seeding rules ────────────────────────────────────────────
        int dlLimit = 0, ulLimit = 0;
        try { dlLimit = dl.getDownloadRateLimitBytesPerSecond(); } catch (Exception ignored) {}
        try { ulLimit = dl.getUploadRateLimitBytesPerSecond();   } catch (Exception ignored) {}
        o.addProperty("dl_limit",                    dlLimit > 0 ? dlLimit : -1);
        o.addProperty("up_limit",                    ulLimit > 0 ? ulLimit : -1);
        o.addProperty("max_ratio",                   -1.0);
        o.addProperty("max_seeding_time",            -1);
        o.addProperty("ratio_limit",                 -1.0);
        o.addProperty("seeding_time_limit",          -1);
        o.addProperty("inactive_seeding_time_limit", -1);
        o.addProperty("max_inactive_seeding_time",   -1);

        // ── Queue / misc flags ────────────────────────────────────────────────
        o.addProperty("priority",       dl.getPosition());
        o.addProperty("seq_dl",         dl.getFlag(Download.FLAG_SEQUENTIAL_DOWNLOAD));
        o.addProperty("f_l_piece_prio", false);
        o.addProperty("auto_tmm",       false);
        o.addProperty("availability",   progress);
        o.addProperty("popularity",     0.0);

        return o;
    }

    // ── Peers ─────────────────────────────────────────────────────────────────

    /**
     * Builds the "peers" object used by both /api/v2/torrents/peers and
     * /api/v2/sync/torrentPeers.  Keys are "ip:port"; values are per-peer fields.
     */
    public static JsonObject buildPeersJson(Download dl) {
        JsonObject peers = new JsonObject();
        try {
            PeerManager pm = dl.getPeerManager();
            if (pm == null) return peers;
            Peer[] peerArr = pm.getPeers();
            if (peerArr == null) return peers;

            for (Peer peer : peerArr) {
                try {
                    String ip   = peer.getIp();
                    int    port = peer.getTCPListenPort();
                    if (port <= 0) port = peer.getPort();
                    String key = ip + ":" + port;

                    PeerStats ps  = peer.getStats();
                    int  dlSpeed  = ps != null ? ps.getDownloadAverage() : 0;
                    int  ulSpeed  = ps != null ? ps.getUploadAverage()   : 0;
                    long received = ps != null ? ps.getTotalReceived()   : 0;
                    long sent     = ps != null ? ps.getTotalSent()       : 0;

                    // Progress: compute from available-pieces bitfield; fall back to API value
                    double progress;
                    try {
                        boolean[] available = peer.getAvailable();
                        if (available != null && available.length > 0) {
                            int count = 0;
                            for (boolean b : available) if (b) count++;
                            progress = (double) count / available.length;
                        } else {
                            progress = peer.getPercentDoneInThousandNotation() / 1000.0;
                        }
                    } catch (Exception e) {
                        progress = peer.getPercentDoneInThousandNotation() / 1000.0;
                    }

                    // qBittorrent-style flags (space-separated single chars).
                    // BiglyBT's plugin-API choked/interested booleans are not always
                    // populated by the time we read them, so we use actual transfer speeds
                    // as the primary indicator for the active-transfer flags D and U.
                    // State booleans are used as a fallback for the non-active variants.
                    java.util.StringJoiner flags = new java.util.StringJoiner(" ");

                    if (dlSpeed > 0) {
                        flags.add("D");                          // actively downloading
                    } else {
                        try {
                            if (peer.isInterested() && peer.isChoked()) flags.add("d"); // want but choked
                        } catch (Exception ignored) {}
                    }
                    if (ulSpeed > 0) {
                        flags.add("U");                          // actively uploading
                    } else {
                        try {
                            if (peer.isInteresting() && peer.isChoking()) flags.add("u"); // they want but we choke
                        } catch (Exception ignored) {}
                    }
                    try { if (peer.isOptimisticUnchoke()) flags.add("O"); } catch (Exception ignored) {}
                    try { if (peer.isSnubbed())  flags.add("S"); } catch (Exception ignored) {}
                    try { if (peer.isIncoming()) flags.add("I"); } catch (Exception ignored) {}

                    // flags_desc: human-readable tooltip
                    String flagsStr = flags.toString();
                    String flagsDesc = buildFlagsDesc(flagsStr);

                    // Country lookup via BiglyBT's built-in GeoIP (LocationProvider API).
                    // Returns null if no provider is registered or IP is unrecognised.
                    String countryCode = "", countryName = "";
                    try {
                        String[] cc = com.biglybt.core.peer.util.PeerUtils.getCountryDetails(peer);
                        if (cc != null) {
                            String code = cc.length > 0 ? cc[0] : null;
                            // CC_UNKNOWN is typically "??" — skip those
                            if (code != null && !code.isEmpty()
                                    && !code.equals(com.biglybt.core.peer.util.PeerUtils.CC_UNKNOWN)) {
                                countryCode = code;
                            }
                            if (cc.length > 1 && cc[1] != null) {
                                countryName = cc[1];
                            }
                        }
                    } catch (Exception ignored) {}

                    JsonObject p = new JsonObject();
                    p.addProperty("ip",             ip);
                    p.addProperty("port",           port);
                    p.addProperty("client",         peer.getClient() != null ? peer.getClient() : "");
                    p.addProperty("progress",       progress);
                    p.addProperty("dl_speed",       dlSpeed);
                    p.addProperty("up_speed",       ulSpeed);
                    p.addProperty("downloaded",     received);
                    p.addProperty("uploaded",       sent);
                    p.addProperty("flags",          flagsStr);
                    p.addProperty("flags_desc",     flagsDesc);
                    p.addProperty("connection",     "BT");
                    p.addProperty("country",        countryName);
                    p.addProperty("country_code",   countryCode);
                    p.addProperty("files",          "");
                    p.addProperty("peer_id_client", "");
                    p.addProperty("relevance",      0.0);
                    peers.add(key, p);
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
        return peers;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert a 20-byte SHA-1 hash to a 40-char lowercase hex string. */
    public static String hashHex(byte[] hash) {
        if (hash == null) return "";
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    private static String toQbtState(Download dl) {
        boolean paused = dl.isPaused();
        long dlSpeed = 0, ulSpeed = 0;
        try { dlSpeed = dl.getStats().getDownloadAverage(); } catch (Exception ignored) {}
        try { ulSpeed = dl.getStats().getUploadAverage();   } catch (Exception ignored) {}
        boolean complete = false;
        try { complete = dl.getStats().getCompleted() >= 1000; } catch (Exception ignored) {}

        switch (dl.getState()) {
            case Download.ST_DOWNLOADING: return paused ? "pausedDL"  : (dlSpeed > 0 ? "downloading" : "stalledDL");
            case Download.ST_SEEDING:     return paused ? "pausedUP"  : (ulSpeed > 0 ? "uploading"   : "stalledUP");
            case Download.ST_STOPPED:     return paused ? (complete ? "pausedUP" : "pausedDL")
                                                        : (complete ? "stalledUP" : "stalledDL");
            case Download.ST_QUEUED:
            case Download.ST_WAITING:     return complete ? "queuedUP" : "queuedDL";
            case Download.ST_READY:       return complete ? "queuedUP" : "queuedDL";
            case Download.ST_PREPARING:   return "checkingDL";
            case Download.ST_ERROR:       return "error";
            default:                      return "unknown";
        }
    }

    private static double safeRatio(long downloaded, long uploaded) {
        if (downloaded <= 0) return 0.0;
        return (double) uploaded / downloaded;
    }

    private static String buildFlagsDesc(String flags) {
        if (flags.isEmpty()) return "";
        StringBuilder desc = new StringBuilder();
        for (String f : flags.split(" ")) {
            switch (f) {
                case "D" -> desc.append("D = Downloading; ");
                case "d" -> desc.append("d = Interested, choked; ");
                case "U" -> desc.append("U = Uploading; ");
                case "u" -> desc.append("u = Peer interested, we choking; ");
                case "K" -> desc.append("K = Unchoked, not interested; ");
                case "O" -> desc.append("O = Optimistic unchoke; ");
                case "S" -> desc.append("S = Snubbed; ");
                case "I" -> desc.append("I = Incoming; ");
            }
        }
        return desc.toString().trim();
    }

    private static String urlEncode(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20"); }
        catch (Exception e) { return s; }
    }
}
