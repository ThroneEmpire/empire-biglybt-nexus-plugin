package com.empire.nexus.api.transmission;

import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.empire.nexus.util.TorrentMapper;
import com.google.gson.JsonObject;

/**
 * Implements Transmission RPC session-* methods:
 *   session-get    — return daemon preferences
 *   session-set    — update daemon preferences
 *   session-stats  — aggregate speed and torrent counts
 */
class TrSessionMethods {

    private final PluginInterface pi;

    TrSessionMethods(PluginInterface pi) {
        this.pi = pi;
    }

    // ── session-get ───────────────────────────────────────────────────────────

    JsonObject get(JsonObject args) {
        PluginConfig pc = pi.getPluginconfig();
        JsonObject a = new JsonObject();

        // Identity — UI parses "VERSION (HEX_CHECKSUM)" with regex /^(.*)\s\(([\da-f]+)\)/
        a.addProperty("version",             "4.0.6 (nexus00)");
        a.addProperty("rpc-version",         17);
        a.addProperty("rpc-version-minimum", 14);
        a.addProperty("session-id",          TransmissionRouter.SESSION_ID);

        // Download directory + free space
        String downloadDir = pi.getPluginconfig()
                .getPluginStringParameter("nexus.default.savepath", "");
        if (downloadDir.isEmpty()) {
            try {
                downloadDir = pc.getCoreStringParameter(
                        PluginConfig.CORE_PARAM_STRING_DEFAULT_SAVE_PATH);
            } catch (Exception ignored) {}
        }
        if (downloadDir == null || downloadDir.isEmpty())
            downloadDir = System.getProperty("user.home");
        a.addProperty("download-dir", downloadDir);
        try {
            a.addProperty("download-dir-free-space",
                    new java.io.File(downloadDir).getFreeSpace());
        } catch (Exception ignored) {
            a.addProperty("download-dir-free-space", 0L);
        }

        // Speed limits (BiglyBT stores in KB/s)
        long dlKB = 0, ulKB = 0;
        try { dlKB = pc.getCoreLongParameter(PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC); } catch (Exception ignored) {}
        try { ulKB = pc.getCoreLongParameter(PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC);   } catch (Exception ignored) {}
        a.addProperty("speed-limit-down",         (int) dlKB);
        a.addProperty("speed-limit-down-enabled",  dlKB > 0);
        a.addProperty("speed-limit-up",           (int) ulKB);
        a.addProperty("speed-limit-up-enabled",    ulKB > 0);

        // Alt-speed stubs (BiglyBT doesn't expose these directly)
        a.addProperty("alt-speed-down",          50);
        a.addProperty("alt-speed-up",            50);
        a.addProperty("alt-speed-enabled",       false);
        a.addProperty("alt-speed-time-enabled",  false);
        a.addProperty("alt-speed-time-begin",    0);
        a.addProperty("alt-speed-time-end",      0);
        a.addProperty("alt-speed-time-day",      0);

        // Peer limits
        int maxGlobal = 200, maxPerTorrent = 50;
        try { maxGlobal     = pc.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_MAX_CONNECTIONS_GLOBAL);      } catch (Exception ignored) {}
        try { maxPerTorrent = pc.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_MAX_CONNECTIONS_PER_TORRENT); } catch (Exception ignored) {}
        a.addProperty("peer-limit-global",      maxGlobal);
        a.addProperty("peer-limit-per-torrent", maxPerTorrent);

        // Peer port
        int port = 6881;
        try { port = pc.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_TCP_PORT); } catch (Exception ignored) {}
        a.addProperty("peer-port",                 port);
        a.addProperty("peer-port-random-on-start", false);
        a.addProperty("port-forwarding-enabled",   true);

        // Protocol features
        a.addProperty("dht-enabled",   true);
        a.addProperty("pex-enabled",   true);
        a.addProperty("utp-enabled",   true);
        a.addProperty("lpd-enabled",   false);
        a.addProperty("encryption",    "preferred");

        // Blocklist
        boolean blocklistEnabled = false;
        long    blocklistSize    = 0;
        try {
            com.biglybt.pif.ipfilter.IPFilter ipf = pi.getIPFilter();
            if (ipf != null) {
                blocklistEnabled = ipf.isEnabled();
                blocklistSize    = ipf.getNumberOfRanges();
            }
        } catch (Exception ignored) {}
        a.addProperty("blocklist-enabled", blocklistEnabled);
        a.addProperty("blocklist-size",    blocklistSize);
        a.addProperty("blocklist-url",     "");

        // Queuing / seeding defaults
        a.addProperty("seed-ratio-limit",            0.0);
        a.addProperty("seed-ratio-limited",          false);
        a.addProperty("seed-queue-size",             5);
        a.addProperty("seed-queue-enabled",          false);
        a.addProperty("download-queue-size",         5);
        a.addProperty("download-queue-enabled",      false);
        a.addProperty("queue-stalled-enabled",       false);
        a.addProperty("queue-stalled-minutes",       30);
        a.addProperty("idle-seeding-limit",          30);
        a.addProperty("idle-seeding-limit-enabled",  false);
        a.addProperty("start-added-torrents",        true);
        a.addProperty("trash-original-torrent-files", false);
        a.addProperty("rename-partial-files",        true);

        // Paths
        a.addProperty("incomplete-dir",          downloadDir);
        a.addProperty("incomplete-dir-enabled",  false);
        a.addProperty("watch-dir",               "");
        a.addProperty("watch-dir-enabled",       false);

        // Misc
        a.addProperty("cache-size-mb",                        4);
        a.addProperty("script-torrent-done-enabled",          false);
        a.addProperty("script-torrent-done-filename",         "");
        a.addProperty("script-torrent-done-seeding-enabled",  false);
        a.addProperty("script-torrent-done-seeding-filename", "");
        a.addProperty("default-trackers",                     "");

        return TransmissionRouter.success(a);
    }

    // ── session-set ───────────────────────────────────────────────────────────

    JsonObject set(JsonObject args) {
        PluginConfig pc = pi.getPluginconfig();
        try {
            // Web UI sends snake_case; also accept hyphen-case for CLI clients
            String dlKey = args.has("speed_limit_down") ? "speed_limit_down" : "speed-limit-down";
            String ulKey = args.has("speed_limit_up")   ? "speed_limit_up"   : "speed-limit-up";
            if (args.has(dlKey))
                pc.setCoreLongParameter(
                        PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC,
                        args.get(dlKey).getAsLong());
            if (args.has(ulKey))
                pc.setCoreLongParameter(
                        PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC,
                        args.get(ulKey).getAsLong());
        } catch (Exception ignored) {}
        return TransmissionRouter.success(new JsonObject());
    }

    // ── session-stats ─────────────────────────────────────────────────────────

    JsonObject stats() {
        Download[] downloads = pi.getDownloadManager().getDownloads();
        int  active = 0, paused = 0, total = 0;
        long dlSpeed = 0, ulSpeed = 0, dlTotal = 0, ulTotal = 0;

        for (Download dl : downloads) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            total++;
            int state = dl.getState();
            if (state == Download.ST_DOWNLOADING || state == Download.ST_SEEDING) active++;
            else paused++;
            DownloadStats s = dl.getStats();
            dlSpeed += s.getDownloadAverage();
            ulSpeed += s.getUploadAverage();
            dlTotal += s.getDownloaded(false);
            ulTotal += s.getUploaded(false);
        }

        long allDl = dlTotal, allUl = ulTotal;
        try { allDl = pi.getDownloadManager().getStats().getOverallDataBytesReceived(); } catch (Exception ignored) {}
        try { allUl = pi.getDownloadManager().getStats().getOverallDataBytesSent();     } catch (Exception ignored) {}

        JsonObject cumulative = new JsonObject();
        cumulative.addProperty("uploadedBytes",   allUl);
        cumulative.addProperty("downloadedBytes", allDl);
        cumulative.addProperty("filesAdded",      total);
        cumulative.addProperty("sessionCount",    1);
        cumulative.addProperty("secondsActive",   0L);

        JsonObject current = new JsonObject();
        current.addProperty("uploadedBytes",   ulTotal);
        current.addProperty("downloadedBytes", dlTotal);
        current.addProperty("filesAdded",      total);
        current.addProperty("sessionCount",    1);
        current.addProperty("secondsActive",   0L);

        JsonObject a = new JsonObject();
        a.addProperty("activeTorrentCount", active);
        a.addProperty("pausedTorrentCount", paused);
        a.addProperty("torrentCount",       total);
        a.addProperty("downloadSpeed",      dlSpeed);
        a.addProperty("uploadSpeed",        ulSpeed);
        a.add("cumulative-stats",           cumulative);
        a.add("current-stats",              current);

        return TransmissionRouter.success(a);
    }
}
