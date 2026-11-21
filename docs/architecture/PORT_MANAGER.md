# UPM Port Manager — Implementation & Verification Report

**Component:** `src/main/java/upm/Ports.java`, `Server.java` (`/api/ports/inventory`),
web view "Ports" (`web/index.html`, `web/app.js`, `web/app.css`).
**Related standards:** [UPM_WEB_UI_STANDARD.md](UPM_WEB_UI_STANDARD.md),
[EVO_WEB_UI_INVENTORY.md](EVO_WEB_UI_INVENTORY.md).

## 1. What it does

The Port Manager is a read-only, centralized inventory of every known port of
the EVO family (EVO, ENR, TRYST, DCode, DStudio, Ollama) plus a live check
against actual system listeners.

- **Inventory** — 33 entries (see table below), each with application, port,
  service name, purpose/description, protocol, classification
  (`implemented` / `historical` / `proposed`), and the process identity the
  service is expected to run under.
- **Live check** — parses `/proc/net/tcp{,6}` for LISTEN sockets, resolves
  socket inodes to owning PIDs in one pass over the proc fd directories, and
  reads `/proc/<pid>/cmdline` for process identification. One scan serves the
  whole inventory (no per-port fd walk).
- **Status** — exactly three states, decided purely from observation:

| Status | Meaning | Color |
|---|---|---|
| `RUNNING` | Port is listening and the owning process matches the expected application | green |
| `READY` | Port is free and available for its intended service | orange |
| `OCCUPIED` | Port is listening, but the owner is not the expected service (foreign/unidentifiable process) | red |

- **Never** terminates processes, never changes port assignments, never opens
  privileged paths. All data comes from files readable by the UPM user.

### Ownership verification

Normally the owner is resolved directly (socket inode → `/proc/<pid>/fd`).
When the owner runs under another user account (e.g. the `ollama` system user
or root), the fd links are unreadable; the checker then falls back to matching
a process with the *same socket UID* and the *expected service name* from
`/proc/<pid>/status` + `cmdline`. Such entries are flagged
`verified: "indirect"` and the UI shows the explanation in the tooltip. If no
owner can be identified at all, the port is reported OCCUPIED with
`unknown (uid N)`.

## 2. API

| Endpoint | Behavior |
|---|---|
| `GET /api/ports/inventory` | Full inventory; response cached server-side for 10 s (the scan is a full `/proc` walk) |
| `GET /api/ports/inventory?refresh=1` | Bypasses the cache, re-scans listeners |
| `GET /api/ports/inventory?port=NNNN` | Fresh check, returns that single entry |
| `GET /api/ports/inventory?app=EVO` | Filtered inventory (summary recomputed) |

Response: `{ timeMs, summary: {running, ready, occupied}, controlCenter: {host, port, pid, process, status}, entries: [ {app, port, service, description, protocol, kind, lastChecked, status, process, pid, binding, verified, detail}, … ] }`.

The pre-existing `GET /api/ports` (control center + workspace launch-config
ports) is unchanged. The workspace snapshot now also populates
`launchConfigs[]._open` (resolved-port TCP probe), which fixes the dashboard
"Port status" widget that previously always displayed "free".

## 3. UI

- New "Ports" nav view (UPM visual style, tokens only).
- Summary metric cards: RUNNING / READY / OCCUPIED + UPM control center info.
- Table grouped per application with per-group status counts; columns:
  Application, Port, Service, Purpose/Description (permanently visible),
  Protocol, Status, Process, PID, Binding, Last checked, per-row refresh.
- Tooltips on service, purpose, status (check detail), process (full command
  line), binding and port cells.
- Sorting by Port / Protocol / Status / Last checked (clickable headers);
  filters by application and status; free-text search over service, port,
  process and description.
- `historical` and `proposed` rows are dimmed and carry kind tags so
  implemented ports are always distinguishable from reservations.
- Automatic periodic refresh every 15 s while the view is open (server-side
  cache TTL is 10 s); manual Refresh button forces a rescan; each row can be
  re-checked individually without touching the rest of the inventory.

## 4. Inventory

| App | Port | Service | Kind | Protocol |
|---|---|---|---|---|
| EVO | 28080 | HTTP Server | implemented | HTTP |
| EVO | 28180 | Secondary / Portal | implemented | HTTP |
| EVO | 28280 | MCP Server | implemented | HTTP |
| EVO | 28380 | DEVELOP | implemented | HTTP |
| EVO | 28480 | Supervisor REST | implemented | HTTP |
| EVO | 28580 | Supervisor Control TCP | implemented | TCP |
| EVO | 28680 | Auxiliary | implemented | HTTP |
| ENR | 8111 | HTTP Server | implemented | HTTP |
| ENR | 28780 | HTTP Server | historical | HTTP |
| ENR | 28880 | Proposed HTTP | proposed | HTTP |
| TRYST | 38080 | HTTP Server | implemented | HTTP |
| TRYST | 38180 | API | implemented | HTTP |
| TRYST | 38280 | TCP Server | implemented | TCP |
| TRYST | 38380 | Management | implemented | HTTP |
| TRYST | 38480 | WebSocket | implemented | WebSocket |
| TRYST | 38580 | Auxiliary | implemented | HTTP |
| TRYST | 38680 | Debug | implemented | HTTP |
| DCode | 48080 | HTTP Server | implemented | HTTP |
| DCode | 48081 | RCP Instance | proposed | HTTP |
| DCode | 48180 | Reserved | proposed | HTTP |
| DCode | 48280 | Reserved | proposed | TCP |
| DCode | 48380 | Management | proposed | HTTP |
| DCode | 48480 | Reserved | proposed | HTTP |
| DCode | 48580 | Reserved | proposed | TCP |
| DCode | 48680 | Auxiliary | proposed | HTTP |
| DStudio | 58080 | HTTP Server | proposed | HTTP |
| DStudio | 58180 | Secondary / API | proposed | HTTP |
| DStudio | 58280 | Reserved | proposed | TCP |
| DStudio | 58380 | Management | proposed | HTTP |
| DStudio | 58480 | Reserved | proposed | HTTP |
| DStudio | 58580 | Reserved | proposed | TCP |
| DStudio | 58680 | Auxiliary | proposed | HTTP |
| Ollama | 11434 | HTTP API | implemented | HTTP |

## 5. Verification results (2026-11-21, live system)

Verified against `ss -tlnp`, `/proc/net/tcp{,6}` and HTTP probes.

| Port | Reported | Evidence |
|---|---|---|
| 28080 | **OCCUPIED** | root-owned listener (uid 0, `0.0.0.0:28080`) serving HTTP 200 with `server: uvicorn` — a foreign Python web app, not EVO |
| 28180, 28280, 28380, 28680 | READY | no listeners; assignments confirmed in `~/.evo/ports.properties` (28280 also in `eu.kalafatic.evolution.servers/application.json`) |
| 28480, 28580 | **RUNNING** | EVO Supervisor, pid 138088 (`-Dport=28480 -Dcontrol.port=28580 …eu.kalafatic.evolution.supervisor…`) |
| 8111 | **RUNNING** | ENR, pid 2643332 (`enr-cli … serve -c enr.yaml`); `enr.yaml` port 8111, `RuntimeConfig` default 8111 |
| 28780 | READY | `~/.evo/ports.properties`: `ENR.port=28780, enabled=false` — historical |
| 28880 | READY | proposed, not implemented anywhere |
| 38080–38680 | READY | defaults verified in TRYST `ServicePortIdentifier`/`PortAllocationGroup` |
| 48080 | **RUNNING** | DCode headless server, pid 2730506 (product `…dcode.server.headless…`) |
| 48081–48680 | READY | reserved/proposed; 48380 and 48680 exist as `DCodePorts.MANAGEMENT`/`AUX` constants |
| 58080–58680 | READY | family plan block; see discrepancy D1 |
| 11434 | **RUNNING** | Ollama, pid 6916, runs as user `ollama` (uid 997) — verified indirectly (name + UID match, see §1) |

Summary at verification time: **5 RUNNING, 27 READY, 1 OCCUPIED**.
No proposed or unimplemented service was reported as running.

## 6. Findings & discrepancies

**D1 — DStudio port block conflict (48xxx vs 58xxx).**
The current DStudio codebase still defaults to the legacy 48xxx block:
`DStudioConfiguration.DEFAULT_TEIP_PORT = 48080`, `DEFAULT_DIAGNOSTICS_PORT = 48180`,
and the UPM workspace launch config for DStudio headless uses 48081. 48080 is
owned by **DCode**, so DStudio's legacy defaults collide with a live DCode
service. The 58xxx block documented in this inventory is the family plan;
all DStudio 58xxx entries are therefore classified `proposed` until the code
is migrated. (DStudio's harness sources also carry stale comments claiming
"DCode owns 58xxx", contradicting `DCodePorts` which defines the 48xxx block —
those comments are wrong.)

**D2 — EVO primary port 28080 is captured by a foreign process.**
A root-owned uvicorn application occupies 28080. Consequence observed on disk:
`~/.evo/ports.properties` persists `HTTP_PRIMARY.port=36621` — a previous EVO
start fell back to a dynamic port because 28080 was taken. The Port Manager
correctly reports OCCUPIED (unexpected process) instead of READY, which makes
this conflict visible. EVO never falls back silently from the administrator's
point of view.

**D3 — TRYST uses dynamic port allocation.**
Defaults are 38080–38680, but the runtime may allocate dynamic ports
(`~/.tryst/port-allocations.properties` currently assigns http=38280,
api=38380, tcp=38480, management=38580, websocket=38680, debug=38880,
auxiliary=38780). The inventory tracks the family default ports; the live
check reports the real listener state. No TRYST server was running at
verification time.

**D4 — ENR historical 28780.** `ENR.port=28780` remains in EVO's
ports.properties with `enabled=false`; the current ENR endpoint is 8111.
8111 was previously described as "historically observed — verify": it is now
**confirmed** as the implemented and running endpoint (`enr.yaml`,
`RuntimeConfig`, live pid 2643332).

**D5 — Ollama runs as a service user.** The socket owner cannot be read
directly from UPM's user; the check falls back to UID + process-name matching
and is flagged `verified: "indirect"` (see §1). This is the designed behavior
for any service running under a different account.

## 7. Known limitations

- TCP listeners only (the whole inventory is TCP/HTTP/WebSocket; UDP sockets
  are not monitored).
- On systems with `hidepid` mounts or hardened `/proc`, the fallback cannot
  identify owners and ports will be reported OCCUPIED with `unknown (uid N)`
  rather than RUNNING.
- Process identity matching is heuristic (substring of the command line:
  `evolution`, `enr`, `tryst`, `dcode`, `dstudio`, `ollama`). A foreign
  process whose command line contains the expected token would be classified
  RUNNING; no such case is known today.
- `verified: "indirect"` means ownership was **inferred** from the socket UID
  plus a matching process name — the PID is not linked to the socket inode.
  This is used only when the direct fd scan is impossible (foreign user
  account) and is always disclosed in the UI tooltip.
- The Port Manager observes; it deliberately does not fix. Conflicts (e.g.
  D2) are surfaced for the administrator, never auto-remediated.
