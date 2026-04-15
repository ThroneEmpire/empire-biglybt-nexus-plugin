package com.empire.nexus.util;

import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.biglybt.pif.torrent.Torrent;
import com.google.gson.JsonObject;

/**
 * Maps a BiglyBT {@link Download} to the JSON shape qBittorrent returns from
 * /api/v2/torrents/info — covering every field VueTorrent's RawTorrent type uses.
 *
 * BiglyBT Download state constants:
 *   ST_WAITING=1  ST_PREPARING=2  ST_READY=3  ST_DOWNLOADING=4
 *   ST_FINISHING=5  ST_SEEDING=6  ST_STOPPING=7  ST_STOPPED=8
 *   ST_ERROR=9  ST_QUEUED=10
 */
public final class TorrentMapper {

    private TorrentMapper() {}

    public static JsonObject toQbtInfo(Download dl) {
        JsonObject o = new JsonObject();

        Torrent       torrent = dl.getTorrent();
        DownloadStats stats   = dl.getStats();

        String hex      = hashHex(torrent.getHash());
        long   totalSize = torrent.getSize();

        // ── Identity ─────────────────────────────────────────────────────────
        o.addProperty("hash",        hex);
        o.addProperty("infohash_v1", hex);   // v1 torrent — same as hash
        o.addProperty("infohash_v2", "");    // only for hybrid/v2 torrents
        o.addProperty("name",        torrent.getName());
        o.addProperty("magnet_uri",  "");    // TODO: build magnet: URI

        // ── Sizes ────────────────────────────────────────────────────────────
        int  permille  = stats.getCompleted();           // 0–1000
        float progress = permille / 1000f;               // 0.0–1.0
        long  completed = (long)(totalSize * progress);
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

        // BiglyBT getDownloaded/getUploaded(true) = session only
        long dlSession = 0, ulSession = 0;
        try { dlSession = stats.getDownloaded(true); } catch (Exception ignored) {}
        try { ulSession = stats.getUploaded(true);   } catch (Exception ignored) {}

        o.addProperty("dlspeed",           dlSpeed);
        o.addProperty("upspeed",           ulSpeed);
        o.addProperty("downloaded",        dlTotal);
        o.addProperty("uploaded",          ulTotal);
        o.addProperty("downloaded_session", dlSession);
        o.addProperty("uploaded_session",   ulSession);
        o.addProperty("ratio",             safeRatio(dlTotal, ulTotal));

        // ── ETA ───────────────────────────────────────────────────────────────
        long etaSecs = stats.getETASecs();
        o.addProperty("eta", etaSecs == Long.MAX_VALUE || etaSecs < 0 ? -1L : etaSecs);

        // ── Time ──────────────────────────────────────────────────────────────
        // BiglyBT doesn't directly expose seeding/download time in the plugin API.
        // getTimeStarted() returns the wall-clock time (ms) download started.
        long timeStarted = 0;
        try { timeStarted = dl.getStats().getTimeStarted(); } catch (Exception ignored) {}

        long nowSecs = System.currentTimeMillis() / 1000L;
        long addedOn  = timeStarted > 0 ? timeStarted / 1000L : 0L;
        long timeActive = timeStarted > 0 ? nowSecs - addedOn : 0L;

        o.addProperty("added_on",      addedOn);
        o.addProperty("last_activity", nowSecs);
        o.addProperty("time_active",   timeActive);

        // seeding_time: only meaningful once complete
        long seedingTime = 0;
        try { seedingTime = stats.getSecondsOnlySeeding(); } catch (Exception ignored) {}
        o.addProperty("seeding_time", seedingTime);

        o.addProperty("completion_on",
                progress >= 1.0f ? nowSecs : 0L);

        // ── State ────────────────────────────────────────────────────────────
        o.addProperty("state",       toQbtState(dl));
        o.addProperty("force_start", false);
        o.addProperty("super_seeding", false);

        // ── Peers / seeds ─────────────────────────────────────────────────────
        // PeerManager is null until the torrent is active; guard carefully.
        int seeds = 0, peers = 0;
        try {
            com.biglybt.pif.peers.PeerManager pm = dl.getPeerManager();
            if (pm != null) {
                com.biglybt.pif.peers.PeerManagerStats pms = pm.getStats();
                seeds = pms.getConnectedSeeds();
                peers = pms.getConnectedLeechers();
            }
        } catch (Exception ignored) {}
        o.addProperty("num_seeds",    seeds);
        o.addProperty("num_leechs",   peers);
        o.addProperty("num_complete",   -1);
        o.addProperty("num_incomplete", -1);

        // ── Tracker ───────────────────────────────────────────────────────────
        String trackerUrl = "";
        int    trackerCount = 0;
        try {
            var url = torrent.getAnnounceURL();
            if (url != null) { trackerUrl = url.toString(); trackerCount = 1; }
        } catch (Exception ignored) {}
        try {
            com.biglybt.pif.torrent.TorrentAnnounceURLListSet[] sets =
                    torrent.getAnnounceURLList().getSets();
            if (sets != null && sets.length > 0) trackerCount = sets.length;
        } catch (Exception ignored) {}
        o.addProperty("tracker",        trackerUrl);
        o.addProperty("trackers_count", trackerCount);

        // ── Paths ──────────────────────────────────────────────────────────────
        String savePath = "", rootPath = "", contentPath = "";
        try {
            var diskFiles = dl.getDiskManagerFileInfo();
            if (diskFiles != null && diskFiles.length > 0) {
                var firstFile = diskFiles[0].getFile(false);
                if (firstFile != null) {
                    // For a multi-file torrent the parent of the first file's
                    // parent is the save dir.  For a single-file torrent its
                    // parent is the save dir.
                    java.io.File parent = firstFile.getParentFile();
                    if (parent != null) {
                        savePath    = parent.getAbsolutePath();
                        contentPath = (diskFiles.length == 1)
                                ? firstFile.getAbsolutePath()
                                : parent.getAbsolutePath();
                        rootPath = contentPath;
                    }
                }
            }
        } catch (Exception ignored) {}
        o.addProperty("save_path",     savePath);
        o.addProperty("content_path",  contentPath);
        o.addProperty("root_path",     rootPath);
        o.addProperty("download_path", savePath);

        // ── Category / tags ───────────────────────────────────────────────────
        // TODO: wire up via TorrentAttribute once category support is added.
        // The TorrentAttribute for category must be looked up via
        // pi.getTorrentManager().getAttribute("Category"), which requires
        // passing PluginInterface into this method.
        o.addProperty("category", "");
        o.addProperty("tags",     "");

        // ── Torrent metadata ──────────────────────────────────────────────────
        String comment = "";
        try { comment = torrent.getComment(); } catch (Exception ignored) {}
        o.addProperty("comment",      comment);

        boolean isPrivate = false;
        try { isPrivate = torrent.isPrivate(); } catch (Exception ignored) {}
        o.addProperty("private",      isPrivate);

        o.addProperty("has_metadata", true);    // regular torrents always have it
        o.addProperty("reannounce",   0);        // seconds until next reannounce

        // ── Limits / seeding rules ────────────────────────────────────────────
        o.addProperty("dl_limit",                    -1);
        o.addProperty("up_limit",                    -1);
        o.addProperty("max_ratio",                   -1.0);
        o.addProperty("max_seeding_time",            -1);
        o.addProperty("ratio_limit",                 -1.0);
        o.addProperty("seeding_time_limit",          -1);
        o.addProperty("inactive_seeding_time_limit", -1);
        o.addProperty("max_inactive_seeding_time",   -1);

        // ── Queue / misc flags ────────────────────────────────────────────────
        o.addProperty("priority",         0);
        o.addProperty("seq_dl",           false);
        o.addProperty("f_l_piece_prio",   false);
        o.addProperty("auto_tmm",         false);
        o.addProperty("availability",     progress);
        o.addProperty("popularity",       0.0);

        return o;
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
        switch (dl.getState()) {
            case Download.ST_DOWNLOADING: return paused ? "pausedDL" : "downloading";
            case Download.ST_SEEDING:     return paused ? "pausedUP" : "uploading";
            case Download.ST_STOPPED:     return paused ? "pausedDL" : "stalledDL";
            case Download.ST_QUEUED:      return "queuedDL";
            case Download.ST_WAITING:     return "queuedDL";
            case Download.ST_PREPARING:   return "checkingDL";
            // ST_FINISHING does not exist in the plugin API — ST_PREPARING covers it
            case Download.ST_ERROR:       return "error";
            default:                      return "unknown";
        }
    }

    private static double safeRatio(long downloaded, long uploaded) {
        if (downloaded <= 0) return 0.0;
        return (double) uploaded / downloaded;
    }
}
