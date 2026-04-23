# Nexus — qBittorrent API Bridge for BiglyBT

**Nexus** is a BiglyBT plugin that simulates the qBittorrent Web API, letting you use any compatible remote UI while keeping the full power of the BiglyBT engine underneath.

| API | Default URL | Compatible clients |
|-----|-------------|--------------------|
| `/api/v2/` | `http://localhost:8090/` | VueTorrent, qBittorrent Web UI, Flood, etc. |

---

## Building

1. Copy `BiglyBT.jar` from your BiglyBT installation directory into the `libs/` folder.
2. Build:
   ```sh
   ./gradlew build
   ```
   The plugin JAR will be at `build/libs/Nexus_VERSION.jar`.

---

## Installation

1. In BiglyBT: **Tools → Plugins → Install from file**
2. Select the JAR from `build/libs/`.
3. Restart BiglyBT.

---

## Configuration

**Tools → Options → Plugins → Nexus**

| Setting | Description | Default |
|---------|-------------|---------|
| HTTP Port | Port the API server listens on | `8090` |
| Bypass Auth | Disable username/password authentication | `false` |
| Username | Login username (when auth is enabled) | `admin` |
| Password | Login password (when auth is enabled) | *(auto-generated on first install)* |
| Web UI folder | Path to the extracted web UI folder | *(empty)* |

On first install a secure password is auto-generated and shown in the settings page alongside a **Copy Password** button. Once you save your own password the credentials hint disappears permanently.

A clickable link to the web UI appears in the settings page once the server is running.

---

## Web UI Setup

1. Download a qBittorrent-compatible web UI (e.g. [VueTorrent](https://github.com/VueTorrent/VueTorrent)).
2. Extract it — the folder must contain `index.html` directly (not inside a subfolder).
3. Set **Web UI folder** to that folder.
4. Restart BiglyBT, then open `http://localhost:8090/`.

If the web UI folder is not configured or `index.html` is not found, visiting the root URL will show a diagnostic page explaining exactly what went wrong.

---

## Authentication

Nexus uses the SID cookie flow (same as the real qBittorrent Web UI). Log in at `/api/v2/auth/login` with your configured username and password. Enable **Bypass Auth** to skip authentication entirely (useful for local-only setups).

## License

This project is licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later).
See the `LICENSE` file for details.