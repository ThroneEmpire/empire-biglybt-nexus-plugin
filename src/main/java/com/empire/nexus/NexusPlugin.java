package com.empire.nexus;

import com.biglybt.pif.Plugin;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.ui.config.*;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.empire.nexus.http.NexusServer;
import com.empire.nexus.util.TorrentMapper;

import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * BiglyBT plugin entry point.
 * <p>
 * Settings are exposed in BiglyBT → Tools → Options → Plugins → Nexus.
 * <p>
 * nexus.http.port    — TCP port the HTTP server listens on  (default 8090)
 * nexus.auth.bypass  — skip SID cookie check                (default false)
 * nexus.webui.path   — path to the web UI dist/ folder       (default "")
 * <p>
 * To use a qBittorrent-compatible web UI (e.g. qBittorrent-Web, cotorrent):
 * 1. Download a release and extract it.
 * 2. Set "Web UI folder" in the plugin settings to that folder.
 * 3. Open http://localhost:8090/ — the plugin serves the static files AND
 * the qBittorrent API from the same port.
 */
public class NexusPlugin implements Plugin {

    private static final String CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
    private static final int PASSWORD_LENGTH = 12;

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
                "nexus.auth.bypass", "nexus.auth.bypass", false);
        StringParameter usernameParam = config.addStringParameter2(
                "nexus.auth.username", "nexus.auth.username", "admin");
        PasswordParameter passwordParam = config.addPasswordParameter2(
                "nexus.auth.password", "nexus.auth.password",
                PasswordParameter.ET_PLAIN, new byte[0]);

        // Hidden flag — flips to true the moment the user saves a custom password
        BooleanParameter passwordUserModifiedParam = config.addBooleanParameter2(
                "nexus.auth.password.usermodified", "nexus.auth.password.usermodified", false);
        passwordUserModifiedParam.setVisible(false);

        DirectoryParameter webuiParam = config.addDirectoryParameter2(
                "nexus.webui.path", "nexus.webui.path", "");

        // Auto-generate password on first install (password empty and not yet user-modified)
        boolean userModified = passwordUserModifiedParam.getValue();
        byte[] pwBytes = passwordParam.getValue();
        boolean passwordEmpty = pwBytes == null || pwBytes.length == 0;

        if (!userModified && passwordEmpty) {
            String generated = generatePassword();
            passwordParam.setValue(generated);
            pwBytes = generated.getBytes(StandardCharsets.UTF_8);
        }

        // Show credentials hint and copy button only while password is still auto-generated
        if (!userModified) {
            String username = usernameParam.getValue();
            String hint = new String(pwBytes, StandardCharsets.UTF_8);

            LabelParameter credHint = config.addLabelParameter2("nexus.auth.credentials.hint");
            credHint.setLabelText("Auto-generated credentials — Username: " + username + "  Password: " + hint);

            ActionParameter copyBtn = config.addActionParameter2(
                    "nexus.auth.copy.password.label", "nexus.auth.copy.password");
            copyBtn.setStyle(ActionParameter.STYLE_BUTTON);
            copyBtn.addListener(param -> {
                try {
                    StringSelection sel = new StringSelection(hint);
                    Toolkit.getDefaultToolkit().getSystemClipboard().setContents(sel, sel);
                } catch (Exception ignored) {
                }
            });
        }

        // When the user saves a new password, flip the flag (hides credentials hint on next restart)
        passwordParam.addListener(param -> {
            if (!passwordUserModifiedParam.getValue()) {
                passwordUserModifiedParam.setValue(true);
            }
        });

        // Read current values
        int port = portParam.getValue();
        boolean bypass = bypassParam.getValue();
        String username = usernameParam.getValue();
        String password = new String(pwBytes, StandardCharsets.UTF_8);
        String webuiPath = webuiParam.getValue();

        // Init mapper (attributes, etc.)
        TorrentMapper.init(pluginInterface);

        // Start Server
        server = new NexusServer(port, bypass, username, password, webuiPath, pluginInterface);
        try {
            server.start();
        } catch (Exception e) {
            throw new PluginException("Nexus: failed to start HTTP server on port " + port, e);
        }

        String uiUrl = "http://localhost:" + port;
        HyperlinkParameter urlParam = config.addHyperlinkParameter2("nexus.server.url", uiUrl);
        urlParam.setHyperlink(uiUrl);

        log.log(null, "Nexus listening on http://localhost:" + port
                + "  bypass=" + bypass
                + (bypass ? "" : "  user=" + username)
                + (webuiPath.isEmpty() ? "" : "  webui=" + webuiPath));
    }

    private static String generatePassword() {
        SecureRandom rng = new SecureRandom();
        StringBuilder sb = new StringBuilder(PASSWORD_LENGTH);
        for (int i = 0; i < PASSWORD_LENGTH; i++) {
            sb.append(CHARS.charAt(rng.nextInt(CHARS.length())));
        }
        return sb.toString();
    }
}
