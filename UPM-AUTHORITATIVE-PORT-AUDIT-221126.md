# UPM Authoritative Port Audit — 221126

Mandatory authoritative port allocation audit and repair. This specification supersedes
all historical port assignments. Result of a full inspection of all repositories,
configurations, source code, launch configurations, product definitions, runtime
properties, persisted configuration, and UPM inventory.

## Summary of Findings

The **DCode ↔ DStudio port blocks were crossed**: DCode (EVO-Code) had been migrated to
the 58xxx family and DStudio (EVO-Studio) to the 48xxx family — the inverse of the
authoritative assignment. Both were repaired and verified at runtime.

| Application | Service | Expected | Configured | Actual | Result |
|---|---|---:|---:|---:|---|
| EVO | HTTP Server | 28080 | 28080 | 28080 (foreign occupant) | OK config · runtime blocked |
| EVO | Secondary / Portal | 28180 | 28180 | — | OK |
| EVO | MCP Server | 28280 | 28280 | — | OK |
| EVO | DEVELOP | 28380 | 28380 | — | OK |
| EVO | Supervisor REST | 28480 | 28480 | — | OK |
| EVO | Supervisor Control TCP | 28580 | 28580 | — | OK |
| EVO | Auxiliary | 28680 | 28680 | — | OK |
| TRYST | HTTP Server | 38080 | 38080 | 38080 (was 38980) | FIXED (offset reset) |
| TRYST | API | 38180 | 38180 | 38180 (was 39080) | FIXED |
| TRYST | TCP Server | 38280 | 38280 | 38280 (was 39180) | FIXED |
| TRYST | Management | 38380 | 38380 | 38380 (was 39280) | FIXED |
| TRYST | WebSocket | 38480 | 38480 | 38480 (was 39380) | FIXED |
| TRYST | Auxiliary | 38580 | 38580 | 38580 (was 39480) | FIXED |
| TRYST | Debug | 38680 | 38680 | 38680 (was 39580) | FIXED |
| DCode | HTTP Server / Headless | 48080 | 48080 (was 58080) | 48080 | **FIXED + verified** |
| DCode | RCP instance | 48081 | 48081 | — | OK |
| DCode | Reserved | 48180 | 48180 | — | OK |
| DCode | Reserved | 48280 | 48280 | — | OK |
| DCode | Management | 48380 | 48380 (was 58380) | — | FIXED |
| DCode | Reserved | 48480 | 48480 | — | OK |
| DCode | Reserved | 48580 | 48580 | — | OK |
| DCode | Auxiliary | 48680 | 48680 (was 58680) | — | FIXED |
| DStudio | HTTP Server | 58080 | 58080 (was 48080/48085) | 58080 | **FIXED + verified** |
| DStudio | Secondary / API | 58180 | 58180 (was 48180) | — | FIXED |
| DStudio | Reserved | 58280 | 58280 | — | OK |
| DStudio | Management | 58380 | 58380 | — | OK |
| DStudio | Reserved | 58480 | 58480 | — | OK |
| DStudio | Reserved | 58580 | 58580 | — | OK |
| DStudio | Auxiliary | 58680 | 58680 | — | OK |
| ENR | API / Headless Runtime | 28780 | 28780 | 28780 (occupied by Eclipse RCP pid) | OK config · runtime blocked |
| ENR | Web UI | 28880 | 28880 | 28880 | OK |
| Ollama | HTTP API | 11434 | 11434 | 11434 | OK |

`8111` (historical ENR) is removed from all configuration and the UPM inventory.

---

## Repairs Performed

### 1. DCode (EVO-Code) — 58xxx → 48xxx
- **Root cause:** a prior collision-resolution had swapped DCode into the 58xxx block
  (`DCodePorts.HTTP = 58080`) and documented "DStudio owns 48xxx", inverting the
  authoritative assignment.
- **Modified (evo-code-221126):**
  - `plugins/eu.kalafatic.dcode.core/.../port/DCodePorts.java` — `BASE_OFFSET 50000→40000`,
    `HTTP 58080→48080`, `MANAGEMENT 58380→48380`, `AUX 58680→48680`.
  - `plugins/eu.kalafatic.dcode.cli/.../EndpointResolver.java` — `DEFAULT_PORT 58080→48080`.
  - `.../CliApplication.java`, `.../RuntimeScanner.java`, `.../ConnectionDialog.java`,
    `.../views/ScanRuntimesDialog.java`, `.../RuntimeConnection.java` — hardcoded `:58080` → `:48080`.
  - Tests: `EndpointResolverTest`, `WebBaseUrlResolutionTest`, `DirectInferenceApiTest`.
  - `AGENTS.md` port-convention statement corrected.

### 2. DStudio (EVO-Studio) — 48xxx → 58xxx
- **Modified (evo-studio-221126):**
  - `DStudioConfiguration.java` — `DEFAULT_TEIP_PORT 48080→58080`,
    `DEFAULT_DIAGNOSTICS_PORT 48180→58180` (legacy/transient markers retained; migration
    now remaps legacy 48xxx → 58xxx).
  - `.../harness/dcode/DCodeHarnessAdapter.java` — `DEFAULT_ENDPOINT` → `http://localhost:48080`.
  - `.../application/PortConflictDialog.java` — conflict text corrected.
  - `DStudioConfigurationServiceImpl.java` — normalization comment + log message corrected.
  - Tests: `PortConflictHandlingTest`, `SecretCodecTest`, `DCodeFamilyIntegrationTest`.

### 3. UPM workspace launch configuration
- DStudio headless launch config `port`/`readiness.port` `48081 → 58080`
  (`workspaces/default/workspace.jsonc`, both checkouts).

### 4. Persisted runtime configuration
- `~/.dcode/dcode-config.json` — `httpPort 58081 → 48080`.
- `~/.dstudio/dstudio.properties` — `teip.port 48085 → 58080`, `diagnostics.port 48180 → 58180`.
- `~/.tryst/port-allocations.properties` — `groupOffset 900 → 0`, all service
  `assignedPort` restored to their authoritative `defaultPort` (38080–38680).

### 5. UPM inventory
- `Ports.java` (both checkouts) already matches the spec: EVO 28xxx, TRYST 38xxx,
  EVO-Code 48xxx, EVO-Studio 58xxx, ENR 28780/28880, Ollama 11434 — no change required.

---

## Runtime Verification

- **DCode (EVO-Code) headless** launched with Java 25: `EVO-Code HTTP API listening on
  127.0.0.1:48080 (configured)`; `GET /health` → 200
  `{"ready":true,"status":"UP"}`. ✅
- **DStudio (EVO-Studio) headless** launched with Java 25: `[EVO-Studio] Server listening
  on 127.0.0.1:58080`; `GET /api/status` → 200 `{"ready":true,"status":"UP"}`. ✅
- **No cross-application collision** — DCode 48xxx and DStudio 58xxx now bind in disjoint
  families; both can run simultaneously.
- **ENR** — `enr.yaml` already declares `server.port: 28780`, `web_server.port: 28880`;
  `8111` absent. Runtime not re-verified because 28780 is held by an Eclipse RCP process
  (pid 467812) — conflict reported, not silently substituted.
- **EVO** — 28xxx assignments correct in `ports.properties` and inventory; 28080 is held by
  a foreign process (conflict reported).

## Build verification

DCode and DStudio were rebuilt (`clean verify`, Java 25) after the repair — **BUILD SUCCESS**
for both.

---

## Remaining deviations

1. **ENR runtime** blocked — port 28780 occupied by an Eclipse RCP process (pid 467812).
2. **EVO runtime** blocked — port 28080 occupied by a foreign process.
3. **Documentation** — several historical `PHASE-*.md` / `docs/*.md` reports in the product
   repositories still describe the superseded (crossed) port assignment; the operational
   docs (`AGENTS.md`) were corrected, historical reports left as point-in-time records.
