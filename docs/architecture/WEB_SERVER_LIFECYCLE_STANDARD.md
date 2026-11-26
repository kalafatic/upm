# Web Server Lifecycle & Application Startup Modes — EVO Family Standard

Standard for the three application startup modes and the web server lifecycle
across the EVO family (EVO, ENR, EVO-Code, EVO-Studio, TRYST, UPM).
Visual standard: [UPM_WEB_UI_STANDARD.md](UPM_WEB_UI_STANDARD.md).
Inventory: [EVO_WEB_UI_INVENTORY.md](EVO_WEB_UI_INVENTORY.md).

## 1. Startup modes

| Mode | Meaning |
|---|---|
| **HEADLESS** | No RCP/SWT UI. Backend services start; the web server auto-starts by default (unless configured otherwise). Fully operable via API/web/CLI. |
| **HEADLESS + WEB** | Same process, web UI reachable from a browser — the normal headless operation mode. |
| **RCP** | Native Eclipse RCP workbench; backend + web server auto-start by default; the web interface can additionally be opened/controlled from the main menu. |

Rules:

- The web interface never depends on the RCP workbench being available.
- No SWT/Workbench initialization on any headless path.
- One HTTP server implementation per application; one authoritative lifecycle.
- Default behavior is unchanged: **both HEADLESS and RCP auto-start the web
  server**.

## 2. Configuration

Persistent, application-level, in each app's existing configuration store:

| App | Config file | Keys |
|---|---|---|
| UPM | `config/application.jsonc` | `webServerEnabled` (true), `webServerAutoStart` (true), port=`port`, bind=`host` |
| EVO | EMF model (Server page of the settings editor) | `ServerSettings.enabled` (new EAttribute, default true), `ServerSettings.autoStart` (existing, default true), `ServerSettings.port` (28080), bind address via `NetworkInterfaceHelper` (`evo.network.mode`/`evo.network.bindAddress`) |
| ENR | `enr.yaml` | `web_server.enabled` (true), `web_server.auto_start` (true), port=`server.port`, host=`server.host` |
| EVO-Code | `~/.dcode/dcode-config.json` | `webServerEnabled` (true), `webServerAutoStart` (true), port=`httpPort`, bind=`bindAddress` |
| EVO-Studio | `~/.dstudio/dstudio.properties` | `webServer.enabled` (true), `webServer.autoStart` (true), port=`teip.port`, bind=`teip.bindAddress` |
| TRYST | `~/.tryst/tryst.properties` | `webServer.enabled` (true), `webServer.autoStart` (true), port=`server.http.port`, bind=`server.bindAddress` |

`webServer.port`/`webServer.bindAddress` reuse the existing authoritative port
and network configuration — no duplicate port systems were introduced.

Changes take effect according to each application's existing lifecycle
capabilities; where a restart is required this is stated in the UI
("applies after restart").

## 3. Lifecycle states

Where applicable the web server reports:

`DISABLED` (enabled=false) → `STOPPED` → `STARTING` → `RUNNING` →
`STOPPING` → `FAILED`

Guarantees:

- Start on an already running server is a no-op (idempotent).
- Stop releases resources and the port.
- Restart is a clean stop/start sequence.
- Start when `enabled=false` fails with a clear message; the state stays
  DISABLED.
- Port conflicts follow the existing per-app allocation policy
  (EVO `PortAvailabilityChecker`/FAILED state, EVO-Code `PortAllocator` range,
  TRYST dynamic range, EVO-Studio `PortProbe`/`PortConflictException`).
- The state is reflected in the RCP menu (items enable/disable per state)
  and in the web administration surfaces.

## 4. RCP main-menu controls

Each RCP application exposes a menu with:

- Open Web Interface (system browser)
- Open Configured Web URL
- Start Web Server
- Stop Web Server
- Restart Web Server
- Check Status (state + URL + port)

| App | Menu |
|---|---|
| EVO | Server → Web Interface (dynamic contribution, `ServerManager`-driven) |
| EVO-Code | Web Interface (dynamic contribution, `WebServerControl` OSGi service) |
| EVO-Studio | Web Interface (dynamic contribution, `DStudioLifecycleCoordinator`) |
| TRYST | Web (existing empty menu filled; `ApplicationLifecycleCoordinator`) |

All menu actions reuse the single existing server lifecycle service of the
application — no second server or lifecycle manager was created.

## 5. Headless control surfaces

- EVO: headless `IApplication` honors the flags; CLI `/start /stop /restart /status /config`.
- EVO-Code: headless daemon honors the flags; new CLI commands
  `server-start / server-stop / server-restart / server-status` drive the
  in-process `WebServerControl`; `start` now starts the local server when
  auto-start is off.
- EVO-Studio: dedicated headless application + `-headless` flag; CLI
  `start / restart / status / web`; `start` fails clearly when disabled.
- TRYST: dedicated `TrystServerApplication` + `-headless` flag; both share
  the coordinator gate.
- ENR/UPM: server-only products; the flags gate the web UI/server at startup.

## 6. Acceptance matrix

| Test | UPM | EVO | ENR | EVO-Code | EVO-Studio | TRYST |
|---|---|---|---|---|---|---|
| Headless starts backend + web server by default | ✓ (smoke-tested) | ✓ (code path) | ✓ (default config) | ✓ | ✓ | ✓ |
| Headless+Web usable without RCP | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| RCP starts backend + web server automatically | n/a | ✓ | n/a | ✓ | ✓ | ✓ |
| RCP menu Open Web UI | n/a | ✓ | n/a | ✓ | ✓ | ✓ |
| RCP menu Start/Stop/Restart/Status | n/a | ✓ | n/a | ✓ | ✓ | ✓ |
| autoStart=false skips startup, manual start works | ✓ (restart required, UPM has no runtime start) | ✓ | n/a (no runtime control channel) | ✓ (menu + CLI) | ✓ (menu + CLI) | ✓ (menu) |
| enabled=false keeps web unavailable | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Port conflict policy respected | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
| Build verified | jar + smoke test | javac (model/controller/view) | mvn compile | Tycho compile | Tycho compile + test-compile | Tycho compile |
