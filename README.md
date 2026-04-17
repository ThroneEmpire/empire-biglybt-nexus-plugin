# Nexus — qBittorrent & Transmission API Bridge for BiglyBT

**Nexus** is a BiglyBT plugin that simulates the qBittorrent Web API and Transmission RPC, letting you use any compatible remote UI while keeping the full power of the BiglyBT engine underneath.

| Mode | API | Default URL | Compatible clients |
|------|-----|-------------|--------------------|
| qBittorrent | `/api/v2/` | `http://localhost:8090/` | VueTorrent, qBittorrent Web UI, Flood, etc. |
| Transmission | `/transmission/rpc` | `http://localhost:8090/transmission/web/` | Transmission Web UI, Tremotesf, etc. |

Only one mode is active at a time — switching modes completely disables the other API endpoint.

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
| Bypass Auth | Disable username/password authentication | `true` |
| Username | Login username (when auth is enabled) | `admin` |
| Password | Login password (when auth is enabled) | `adminadmin` |
| Mode | API mode — `qBittorrent Web UI` or `Transmission` | `qBittorrent Web UI` |
| Web UI folder | Path to the extracted web UI folder | *(empty)* |

A clickable link to the web UI appears in the settings page once the server is running.

---

## Web UI Setup

### qBittorrent mode

1. Download a qBittorrent-compatible web UI (e.g. [VueTorrent](https://github.com/VueTorrent/VueTorrent)).
2. Extract it — the folder must contain `index.html` directly (not inside a subfolder).
3. Set **Web UI folder** to that folder.
4. Set **Mode** to `qBittorrent Web UI`.
5. Restart BiglyBT, then open `http://localhost:8090/`.

### Transmission mode

1. Download the [Transmission web UI](https://github.com/transmission/transmission) or build it from source (`web/` directory).
2. Extract it — the folder must contain `index.html` directly.
3. Set **Web UI folder** to that folder.
4. Set **Mode** to `Transmission`.
5. Restart BiglyBT, then open `http://localhost:8090/transmission/web/`.

If the web UI folder is not configured or `index.html` is not found, visiting the root URL will show a diagnostic page explaining exactly what went wrong.

---

## Authentication

When **Bypass Auth** is disabled:

- **qBittorrent mode** — uses the SID cookie flow (same as the real qBittorrent Web UI). Log in at `/api/v2/auth/login`.
- **Transmission mode** — uses HTTP Basic Auth with the configured username and password, plus the standard `X-Transmission-Session-Id` CSRF token handshake.

## License

This project is licensed under the GNU General Public License v3.0 or later (GPL-3.0-or-later).
See the `LICENSE` file for details.