package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginConfig;
import com.biglybt.pif.PluginInterface;
import com.empire.nexus.http.HttpUtils;
import com.empire.nexus.http.NexusServer;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;

import java.io.File;
import java.io.IOException;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.Map;

/**
 * /api/v2/app/* endpoints.
 */
public class AppHandler {

    private static final String QBT_VERSION = "5.0.0";
    private static final String API_VERSION  = "2.11.0";

    private final PluginInterface pi;
    private final NexusServer server;

    public AppHandler(PluginInterface pi, NexusServer server) {
        this.pi     = pi;
        this.server = server;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "version"                    -> HttpUtils.sendText(exchange, "v" + QBT_VERSION);
            case "apiVersion", "webapiVersion"-> HttpUtils.sendText(exchange, API_VERSION);
            case "buildInfo"                  -> buildInfo(exchange);
            case "defaultSavePath"            -> defaultSavePath(exchange);
            case "preferences"                -> preferences(exchange);
            case "setPreferences"             -> setPreferences(exchange);
            case "networkInterfaceList"       -> networkInterfaceList(exchange);
            case "networkInterfaceAddressList"-> networkInterfaceAddressList(exchange);
            case "getDirectoryContent"        -> getDirectoryContent(exchange);
            case "sendTestEmail",
                 "setCookies",
                 "cookies",
                 "shutdown"                   -> HttpUtils.sendText(exchange, "Ok.");
            default                           -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    // ── GET /api/v2/app/buildInfo ─────────────────────────────────────────────

    private void buildInfo(HttpExchange exchange) throws IOException {
        String os = System.getProperty("os.name", "linux").toLowerCase();
        String platform = os.contains("win") ? "windows" : os.contains("mac") ? "osx" : "linux";
        String bitness  = System.getProperty("os.arch", "").contains("64") ? "64" : "32";
        HttpUtils.sendJson(exchange,
                "{\"qt\":\"0\",\"libtorrent\":\"2.0.10\",\"boost\":\"1.76.0\"," +
                "\"openssl\":\"3.0.0\",\"zlib\":\"1.2.11\",\"bitness\":" + bitness + "," +
                "\"platform\":\"" + platform + "\"}");
    }

    // ── GET /api/v2/app/defaultSavePath ──────────────────────────────────────

    private void defaultSavePath(HttpExchange exchange) throws IOException {
        String path = realSavePath();
        HttpUtils.sendText(exchange, path);
    }

    // ── GET /api/v2/app/preferences ──────────────────────────────────────────

    private void preferences(HttpExchange exchange) throws IOException {
        PluginConfig cfg = pi.getPluginconfig();

        String savePath = realSavePath();
        int    port     = cfg.getPluginIntParameter("nexus.http.port", 8090);
        String username = server.getUsername();
        long   dlLimitKb = 0, ulLimitKb = 0;
        try { dlLimitKb = cfg.getCoreLongParameter(PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC); } catch (Exception ignored) {}
        try { ulLimitKb = cfg.getCoreLongParameter(PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC);  } catch (Exception ignored) {}
        long dlLimit = dlLimitKb > 0 ? dlLimitKb * 1024L : 0L;
        long ulLimit = ulLimitKb > 0 ? ulLimitKb * 1024L : 0L;

        int incomingPort = 6881;
        try { incomingPort = cfg.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_INCOMING_TCP_PORT); } catch (Exception ignored) {}
        int maxConnGlobal = 500;
        try { maxConnGlobal = cfg.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_MAX_CONNECTIONS_GLOBAL); } catch (Exception ignored) {}
        int maxConnTorrent = 100;
        try { maxConnTorrent = cfg.getCoreIntParameter(PluginConfig.CORE_PARAM_INT_MAX_CONNECTIONS_PER_TORRENT); } catch (Exception ignored) {}

        JsonObject o = new JsonObject();

        o.addProperty("locale",         "en");
        o.addProperty("save_path",      savePath);
        o.addProperty("temp_path_enabled", false);
        o.addProperty("temp_path",      "");
        o.add("scan_dirs",              new JsonObject());
        o.addProperty("export_dir",     "");
        o.addProperty("export_dir_fin", "");

        o.addProperty("mail_notification_enabled",     false);
        o.addProperty("mail_notification_sender",      "");
        o.addProperty("mail_notification_email",       "");
        o.addProperty("mail_notification_smtp",        "");
        o.addProperty("mail_notification_ssl_enabled", false);
        o.addProperty("mail_notification_auth_enabled",false);
        o.addProperty("mail_notification_username",    "");
        o.addProperty("mail_notification_password",    "");

        o.addProperty("autorun_enabled", false);
        o.addProperty("autorun_program", "");

        o.addProperty("preallocate_all",             false);
        o.addProperty("queueing_enabled",            false);
        o.addProperty("max_active_downloads",        -1);
        o.addProperty("max_active_torrents",         -1);
        o.addProperty("max_active_uploads",          -1);
        o.addProperty("dont_count_slow_torrents",    false);
        o.addProperty("slow_torrent_dl_rate_threshold", 2);
        o.addProperty("slow_torrent_ul_rate_threshold", 2);
        o.addProperty("slow_torrent_inactive_timer", 60);

        o.addProperty("max_ratio_enabled",                false);
        o.addProperty("max_ratio",                        -1.0);
        o.addProperty("max_ratio_act",                    0);
        o.addProperty("max_seeding_time_enabled",         false);
        o.addProperty("max_seeding_time",                 -1);
        o.addProperty("max_inactive_seeding_time_enabled",false);
        o.addProperty("max_inactive_seeding_time",        -1);

        o.addProperty("listen_port",  incomingPort);
        o.addProperty("upnp",         true);
        o.addProperty("natpmp",       true);
        o.addProperty("random_port",  false);

        o.addProperty("dl_limit",           dlLimit);
        o.addProperty("up_limit",           ulLimit);
        o.addProperty("bittorrent_protocol", 0);
        o.addProperty("limit_utp_rate",      false);
        o.addProperty("limit_tcp_overhead",  false);
        o.addProperty("limit_lan_peers",     true);
        o.addProperty("alt_dl_limit",        10);
        o.addProperty("alt_up_limit",        10);
        o.addProperty("scheduler_enabled",   false);
        o.addProperty("schedule_from_hour",  8);
        o.addProperty("schedule_from_min",   0);
        o.addProperty("schedule_to_hour",    20);
        o.addProperty("schedule_to_min",     0);
        o.addProperty("scheduler_days",      0);

        o.addProperty("dht",        true);
        o.addProperty("pex",        true);
        o.addProperty("lsd",        true);
        o.addProperty("encryption", 0);

        o.addProperty("anonymous_mode",         false);
        o.addProperty("proxy_type",             0);
        o.addProperty("proxy_ip",               "");
        o.addProperty("proxy_port",             8080);
        o.addProperty("proxy_peer_connections", false);
        o.addProperty("proxy_auth_enabled",     false);
        o.addProperty("proxy_username",         "");
        o.addProperty("proxy_password",         "");
        o.addProperty("proxy_hostname_lookup",  false);
        o.addProperty("proxy_bittorrent",       false);
        o.addProperty("proxy_misc",             false);
        o.addProperty("proxy_rss",              false);

        o.addProperty("ip_filter_enabled",  false);
        o.addProperty("ip_filter_path",     "");
        o.addProperty("ip_filter_trackers", false);
        o.addProperty("banned_IPs",         "");

        o.addProperty("max_connec",             maxConnGlobal);
        o.addProperty("max_connec_per_torrent", maxConnTorrent);
        o.addProperty("max_uploads",            -1);
        o.addProperty("max_uploads_per_torrent",-1);

        o.addProperty("i2p_enabled",    false);
        o.addProperty("i2p_address",    "127.0.0.1");
        o.addProperty("i2p_port",       7656);
        o.addProperty("i2p_mixed_mode", false);

        o.addProperty("use_https",                              false);
        o.addProperty("web_ui_domain_list",                     "*");
        o.addProperty("web_ui_address",                         "*");
        o.addProperty("web_ui_port",                            port);
        o.addProperty("web_ui_upnp",                            false);
        o.addProperty("web_ui_username",                        username);
        o.addProperty("web_ui_password",                        "");
        o.addProperty("web_ui_csrf_protection_enabled",         true);
        o.addProperty("web_ui_clickjacking_protection_enabled", true);
        o.addProperty("web_ui_secure_cookie_enabled",           false);
        o.addProperty("web_ui_max_auth_fail_count",             5);
        o.addProperty("web_ui_ban_duration",                    3600);
        o.addProperty("web_ui_session_timeout",                 3600);
        o.addProperty("bypass_local_auth",                      server.isBypassAuth());
        o.addProperty("bypass_auth_subnet_whitelist_enabled",   false);
        o.addProperty("bypass_auth_subnet_whitelist",           "");
        o.addProperty("alternative_webui_enabled",              false);
        o.addProperty("alternative_webui_path",                 "");

        o.addProperty("use_https_webcert",    false);
        o.addProperty("web_ui_https_cert_path", "");
        o.addProperty("web_ui_https_key_path",  "");

        o.addProperty("dyndns_enabled",  false);
        o.addProperty("dyndns_scheme",   0);
        o.addProperty("dyndns_domain",   "");
        o.addProperty("dyndns_username", "");
        o.addProperty("dyndns_password", "");

        o.addProperty("rss_refresh_interval",           30);
        o.addProperty("rss_max_articles_per_feed",      50);
        o.addProperty("rss_processing_enabled",         false);
        o.addProperty("rss_auto_downloading_enabled",   false);
        o.addProperty("rss_download_repack_proper_episodes", true);
        o.addProperty("rss_smart_episode_filters",
                "s(\\d+)e(\\d+), (\\d+)x(\\d+), (\\d{4}[.\\-]\\d{2}[.\\-]\\d{2}), (\\d+)\\.(\\d+)");

        o.addProperty("category_changed_tmm_enabled",  false);
        o.addProperty("torrent_changed_tmm_enabled",   false);
        o.addProperty("save_path_changed_tmm_enabled", false);

        o.addProperty("add_trackers_enabled", false);
        o.addProperty("add_trackers",         "");

        o.addProperty("create_subfolder_enabled", true);
        o.addProperty("start_paused_enabled",     false);
        o.addProperty("auto_delete_mode",         0);
        o.addProperty("auto_tmm_enabled",         false);
        o.addProperty("torrent_content_layout",   "Original");
        o.addProperty("torrent_stop_condition",   "None");

        o.addProperty("disk_cache",                  -1);
        o.addProperty("disk_cache_ttl",              60);
        o.addProperty("use_os_cache",                true);
        o.addProperty("disk_queue_size",             1048576);
        o.addProperty("enable_coalesce_read_write",  false);
        o.addProperty("enable_piece_extent_affinity",false);
        o.addProperty("enable_upload_suggestions",   false);
        o.addProperty("send_buffer_watermark",       500);
        o.addProperty("send_buffer_low_watermark",   10);
        o.addProperty("send_buffer_watermark_factor",50);
        o.addProperty("connection_speed",            20);
        o.addProperty("socket_backlog_size",         30);
        o.addProperty("outgoing_ports_min",          0);
        o.addProperty("outgoing_ports_max",          0);
        o.addProperty("upnp_lease_duration",         0);
        o.addProperty("peer_tos",                    4);
        o.addProperty("utp_tcp_mixed_mode",          0);
        o.addProperty("idn_support_enabled",         false);
        o.addProperty("enable_multi_connections_from_same_ip", false);
        o.addProperty("validate_https_tracker_certificate",    true);
        o.addProperty("ssrf_mitigation",             true);
        o.addProperty("block_peers_on_privileged_ports", false);

        o.addProperty("enable_embedded_tracker",          false);
        o.addProperty("embedded_tracker_port",            9000);
        o.addProperty("embedded_tracker_port_forwarding", false);

        o.addProperty("file_log_enabled",        false);
        o.addProperty("file_log_path",           "");
        o.addProperty("file_log_backup_enabled", true);
        o.addProperty("file_log_max_size",       10);
        o.addProperty("file_log_age",            6);
        o.addProperty("file_log_age_type",       1);

        o.addProperty("performance_warning", false);

        o.addProperty("current_interface_name",    "");
        o.addProperty("current_interface_address", "");

        o.addProperty("bdecode_depth_limit",  100);
        o.addProperty("bdecode_token_limit",  10000000);
        o.addProperty("async_io_threads",     10);
        o.addProperty("checking_memory_use",  32);
        o.addProperty("network_threads",      2);
        o.addProperty("ssl_certificate",      "");
        o.addProperty("ssl_private_key",      "");
        o.addProperty("ssl_dh_params",        "");

        HttpUtils.sendJson(exchange, o.toString());
    }

    // ── POST /api/v2/app/setPreferences ──────────────────────────────────────

    private void setPreferences(HttpExchange exchange) throws IOException {
        // qBittorrent API: POST body is form-encoded with a single "json" field
        // containing the preferences as a JSON object string.
        try {
            Map<String, String> params = HttpUtils.formParams(exchange);
            String jsonStr = params.getOrDefault("json", "").trim();
            if (jsonStr.isEmpty()) {
                HttpUtils.sendText(exchange, "Ok.");
                return;
            }
            com.google.gson.JsonObject prefs =
                    com.google.gson.JsonParser.parseString(jsonStr).getAsJsonObject();
            PluginConfig cfg = pi.getPluginconfig();

            if (prefs.has("dl_limit")) {
                long bps = prefs.get("dl_limit").getAsLong();
                // -1 or 0 both mean unlimited in the qBittorrent API
                cfg.setCoreLongParameter(PluginConfig.CORE_PARAM_LONG_MAX_DOWNLOAD_SPEED_KBYTES_PER_SEC,
                        bps > 0 ? bps / 1024L : 0L);
            }
            if (prefs.has("up_limit")) {
                long bps = prefs.get("up_limit").getAsLong();
                cfg.setCoreLongParameter(PluginConfig.CORE_PARAM_LONG_MAX_UPLOAD_SPEED_KBYTES_PER_SEC,
                        bps > 0 ? bps / 1024L : 0L);
            }
            if (prefs.has("save_path")) {
                String sp = prefs.get("save_path").getAsString().trim();
                if (!sp.isEmpty()) {
                    cfg.setCoreStringParameter(PluginConfig.CORE_PARAM_STRING_DEFAULT_SAVE_PATH, sp);
                }
            }
            if (prefs.has("max_connec")) {
                int v = prefs.get("max_connec").getAsInt();
                if (v >= 0) cfg.setCoreIntParameter(PluginConfig.CORE_PARAM_INT_MAX_CONNECTIONS_GLOBAL, v);
            }
            if (prefs.has("max_connec_per_torrent")) {
                int v = prefs.get("max_connec_per_torrent").getAsInt();
                if (v >= 0) cfg.setCoreIntParameter(PluginConfig.CORE_PARAM_INT_MAX_CONNECTIONS_PER_TORRENT, v);
            }
        } catch (Exception ignored) {}
        HttpUtils.sendText(exchange, "Ok.");
    }

    // ── GET /api/v2/app/networkInterfaceList ──────────────────────────────────

    private void networkInterfaceList(HttpExchange exchange) throws IOException {
        JsonArray arr = new JsonArray();
        // "Any interface" entry always first
        JsonObject any = new JsonObject();
        any.addProperty("name",  "Any interface");
        any.addProperty("value", "");
        arr.add(any);
        try {
            for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!iface.isLoopback() && iface.isUp()) {
                    JsonObject o = new JsonObject();
                    String displayName = iface.getDisplayName();
                    o.addProperty("name",  displayName != null ? displayName : iface.getName());
                    o.addProperty("value", iface.getName());
                    arr.add(o);
                }
            }
        } catch (Exception ignored) {}
        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── GET /api/v2/app/networkInterfaceAddressList ───────────────────────────

    private void networkInterfaceAddressList(HttpExchange exchange) throws IOException {
        String ifaceName = HttpUtils.queryParams(exchange).getOrDefault("iface", "");
        JsonArray arr    = new JsonArray();
        arr.add("0.0.0.0");  // always include "any" option
        try {
            NetworkInterface iface = ifaceName.isEmpty()
                    ? null : NetworkInterface.getByName(ifaceName);
            java.util.List<java.net.InetAddress> addrs = iface != null
                    ? Collections.list(iface.getInetAddresses())
                    : java.util.Arrays.asList(java.net.InetAddress.getAllByName(null));
            for (java.net.InetAddress addr : addrs) {
                String ip = addr.getHostAddress();
                if (ip != null && !ip.equals("0.0.0.0")) arr.add(ip);
            }
        } catch (Exception ignored) {}
        HttpUtils.sendJson(exchange, arr.toString());
    }

    // ── POST /api/v2/app/getDirectoryContent ──────────────────────────────────

    private void getDirectoryContent(HttpExchange exchange) throws IOException {
        Map<String, String> params = HttpUtils.formParams(exchange);
        String dirPath = params.getOrDefault("dirPath", "").trim();

        // Use home dir if no path provided
        if (dirPath.isEmpty()) dirPath = System.getProperty("user.home", "");

        File dir = new File(dirPath);
        JsonObject result = new JsonObject();
        result.addProperty("path",      dir.getAbsolutePath());
        result.addProperty("separator", File.separator);

        JsonArray subdirs = new JsonArray();
        JsonArray files   = new JsonArray();

        try {
            if (dir.isDirectory()) {
                File[] entries = dir.listFiles();
                if (entries != null) {
                    java.util.Arrays.sort(entries,
                            java.util.Comparator.comparing(f -> f.getName().toLowerCase()));
                    for (File f : entries) {
                        if (f.isDirectory()) subdirs.add(f.getName());
                        // we intentionally omit files — directory browser only needs dirs
                    }
                }
            }
        } catch (Exception ignored) {}

        result.add("subdirs", subdirs);
        result.add("files",   files);
        HttpUtils.sendJson(exchange, result.toString());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String realSavePath() {
        try {
            String p = pi.getPluginconfig().getCoreStringParameter(
                    PluginConfig.CORE_PARAM_STRING_DEFAULT_SAVE_PATH);
            if (p != null && !p.isEmpty()) return p;
        } catch (Exception ignored) {}
        return System.getProperty("user.home", "");
    }
}
