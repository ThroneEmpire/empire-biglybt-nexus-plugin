package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.util.TorrentMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GET /api/v2/sync/maindata?rid=0
 *
 * VueTorrent polls this every few seconds for incremental updates.
 * The rid (response ID) lets the server send only changed data after the
 * first full snapshot.  We always return a full snapshot for now — this is
 * spec-compliant because the client must handle full_update:true at any time.
 */
public class SyncHandler {

    private final PluginInterface pi;
    private final AtomicLong ridCounter = new AtomicLong(1);

    public SyncHandler(PluginInterface pi) {
        this.pi = pi;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "maindata"    -> maindata(exchange);
            case "torrentPeers" -> torrentPeers(exchange);
            default            -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    // ── GET /api/v2/sync/maindata ─────────────────────────────────────────────

    private void maindata(HttpExchange exchange) throws IOException {
        long newRid = ridCounter.getAndIncrement();
        Download[] downloads = pi.getDownloadManager().getDownloads();

        // ── Torrents ──────────────────────────────────────────────────────────
        JsonObject torrents = new JsonObject();
        long dlSpeed = 0, ulSpeed = 0, dlData = 0, ulData = 0, peers = 0;

        for (Download dl : downloads) {
            if (dl.getTorrent() == null) continue;
            String hash = TorrentMapper.hashHex(dl.getTorrent().getHash());
            torrents.add(hash, TorrentMapper.toQbtInfo(dl));

            DownloadStats s = dl.getStats();
            dlSpeed += s.getDownloadAverage();
            ulSpeed += s.getUploadAverage();
            dlData  += s.getDownloaded(false);
            ulData  += s.getUploaded(false);
            try {
                com.biglybt.pif.peers.PeerManager pm = dl.getPeerManager();
                if (pm != null) peers += pm.getStats().getConnectedLeechers();
            } catch (Exception ignored) {}
        }

        // ── Server state ──────────────────────────────────────────────────────
        JsonObject state = buildServerState(dlSpeed, ulSpeed, dlData, ulData, peers);

        // ── Root ──────────────────────────────────────────────────────────────
        JsonObject root = new JsonObject();
        root.addProperty("rid",         newRid);
        root.addProperty("full_update", true);
        root.add("torrents",            torrents);
        root.add("torrents_removed",    new JsonArray());
        root.add("categories",          new JsonObject());
        root.add("categories_removed",  new JsonArray());
        root.add("tags",                new JsonArray());
        root.add("tags_removed",        new JsonArray());
        root.add("trackers",            new JsonObject());
        root.add("server_state",        state);

        HttpUtils.sendJson(exchange, root.toString());
    }

    // ── GET /api/v2/sync/torrentPeers?hash= ──────────────────────────────────

    private void torrentPeers(HttpExchange exchange) throws IOException {
        HttpUtils.sendJson(exchange, "{\"full_update\":true,\"peers\":{},\"rid\":1}");
    }

    // ── ServerState builder ───────────────────────────────────────────────────

    private JsonObject buildServerState(long dlSpeed, long ulSpeed,
                                        long dlData, long ulData, long peerCount) {
        // Free disk space — best-effort from the default save path
        long freeSpace = 0;
        try {
            String savePath = pi.getPluginconfig().getPluginStringParameter("nexus.default.savepath", "");
            if (savePath.isEmpty()) savePath = System.getProperty("user.home");
            freeSpace = new java.io.File(savePath).getFreeSpace();
        } catch (Exception ignored) {}

        // All-time totals — BiglyBT plugin API doesn't expose these directly,
        // so we use the current session totals as a best-effort approximation.
        long alltimeDl = dlData, alltimeUl = ulData;
        try {
            alltimeDl = pi.getDownloadManager().getStats().getOverallDataBytesReceived();
            alltimeUl = pi.getDownloadManager().getStats().getOverallDataBytesSent();
        } catch (Exception ignored) {}

        double globalRatio = (alltimeDl > 0) ? (double) alltimeUl / alltimeDl : 0.0;

        JsonObject s = new JsonObject();

        // Connection
        s.addProperty("connection_status",        "connected");
        s.addProperty("dht_nodes",                0);
        s.addProperty("total_peer_connections",   (int) peerCount);

        // Current session speeds
        s.addProperty("dl_info_speed",            dlSpeed);
        s.addProperty("dl_info_data",             dlData);
        s.addProperty("dl_rate_limit",            0L);
        s.addProperty("up_info_speed",            ulSpeed);
        s.addProperty("up_info_data",             ulData);
        s.addProperty("up_rate_limit",            0L);

        // All-time
        s.addProperty("alltime_dl",               alltimeDl);
        s.addProperty("alltime_ul",               alltimeUl);
        s.addProperty("global_ratio",             String.format("%.2f", globalRatio));
        s.addProperty("total_wasted_session",     0L);

        // Disk
        s.addProperty("free_space_on_disk",       freeSpace);

        // Queuing / feature flags
        s.addProperty("queueing",                 false);
        s.addProperty("use_alt_speed_limits",     false);
        s.addProperty("use_subcategories",        false);
        s.addProperty("refresh_interval",         2000);

        // Cache / IO stats (not exposed by BiglyBT plugin API — report zeros)
        s.addProperty("read_cache_hits",          "0%");
        s.addProperty("read_cache_overload",      "0%");
        s.addProperty("write_cache_overload",     "0%");
        s.addProperty("total_buffers_size",       0L);
        s.addProperty("queued_io_jobs",           0);
        s.addProperty("total_queued_size",        0L);
        s.addProperty("average_time_queue",       0);

        // External addresses (unknown)
        s.addProperty("last_external_address_v4", "");
        s.addProperty("last_external_address_v6", "");

        return s;
    }
}
