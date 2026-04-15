package com.empire.nexus.api.qbt;

import com.biglybt.pif.PluginInterface;
import com.empire.nexus.http.HttpUtils;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;

/**
 * /api/v2/app/* endpoints.
 *
 * GET  version            → plain-text version string
 * GET  apiVersion         → plain-text API version
 * GET  webapiVersion      → alias of apiVersion (some clients use this)
 * GET  buildInfo          → JSON
 * GET  defaultSavePath    → plain-text save path
 * GET  preferences        → JSON (full set VueTorrent uses for its settings page)
 * POST setPreferences     → Ok. (accepted; TODO: apply to BiglyBT config)
 * POST shutdown           → Ok. (ignored — we don't shut down BiglyBT remotely)
 */
public class AppHandler {

    private static final String QBT_VERSION = "4.6.0";
    private static final String API_VERSION  = "2.9.3";

    private final PluginInterface pi;

    public AppHandler(PluginInterface pi) {
        this.pi = pi;
    }

    public void handle(HttpExchange exchange) throws IOException {
        switch (HttpUtils.pathSegment(exchange)) {
            case "version"         -> HttpUtils.sendText(exchange, "v" + QBT_VERSION);
            case "apiVersion",
                 "webapiVersion"   -> HttpUtils.sendText(exchange, API_VERSION);
            case "buildInfo"       -> buildInfo(exchange);
            case "defaultSavePath" -> defaultSavePath(exchange);
            case "preferences"     -> preferences(exchange);
            case "setPreferences",
                 "shutdown"        -> HttpUtils.sendText(exchange, "Ok.");
            default                -> HttpUtils.sendText(exchange, "Not Found", 404);
        }
    }

    private void buildInfo(HttpExchange exchange) throws IOException {
        HttpUtils.sendJson(exchange,
                "{\"qt\":\"0\",\"libtorrent\":\"2.0.10\",\"boost\":\"1.76.0\"," +
                "\"openssl\":\"3.0.0\",\"zlib\":\"1.2.11\",\"bitness\":64," +
                "\"platform\":\"linux\"}");
    }

    private void defaultSavePath(HttpExchange exchange) throws IOException {
        String path = "";
        try {
            path = pi.getPluginconfig().getPluginStringParameter("nexus.default.savepath", "");
        } catch (Exception ignored) {}
        if (path.isEmpty()) path = System.getProperty("user.home");
        HttpUtils.sendText(exchange, path);
    }

    private void preferences(HttpExchange exchange) throws IOException {
        // Covers every property VueTorrent's AppPreferences type references.
        // Values reflect BiglyBT defaults; expand setPreferences() to persist changes.
        HttpUtils.sendJson(exchange, """
                {
                  "locale": "en",

                  "save_path": "",
                  "temp_path_enabled": false,
                  "temp_path": "",
                  "scan_dirs": {},
                  "export_dir": "",
                  "export_dir_fin": "",

                  "mail_notification_enabled": false,
                  "mail_notification_sender": "",
                  "mail_notification_email": "",
                  "mail_notification_smtp": "",
                  "mail_notification_ssl_enabled": false,
                  "mail_notification_auth_enabled": false,
                  "mail_notification_username": "",
                  "mail_notification_password": "",

                  "autorun_enabled": false,
                  "autorun_program": "",

                  "preallocate_all": false,
                  "queueing_enabled": false,
                  "max_active_downloads": -1,
                  "max_active_torrents": -1,
                  "max_active_uploads": -1,
                  "dont_count_slow_torrents": false,
                  "slow_torrent_dl_rate_threshold": 2,
                  "slow_torrent_ul_rate_threshold": 2,
                  "slow_torrent_inactive_timer": 60,

                  "max_ratio_enabled": false,
                  "max_ratio": -1.0,
                  "max_ratio_act": 0,
                  "max_seeding_time_enabled": false,
                  "max_seeding_time": -1,
                  "max_inactive_seeding_time_enabled": false,
                  "max_inactive_seeding_time": -1,

                  "listen_port": 6881,
                  "upnp": true,
                  "natpmp": true,
                  "random_port": false,

                  "dl_limit": 0,
                  "up_limit": 0,
                  "bittorrent_protocol": 0,

                  "limit_utp_rate": false,
                  "limit_tcp_overhead": false,
                  "limit_lan_peers": true,
                  "alt_dl_limit": 10,
                  "alt_up_limit": 10,
                  "scheduler_enabled": false,
                  "schedule_from_hour": 8,
                  "schedule_from_min": 0,
                  "schedule_to_hour": 20,
                  "schedule_to_min": 0,
                  "scheduler_days": 0,

                  "dht": true,
                  "pex": true,
                  "lsd": true,
                  "encryption": 0,

                  "anonymous_mode": false,
                  "proxy_type": 0,
                  "proxy_ip": "",
                  "proxy_port": 8080,
                  "proxy_peer_connections": false,
                  "proxy_auth_enabled": false,
                  "proxy_username": "",
                  "proxy_password": "",
                  "proxy_hostname_lookup": false,
                  "proxy_bittorrent": false,
                  "proxy_misc": false,
                  "proxy_rss": false,

                  "ip_filter_enabled": false,
                  "ip_filter_path": "",
                  "ip_filter_trackers": false,
                  "banned_IPs": "",

                  "max_connec": 500,
                  "max_connec_per_torrent": 100,
                  "max_uploads": -1,
                  "max_uploads_per_torrent": -1,

                  "i2p_enabled": false,
                  "i2p_address": "127.0.0.1",
                  "i2p_port": 7656,
                  "i2p_mixed_mode": false,

                  "use_https": false,
                  "web_ui_domain_list": "*",
                  "web_ui_address": "*",
                  "web_ui_port": 8090,
                  "web_ui_upnp": false,
                  "web_ui_username": "admin",
                  "web_ui_password": "",
                  "web_ui_csrf_protection_enabled": true,
                  "web_ui_clickjacking_protection_enabled": true,
                  "web_ui_secure_cookie_enabled": false,
                  "web_ui_max_auth_fail_count": 5,
                  "web_ui_ban_duration": 3600,
                  "web_ui_session_timeout": 3600,
                  "bypass_local_auth": false,
                  "bypass_auth_subnet_whitelist_enabled": false,
                  "bypass_auth_subnet_whitelist": "",
                  "alternative_webui_enabled": false,
                  "alternative_webui_path": "",

                  "use_https_webcert": false,
                  "web_ui_https_cert_path": "",
                  "web_ui_https_key_path": "",

                  "dyndns_enabled": false,
                  "dyndns_scheme": 0,
                  "dyndns_domain": "",
                  "dyndns_username": "",
                  "dyndns_password": "",

                  "rss_refresh_interval": 30,
                  "rss_max_articles_per_feed": 50,
                  "rss_processing_enabled": false,
                  "rss_auto_downloading_enabled": false,
                  "rss_download_repack_proper_episodes": true,
                  "rss_smart_episode_filters": "s(\\d+)e(\\d+), (\\d+)x(\\d+), (\\d{4}[.\\-]\\d{2}[.\\-]\\d{2}), (\\d+)\\.(\\d+)",

                  "category_changed_tmm_enabled": false,
                  "torrent_changed_tmm_enabled": false,
                  "save_path_changed_tmm_enabled": false,

                  "add_trackers_enabled": false,
                  "add_trackers": "",

                  "create_subfolder_enabled": true,
                  "start_paused_enabled": false,
                  "auto_delete_mode": 0,
                  "auto_tmm_enabled": false,
                  "torrent_content_layout": "Original",
                  "torrent_stop_condition": "None",

                  "disk_cache": -1,
                  "disk_cache_ttl": 60,
                  "use_os_cache": true,
                  "disk_queue_size": 1048576,
                  "enable_coalesce_read_write": false,
                  "enable_piece_extent_affinity": false,
                  "enable_upload_suggestions": false,
                  "send_buffer_watermark": 500,
                  "send_buffer_low_watermark": 10,
                  "send_buffer_watermark_factor": 50,
                  "connection_speed": 20,
                  "socket_backlog_size": 30,
                  "outgoing_ports_min": 0,
                  "outgoing_ports_max": 0,
                  "upnp_lease_duration": 0,
                  "peer_tos": 4,
                  "utp_tcp_mixed_mode": 0,
                  "idn_support_enabled": false,
                  "enable_multi_connections_from_same_ip": false,
                  "validate_https_tracker_certificate": true,
                  "ssrf_mitigation": true,
                  "block_peers_on_privileged_ports": false,

                  "enable_embedded_tracker": false,
                  "embedded_tracker_port": 9000,
                  "embedded_tracker_port_forwarding": false,

                  "file_log_enabled": false,
                  "file_log_path": "",
                  "file_log_backup_enabled": true,
                  "file_log_max_size": 10,
                  "file_log_age": 6,
                  "file_log_age_type": 1,

                  "performance_warning": false,

                  "current_interface_name": "",
                  "current_interface_address": "",

                  "bdecode_depth_limit": 100,
                  "bdecode_token_limit": 10000000,
                  "async_io_threads": 10,
                  "checking_memory_use": 32,
                  "network_threads": 2,
                  "ssl_certificate": "",
                  "ssl_private_key": "",
                  "ssl_dh_params": ""
                }
                """);
    }
}
