package com.empire.nexus;

import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.empire.nexus.util.TorrentMapper;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.DirectoryParameter;
import com.biglybt.pif.ui.config.HyperlinkParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.config.PasswordParameter;
import com.biglybt.pif.ui.config.StringListParameter;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;

import java.nio.charset.StandardCharsets;
import com.empire.nexus.http.NexusServer;

/**
 * BiglyBT plugin entry point.
 * <p>
 * Settings are exposed in BiglyBT → Tools → Options → Plugins → Nexus.
 * <p>
 * nexus.http.port    — TCP port the HTTP server listens on  (default 8090)
 * nexus.auth.bypass  — skip SID cookie check                (default true)
 * nexus.webui.path   — path to the web UI dist/ folder       (default "")
 * <p>
 * To use a qBittorrent-compatible web UI (e.g. qBittorrent-Web, cotorrent):
 * 1. Download a release and extract it.
 * 2. Set "Web UI folder" in the plugin settings to that folder.
 * 3. Open http://localhost:8090/ — the plugin serves the static files AND
 * the qBittorrent API from the same port.
 */
public class NexusPlugin implements Plugin {

    private NexusServer server;

    @Override
    public void initialize(PluginInterface pluginInterface) throws PluginException {
        LoggerChannel log = pluginInterface.getLogger().getChannel("Nexus");
        pluginInterface.getPluginProperties().setProperty("plugin.name", "Nexus");

        // Settings UI
        BasicPluginConfigModel config = pluginInterface.getUIManager()
                .createBasicPluginConfigModel("plugins", "nexus.section");

        config.addLabelParameter2("nexus.description");
        IntParameter portParam = config.addIntParameter2(
                "nexus.http.port", "nexus.http.port", 8090);
        BooleanParameter bypassParam = config.addBooleanParameter2(
                "nexus.auth.bypass", "nexus.auth.bypass", true);
        StringParameter usernameParam = config.addStringParameter2(
                "nexus.auth.username", "nexus.auth.username", "admin");
        PasswordParameter passwordParam = config.addPasswordParameter2(
                "nexus.auth.password", "nexus.auth.password",
                PasswordParameter.ET_PLAIN, "adminadmin".getBytes(StandardCharsets.UTF_8));
        StringListParameter modeParam = config.addStringListParameter2(
                "nexus.mode", "nexus.mode",
                new String[]{"qbittorrent", "transmission"},
                new String[]{"qBittorrent", "Transmission"},
                "qbittorrent");
        DirectoryParameter webuiParam = config.addDirectoryParameter2(
                "nexus.webui.path", "nexus.webui.path", "");

        // Read current values
        int     port       = portParam.getValue();
        boolean bypass     = bypassParam.getValue();
        String  username   = usernameParam.getValue();
        byte[]  pwBytes    = passwordParam.getValue();
        String  password   = (pwBytes != null && pwBytes.length > 0)
                ? new String(pwBytes, StandardCharsets.UTF_8) : "";
        String  mode       = modeParam.getValue();
        String  webuiPath  = webuiParam.getValue();

        // Init mapper (attributes, etc.)
        TorrentMapper.init(pluginInterface);

        // Start Server
        server = new NexusServer(port, bypass, username, password, mode, webuiPath, pluginInterface);
        try {
            server.start();
        } catch (Exception e) {
            throw new PluginException("Nexus: failed to start HTTP server on port " + port, e);
        }

        // ── Clickable URL — points to the web UI landing page for the active mode
        String uiUrl = "transmission".equals(mode)
                ? "http://localhost:" + port + "/transmission/web/"
                : "http://localhost:" + port;
        HyperlinkParameter urlParam = config.addHyperlinkParameter2("nexus.server.url", uiUrl);
        urlParam.setHyperlink(uiUrl);

        log.log(null, "Nexus listening on http://localhost:" + port
                + "  mode=" + mode
                + "  bypass=" + bypass
                + (bypass ? "" : "  user=" + username)
                + (webuiPath.isEmpty() ? "" : "  webui=" + webuiPath));
    }
}
