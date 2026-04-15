# Nexus — qBittorrent / Transmission API Bridge for BiglyBT

A BiglyBT plugin that provides a qBittorrent and Transmission compatible API. This allows you to use web UIs and remote clients like VueTorrent, qBittorrent-Web, Flood, and Tremotesf with BiglyBT.

## Building

1.  **Get BiglyBT.jar:** Copy `BiglyBT.jar` from your BiglyBT installation directory into the `libs/` folder of this project.
2.  **Build the plugin:** Run the following command in the project root:
    ```sh
    ./gradlew shadowJar
    ```
    This will create the plugin JAR file in `build/libs/nexus.jar`.

## Installation

1.  Copy the generated `nexus.jar` from `build/libs/` into your BiglyBT plugins directory. The location varies by operating system, but is typically in a `.biglybt/plugins/` folder in your user's home directory.
2.  Restart BiglyBT.

## Configuration

After installation, you can configure the plugin in BiglyBT under **Tools → Options → Plugins → Nexus**.

-   **HTTP Port:** The port for the API server (default: 8090).
-   **Bypass Auth:** Whether to disable authentication (default: true).
-   **Web UI Path:** The path to the `dist` folder of your chosen web UI (e.g., VueTorrent).

Once configured, you can access the web UI by navigating to `http://localhost:8090/` in your browser.

