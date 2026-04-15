package com.empire.nexus;

import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.DirectoryParameter;
import com.biglybt.pif.ui.config.HyperlinkParameter;
import com.biglybt.pif.ui.config.IntParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.empire.nexus.http.NexusServer;

/**
 * BiglyBT plugin entry point.
 * <p>
 * Settings are exposed in BiglyBT → Tools → Options → Plugins → Nexus.
 * <p>
 * nexus.http.port    — TCP port the HTTP server listens on  (default 8090)
 * nexus.auth.bypass  — skip SID cookie check                (default true)
 * nexus.webui.path   — path to VueTorrent dist/ folder      (default "")
 * <p>
 * To use VueTorrent:
 * 1. Download a VueTorrent release and extract it.
 * 2. Set "VueTorrent dist/ folder" in the plugin settings to that folder.
 * 3. Open http://localhost:8090/ — the plugin serves the static files AND
 * the qBittorrent API from the same port.
 */
public class NexusPlugin implements Plugin {

    private NexusServer server;

    @Override
    public void initialize(PluginInterface pi) throws PluginException {

        LoggerChannel log = pi.getLogger().getChannel("Nexus");

        pi.getPluginProperties().setProperty("plugin.name", "Nexus");

        // ── Settings UI ───────────────────────────────────────────────────────
        // createBasicPluginConfigModel() registers a section under
        // BiglyBT → Tools → Options → Plugins → Nexus.
        // The string key is a parent section; "Nexus" is the child label.
        BasicPluginConfigModel config = pi.getUIManager()
                .createBasicPluginConfigModel("plugins", "nexus.section");

        config.addLabelParameter2("nexus.description");

        IntParameter portParam = config.addIntParameter2(
                "nexus.http.port", "nexus.http.port", 8090);
        BooleanParameter bypassParam = config.addBooleanParameter2(
                "nexus.auth.bypass", "nexus.auth.bypass", true);
        DirectoryParameter webuiParam = config.addDirectoryParameter2(
                "nexus.webui.path", "nexus.webui.path", "");

        // ── Read current values ───────────────────────────────────────────────
        int port = portParam.getValue();
        boolean bypass = bypassParam.getValue();
        String webuiPath = webuiParam.getValue();

        // ── Start server ──────────────────────────────────────────────────────
        server = new NexusServer(port, bypass, webuiPath, pi);
        try {
            server.start();
        } catch (Exception e) {
            throw new PluginException("Nexus: failed to start HTTP server on port " + port, e);
        }

        // ── Clickable URL in the settings page ────────────────────────────────
        HyperlinkParameter urlParam = config.addHyperlinkParameter2(
                "nexus.server.url", "http://localhost:" + port);
        urlParam.setHyperlink("http://localhost:" + port);

        log.log(null, "Nexus listening on http://localhost:" + port
                + "  bypass=" + bypass
                + (webuiPath.isEmpty() ? "" : "  webui=" + webuiPath));
    }
}
