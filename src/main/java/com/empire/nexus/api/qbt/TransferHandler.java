package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.util.TorrentMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;

/**
 * /api/v2/transfer/* endpoints.
 * <p>
 * GET  info                — aggregate speed + session data (status bar)
 * GET  speedLimitsMode     — alt-speed mode flag (0/1)
 * GET  downloadLimit       — global download limit in bytes/sec
 * GET  uploadLimit         — global upload limit in bytes/sec
 * POST setDownloadLimit    — set global download limit
 * POST setUploadLimit      — set global upload limit
 * POST toggleSpeedLimitsMode — toggle alt-speed mode (no-op)
 */
public class TransferHandler {

    private final PluginInterface pi;

    public TransferHandler(PluginInterface pi) {
        this.pi = pi;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "info" -> info(exchange);
            case "speedLimitsMode" -> HttpUtils.sendText(exchange, "0");
            case "downloadLimit" -> downloadLimit(exchange);
            case "uploadLimit" -> uploadLimit(exchange);
            case "setDownloadLimit" -> setDownloadLimit(exchange);
            case "setUploadLimit" -> setUploadLimit(exchange);
            case "toggleSpeedLimitsMode",
                 "banPeers" -> HttpUtils.sendText(exchange, "Ok.");
            default -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    // ── GET /api/v2/transfer/info ─────────────────────────────────────────────

    private void info(HttpExchange exchange) throws IOException {
        long dlSpeed = 0, ulSpeed = 0, dlData = 0, ulData = 0;
        for (Download dl : pi.getDownloadManager().getDownloads()) {
            if (!TorrentMapper.isUserDownload(dl)) continue;
            DownloadStats s = dl.getStats();
            dlSpeed += s.getDownloadAverage();
            ulSpeed += s.getUploadAverage();
            dlData += s.getDownloaded(false);
            ulData += s.getUploaded(false);
        }

        long dlLimit = globalDownloadLimitBytes();
        long ulLimit = globalUploadLimitBytes();

        String json = String.format("""
                {
                  "connection_status": "connected",
                  "dht_nodes": 0,
                  "dl_info_data": %d,
                  "dl_info_speed": %d,
                  "dl_rate_limit": %d,
                  "up_info_data": %d,
                  "up_info_speed": %d,
                  "up_rate_limit": %d
                }""", dlData, dlSpeed, dlLimit, ulData, ulSpeed, ulLimit);

        HttpUtils.sendJson(exchange, json);
    }

    // ── GET /api/v2/transfer/downloadLimit ────────────────────────────────────

    private void downloadLimit(HttpExchange exchange) throws IOException {
        HttpUtils.sendJson(exchange, String.valueOf(globalDownloadLimitBytes()));
    }

    // ── GET /api/v2/transfer/uploadLimit ──────────────────────────────────────

    private void uploadLimit(HttpExchange exchange) throws IOException {
        HttpUtils.sendJson(exchange, String.valueOf(globalUploadLimitBytes()));
    }

    // ── POST /api/v2/transfer/setDownloadLimit ────────────────────────────────

    private void setDownloadLimit(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        long limit = parseLong(params.getOrDefault("limit", "0"), 0);
        try {
            // BiglyBT stores global limits in KB/s
            pi.getPluginconfig().setCoreLongParameter(
                    PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC,
                    limit > 0 ? limit / 1024L : 0L);
        } catch (Exception ignored) {
        }
        HttpUtils.sendJson(exchange, String.valueOf(limit));
    }

    // ── POST /api/v2/transfer/setUploadLimit ──────────────────────────────────

    private void setUploadLimit(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        long limit = parseLong(params.getOrDefault("limit", "0"), 0);
        try {
            pi.getPluginconfig().setCoreLongParameter(
                    PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC,
                    limit > 0 ? limit / 1024L : 0L);
        } catch (Exception ignored) {
        }
        HttpUtils.sendJson(exchange, String.valueOf(limit));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Returns global download limit in bytes/sec (0 = unlimited).
     */
    private long globalDownloadLimitBytes() {
        try {
            long kb = pi.getPluginconfig().getCoreLongParameter(
                    PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC);
            return kb > 0 ? kb * 1024L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Returns global upload limit in bytes/sec (0 = unlimited).
     */
    private long globalUploadLimitBytes() {
        try {
            long kb = pi.getPluginconfig().getCoreLongParameter(
                    PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC);
            return kb > 0 ? kb * 1024L : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    private static long parseLong(String s, long def) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return def;
        }
    }
}
