package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.download.Download;
import com.biglybt.pif.download.DownloadStats;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.util.TorrentMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GET /api/v2/sync/maindata?rid=N
 * <p>
 * On the first request (rid=0) or whenever the client's rid doesn't match our
 * last-sent rid, we return a full snapshot with full_update=true.  On every
 * subsequent request with a matching rid we compute a diff: only changed/new
 * torrents, newly added/removed categories and tags.
 * <p>
 * Fields that change every second on idle torrents (last_activity, time_active,
 * seeding_time) are excluded from the change-detection comparison so that
 * finished, idle torrents don't flood every incremental response.
 */
public class SyncHandler {

    private final PluginInterface pi;
    private final AtomicLong ridCounter = new AtomicLong(1);

    // All mutable sync state is protected by stateLock.
    private final Object stateLock = new Object();
    private long lastSentRid = 0;
    private final Map<String, String> lastTorrentSnapshots = new LinkedHashMap<>();
    private final Map<String, JsonObject> lastCategoryObjects = new LinkedHashMap<>();
    private final Set<String> lastTags = new LinkedHashSet<>();

    private volatile long cachedFreeSpace = 0;
    private volatile long cachedFreeSpaceAt = 0;

    public SyncHandler(PluginInterface pi) {
        this.pi = pi;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "maindata" -> maindata(exchange);
            case "torrentPeers" -> torrentPeers(exchange);
            default -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    // ── GET /api/v2/sync/maindata ─────────────────────────────────────────────

    private void maindata(HttpExchange exchange) throws IOException {
        int clientRid = parseInt(HttpUtils.queryParams(exchange).getOrDefault("rid", "0"));

        Download[] downloads = pi.getDownloadManager().getDownloads();
        Map<String, JsonObject> currentTorrents = new LinkedHashMap<>();
        Map<String, JsonObject> currentCategories = new LinkedHashMap<>();
        long dlSpeed = 0, ulSpeed = 0, dlData = 0, ulData = 0, peers = 0;

        for (Download dl : downloads) {
            if (!TorrentMapper.isUserDownload(dl)) continue;

            String hash = TorrentMapper.hashHex(dl.getTorrent().getHash());
            currentTorrents.put(hash, TorrentMapper.toQbtInfo(dl));

            DownloadStats s = dl.getStats();
            dlSpeed += s.getDownloadAverage();
            ulSpeed += s.getUploadAverage();
            dlData += s.getDownloaded(false);
            ulData += s.getUploaded(false);
            try {
                com.biglybt.pif.peers.PeerManager pm = dl.getPeerManager();
                if (pm != null) peers += pm.getStats().getConnectedLeechers();
            } catch (Exception ignored) {
            }
        }

        Set<String> currentTags = TorrentMapper.getAllUserTags();

        for (com.biglybt.core.category.Category c : TorrentMapper.getUserCategories()) {
            JsonObject catObj = new JsonObject();
            catObj.addProperty("name", c.getName());
            catObj.addProperty("savePath", "");
            currentCategories.put(c.getName(), catObj);
        }

        JsonObject serverState = buildServerState(dlSpeed, ulSpeed, dlData, ulData, peers);

        Map<String, String> newSnapshots = new LinkedHashMap<>(currentTorrents.size());
        currentTorrents.forEach((h, info) -> newSnapshots.put(h, stableSnapshot(info)));

        String response;
        synchronized (stateLock) {
            boolean fullUpdate = (clientRid == 0 || clientRid != lastSentRid);
            long newRid = ridCounter.getAndIncrement();

            JsonObject root = new JsonObject();
            root.addProperty("rid", newRid);
            root.addProperty("full_update", fullUpdate);

            if (fullUpdate) {
                JsonObject torrentsObj = new JsonObject();
                currentTorrents.forEach(torrentsObj::add);
                root.add("torrents", torrentsObj);
                root.add("torrents_removed", new JsonArray());

                JsonObject catsObj = new JsonObject();
                currentCategories.forEach(catsObj::add);
                root.add("categories", catsObj);
                root.add("categories_removed", new JsonArray());

                JsonArray tagsArr = new JsonArray();
                currentTags.forEach(tagsArr::add);
                root.add("tags", tagsArr);
                root.add("tags_removed", new JsonArray());

            } else {
                JsonObject changedTorrents = new JsonObject();
                JsonArray removedTorrents = new JsonArray();
                newSnapshots.forEach((hash, snap) -> {
                    if (!snap.equals(lastTorrentSnapshots.get(hash)))
                        changedTorrents.add(hash, currentTorrents.get(hash));
                });
                lastTorrentSnapshots.keySet().stream()
                        .filter(h -> !currentTorrents.containsKey(h))
                        .forEach(removedTorrents::add);
                root.add("torrents", changedTorrents);
                root.add("torrents_removed", removedTorrents);

                JsonObject changedCats = new JsonObject();
                JsonArray removedCats = new JsonArray();
                currentCategories.forEach((k, v) -> {
                    JsonObject prev = lastCategoryObjects.get(k);
                    if (prev == null || !prev.equals(v)) changedCats.add(k, v);
                });
                lastCategoryObjects.keySet().stream()
                        .filter(k -> !currentCategories.containsKey(k))
                        .forEach(removedCats::add);
                root.add("categories", changedCats);
                root.add("categories_removed", removedCats);

                JsonArray newTagsArr = new JsonArray();
                JsonArray removedTagsArr = new JsonArray();
                currentTags.stream().filter(t -> !lastTags.contains(t)).forEach(newTagsArr::add);
                lastTags.stream().filter(t -> !currentTags.contains(t)).forEach(removedTagsArr::add);
                root.add("tags", newTagsArr);
                root.add("tags_removed", removedTagsArr);
            }

            root.add("trackers", new JsonObject());
            root.add("server_state", serverState);

            lastTorrentSnapshots.clear();
            lastTorrentSnapshots.putAll(newSnapshots);
            lastCategoryObjects.clear();
            lastCategoryObjects.putAll(currentCategories);
            lastTags.clear();
            lastTags.addAll(currentTags);
            lastSentRid = newRid;

            response = root.toString();
        }

        HttpUtils.sendJson(exchange, response);
    }

    // Strips fields that tick every second on idle torrents so they don't appear
    // in every incremental diff. Mutates and restores the input object to avoid
    // the per-poll deepCopy cost.
    private static String stableSnapshot(JsonObject obj) {
        com.google.gson.JsonElement la = obj.remove("last_activity");
        com.google.gson.JsonElement ta = obj.remove("time_active");
        com.google.gson.JsonElement st = obj.remove("seeding_time");
        String s = obj.toString();
        if (la != null) obj.add("last_activity", la);
        if (ta != null) obj.add("time_active", ta);
        if (st != null) obj.add("seeding_time", st);
        return s;
    }

    // ── GET /api/v2/sync/torrentPeers?hash= ──────────────────────────────────

    private void torrentPeers(HttpExchange exchange) throws IOException {
        String hash = HttpUtils.queryParams(exchange).get("hash");
        Download dl = null;
        if (hash != null) {
            for (Download d : pi.getDownloadManager().getDownloads()) {
                if (TorrentMapper.isUserDownload(d) &&
                        hash.equalsIgnoreCase(TorrentMapper.hashHex(d.getTorrent().getHash()))) {
                    dl = d;
                    break;
                }
            }
        }

        JsonObject result = new JsonObject();
        result.addProperty("full_update", true);
        result.addProperty("rid", ridCounter.getAndIncrement());
        result.addProperty("show_flags", true);
        result.add("peers", dl != null ? TorrentMapper.buildPeersJson(dl) : new JsonObject());
        HttpUtils.sendJson(exchange, result.toString());
    }

    // ── ServerState builder ───────────────────────────────────────────────────

    private JsonObject buildServerState(long dlSpeed, long ulSpeed,
                                        long dlData, long ulData, long peerCount) {
        long now = System.currentTimeMillis();
        if (now - cachedFreeSpaceAt > 10_000L) {
            try {
                String savePath = pi.getPluginconfig().getPluginStringParameter("nexus.default.savepath", "");
                if (savePath.isEmpty()) savePath = System.getProperty("user.home");
                cachedFreeSpace = new java.io.File(savePath).getFreeSpace();
            } catch (Exception ignored) {
            }
            cachedFreeSpaceAt = now;
        }
        long freeSpace = cachedFreeSpace;

        long alltimeDl = dlData, alltimeUl = ulData;
        try {
            alltimeDl = pi.getDownloadManager().getStats().getOverallDataBytesReceived();
            alltimeUl = pi.getDownloadManager().getStats().getOverallDataBytesSent();
        } catch (Exception ignored) {
        }

        double globalRatio = (alltimeDl > 0) ? (double) alltimeUl / alltimeDl : -1.0;

        long dlRateLimit = 0, ulRateLimit = 0;
        try {
            long kb = pi.getPluginconfig().getCoreLongParameter(com.biglybt.pif.PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC);
            dlRateLimit = kb > 0 ? kb * 1024L : 0L;
        } catch (Exception ignored) {
        }
        try {
            long kb = pi.getPluginconfig().getCoreLongParameter(com.biglybt.pif.PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC);
            ulRateLimit = kb > 0 ? kb * 1024L : 0L;
        } catch (Exception ignored) {
        }

        JsonObject s = new JsonObject();
        s.addProperty("connection_status", "connected");
        s.addProperty("dht_nodes", 0);
        s.addProperty("total_peer_connections", (int) peerCount);
        s.addProperty("dl_info_speed", dlSpeed);
        s.addProperty("dl_info_data", dlData);
        s.addProperty("dl_rate_limit", dlRateLimit);
        s.addProperty("up_info_speed", ulSpeed);
        s.addProperty("up_info_data", ulData);
        s.addProperty("up_rate_limit", ulRateLimit);
        s.addProperty("alltime_dl", alltimeDl);
        s.addProperty("alltime_ul", alltimeUl);
        // qBittorrent returns global_ratio as a formatted string, not a raw float
        s.addProperty("global_ratio", globalRatio < 0 ? "-1" : String.format("%.2f", globalRatio));
        s.addProperty("total_wasted_session", 0L);
        s.addProperty("free_space_on_disk", freeSpace);
        s.addProperty("queueing", false);
        s.addProperty("use_alt_speed_limits", false);
        s.addProperty("use_subcategories", false);
        s.addProperty("refresh_interval", 2000);
        s.addProperty("read_cache_hits", "0%");
        s.addProperty("read_cache_overload", "0%");
        s.addProperty("write_cache_overload", "0%");
        s.addProperty("total_buffers_size", 0L);
        s.addProperty("queued_io_jobs", 0);
        s.addProperty("total_queued_size", 0L);
        s.addProperty("average_time_queue", 0);
        s.addProperty("last_external_address_v4", "");
        s.addProperty("last_external_address_v6", "");
        return s;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }
}
