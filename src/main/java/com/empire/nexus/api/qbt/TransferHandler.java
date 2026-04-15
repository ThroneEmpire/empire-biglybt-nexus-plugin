package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.empire.nexus.http.HttpUtils;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * GET /api/v2/transfer/info
 *
 * Returns aggregate speed and session transfer data — the bottom status bar
 * in VueTorrent pulls from this endpoint.
 */
public class TransferHandler {

    private final PluginInterface pi;

    public TransferHandler(PluginInterface pi) {
        this.pi = pi;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "info"            -> info(exchange);
            case "speedLimitsMode" -> HttpUtils.sendText(exchange, "0");
            case "downloadLimit"   -> HttpUtils.sendText(exchange, "0");
            case "uploadLimit"     -> HttpUtils.sendText(exchange, "0");
            case "setDownloadLimit",
                 "setUploadLimit",
                 "toggleSpeedLimitsMode" -> HttpUtils.sendText(exchange, "Ok.");
            default                -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    private void info(HttpExchange exchange) throws IOException {
        long dlSpeed = 0, ulSpeed = 0, dlData = 0, ulData = 0;

        for (Download dl : pi.getDownloadManager().getDownloads()) {
            DownloadStats s = dl.getStats();
            dlSpeed += s.getDownloadAverage();
            ulSpeed += s.getUploadAverage();
            dlData  += s.getDownloaded(false);
            ulData  += s.getUploaded(false);
        }

        // connection_status: "connected" | "firewalled" | "disconnected"
        String json = String.format("""
                {
                  "connection_status": "connected",
                  "dht_nodes": 0,
                  "dl_info_data": %d,
                  "dl_info_speed": %d,
                  "dl_rate_limit": 0,
                  "up_info_data": %d,
                  "up_info_speed": %d,
                  "up_rate_limit": 0
                }
                """, dlData, dlSpeed, ulData, ulSpeed);

        HttpUtils.sendJson(exchange, json);
    }
}
