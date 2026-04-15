# Nexus — qBittorrent & Transmission API Bridge for BiglyBT

**Nexus** is a high-performance plugin for BiglyBT that acts as a translation layer, exposing a **qBittorrent-compatible Web API** and **Transmission RPC**.

This allows you to use modern, beautiful Web UIs like **VueTorrent**, **Flood**, or **Transmission Remote GUI** while keeping the advanced features and power of the BiglyBT engine.

## Building

1.  **Get BiglyBT.jar:** Copy `BiglyBT.jar` from your BiglyBT installation directory into the `libs/` folder of this project.
2.  **Build the plugin:** Run the following command in the project root:
    ```sh
    ./gradlew build
    ```
    This will create the plugin JAR file in `build/libs/Nexus_VERSION.jar`.

## Installation

1.  Copy the generated `Nexus_Version.jar` from `build/libs/` and go select the jar in Tools -> Plugins -> Install from file.
2.  Restart BiglyBT.

## Configuration

After installation, you can configure the plugin in BiglyBT under **Tools → Options → Plugins → Nexus**.

-   **HTTP Port:** The port for the API server (default: 8090).
-   **Bypass Auth:** Whether to disable authentication (default: true).
-   **Web UI Path:** The path to the `dist` folder of your chosen web UI (e.g., VueTorrent).

Once configured, you can access the web UI by navigating to `http://localhost:8090/` in your browser.

