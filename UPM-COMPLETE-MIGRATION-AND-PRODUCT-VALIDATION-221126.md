# UPM — Complete Migration and Product Validation — 221126

Execution report for the end-to-end migration, build repair, and validation of all
UPM-managed projects and products, standardized to timestamp `221126`.

---

## A. Repository Migration

### A.1 Inventory

| Project | Previous location | New location | Git status | Migration |
|---|---|---|---|---|
| EVO (Evolution) | /home/petr/git/evolution-121126 | /home/petr/git/evolution-221126 | master (uncommitted changes present) | ✅ migrated |
| TRYST | /home/petr/git/tryst-121126 | /home/petr/git/tryst-221126 | master (uncommitted changes present) | ✅ migrated |
| EVO-Code (DCode) | /home/petr/git/DCoder-121126 | /home/petr/git/evo-code-221126 | master (uncommitted changes present) | ✅ migrated (prior task) |
| EVO-Studio (DStudio) | /home/petr/git/DStudio-121126 | /home/petr/git/evo-studio-221126 | master (uncommitted changes present) | ✅ migrated (prior task) |
| ENR | /home/petr/git/evo-native-runtime-161126 | /home/petr/git/enr-221126 | master (uncommitted changes present) | ✅ migrated |

### A.2 Summary

- **Repositories discovered:** 5 (UPM-managed).
- **Successfully migrated:** 5 / 5.
- **Repository directories updated in:** `workspaces/default/workspace.jsonc`,
  `workspaces/upm-itest/workspace.jsonc` (both checkouts), plus
  `publishUpdateSite` absolute paths, ENR `modelDirectories` absolute paths, and
  documentation (`EVO_WEB_UI_INVENTORY.md`, `UPM_PROJECT_DISCOVERY_REPORT.md`).
- **Git working-tree status:** all target repositories are on `master` and carry
  pre-existing **uncommitted changes that were preserved** (no repository was
  overwritten, deleted, reset, or copied). Only UPM's own configuration was edited.
- **Remaining migration problems:** none — no tracked UPM file references an
  obsolete timestamp directory (`121126`/`161126`).

---

## B. Product Build Results

Java baseline enforced: **Java 25** across all builds (the `{java21}` / `java-21`
references in UPM build commands were migrated to `{java25}` / `java-25`).

| Product | Repository | Build | Tests | Export | Status |
|---|---|---|---|---|---|
| ENR | enr-221126 | ✅ SUCCESS (13 modules) | ✅ 146/146 (0 fail) | ✅ enr-cli shaded jar | OK |
| EVO | evolution-221126 | ✅ SUCCESS (after repair) | ❌ test runtime launch failure | ✅ headless + RCP launchers | PARTIAL |
| TRYST | tryst-221126 | ✅ SUCCESS | ⚠️ 0 failures; live-agent E2E still running at report time | ✅ tryst launcher | PARTIAL |
| EVO-Code | evo-code-221126 | ✅ SUCCESS | ✅ 216/216 (0 fail, 1 skip) | ✅ dcode-cli/headless/ui | OK |
| EVO-Studio | evo-studio-221126 | ✅ SUCCESS | ⚠️ 222/225 (2 fail + 1 err, ENR-live) | ✅ evostudio launcher | PARTIAL |

Export verification: every product produced its native launcher under
`target/products/**/linux/gtk/x86_64/` — `evolution/evo`, `headless/eclipse`,
`tryst`, `dcode`, `dcode-headless`, `dcode-cli`, `evostudio`.

---

## C. Runtime Results

| Product | Startup | OSGi | Services | Health Check | Result |
|---|---|---|---|---|---|
| EVO-Code | ✅ (headless) | ✅ bundles resolved | HTTP API `58081` | `GET /health` → 200 `{"ready":true,"status":"UP"}` | RUNNING |
| EVO-Studio | ✅ (headless) | ✅ bundles resolved | HTTP API `48085` | `GET /api/status` → 200 `{"ready":true,"status":"UP"}` | RUNNING |
| TRYST | ✅ (headless) | ✅ bundles resolved | TCP `39180`, HTTP `38980` (dynamic) | started; orchestrator BLOCKED (DCode harness at wrong port) | PARTIAL |
| ENR | ❌ blocked | — | — | port `28780` occupied by Eclipse RCP pid 467812 | RUNTIME BLOCKED |
| EVO | ❌ blocked | — | — | port `28080` occupied by foreign process | RUNTIME BLOCKED |

Runtime note: the RCP/headless launchers defaulted to the system JDK (Java 8),
which fails with `UnsupportedClassVersionError`; the products were therefore
launched with `-vm /usr/lib/jvm/java-25-openjdk-amd64/bin/java` and verified on
Java 25. **Port discrepancy finding:** EVO-Code actually binds `58081` (not the
inventory's `48080`) and EVO-Studio binds `48085` (not `48081`) — a pre-existing
crossed port-block drift in the product codebases, not a UPM regression.

---

## D. Repairs Performed

### D.1 Java 25 baseline
- **Problem:** UPM build/test commands referenced `{java21}` and
  `/usr/lib/jvm/java-21-openjdk-amd64`, while all products require `release 25`.
- **Root cause:** stale workspace configuration from before the products' Java 25
  migration.
- **Fix:** replaced all `{java21}` → `{java25}` and
  `java-21-openjdk-amd64` → `java-25-openjdk-amd64` in workspace operation `env`.
- **Modified:** `workspaces/default/workspace.jsonc`, `workspaces/upm-itest/workspace.jsonc` (both checkouts).
- **Verification:** builds run under JDK 25; no `java21` references remain.

### D.2 EVO build failure (Windows-only OLE sources)
- **Problem:** EVO `clean verify -Plinux` failed with 70 errors —
  `org.eclipse.swt.ole.win32.OleFrame`/`OleClientSite` unresolvable.
- **Root cause:** 4 unreferenced Windows-only OLE demo classes
  (`Snippet264.java`, `Excel.java`, `SendEmail.java`, `FileExplorerView.java`)
  were compiled unconditionally; `org.eclipse.swt.ole.win32` does not exist on
  Linux.
- **Fix:** excluded those four files from the Tycho compile (sources preserved).
- **Modified:** `evolution-221126/eu.kalafatic.utils/pom.xml`.
- **Verification:** EVO `clean verify -Plinux` → BUILD SUCCESS, product exported.

### D.3 ENR authoritative ports
- **Problem:** `Ports.java` (stale checkout) listed ENR `8111` as IMPLEMENTED.
- **Root cause:** legacy ENR port (8111) still present in one checkout's inventory
  and the workspace `apiBase`.
- **Fix:** `Ports.java` → `ENR 28780` (API) + `ENR_WEB 28880` (Web UI); workspace
  `apiBase` → `http://127.0.0.1:28780/api/v1`.
- **Modified:** `UniversalProjectManager/src/main/java/upm/Ports.java`,
  both checkouts' `workspaces/default/workspace.jsonc`.
- **Verification:** `enr.yaml` already declares `server.port: 28780`,
  `web_server.port: 28880`; `Ports.java` no longer references 8111.

---

## E. UPM UI Status Verification

- **Build graphics/text:** new label mapping — `BUILDING` (progress), `BUILD OK`
  (green), `BUILD FAILED` (red), `TESTS OK`/`TESTS FAILED`, `TESTING`,
  `RUNNING`/`RUNTIME FAILED`. Implemented in `web/app.js` (`opBadge`,
  `runtimeBadge`); color coding retained from the raw status (`st-SUCCEEDED`,
  `st-FAILED`, `st-RUNNING`).
- **Build vs test separation:** maintained (label derived from operation `type`).
- **Automatic refresh:** existing SSE (`/api/events`) + `refreshState()`; the
  updated `app.js` is served live (verified via `GET /app.js` returning the new
  labels).
- **Persisted status:** derived from the persisted operation history
  (`state/operations`, `state/history.json`); no stale `BUILDING`/`TESTING`
  indicators are left once an operation reaches a terminal status (raw status is
  authoritative).

---

## F. Remaining Problems (unresolved, disclosed)

1. **EVO test-runtime launch failure** — `eu.kalafatic.evolution.controller.tests`
   fails to launch its OSGi test runtime with
   `ClassNotFoundException: eu.kalafatic.evolution.view.editors.pages.aichat.ProviderModelSelectionSupportTest`.
   Two test classes carry a `view`-bundle package while living in the
   `controller.tests` bundle (`ProviderModelSelectionSupportTest.java`,
   `EvoTableViewerHelperTest.java`). Pre-existing structural defect; uncommitted
   changes on those files were not touched.
2. **EVO-Studio ENR live tests fail** — `EnrLiveInferenceTest` (2 fail + 1 error)
   because no ENR instance is reachable (port 28780 occupied). Environment
   dependency, not an application defect; the other 222 tests pass.
3. **TRYST live-agent E2E** — `OpenCodeEndToEndWorkflowTest` was still executing at
   report time (live OpenCode harness / LAN dependent). All completed TRYST tests
   passed.
4. **Port-block drift** — EVO-Code binds `58081`, EVO-Studio binds `48085`,
   contradicting the UPM `Ports.java` inventory (48xxx for EVO-Code, 58xxx for
   EVO-Studio). Product-codebase issue, not a UPM configuration error.
5. **ENR runtime blocked** — port `28780` is held by an Eclipse RCP process
   (pid 467812, `-Declipse.pde.launch=true`). Not killed (user process); requires
   freeing 28780 to run the standalone ENR server.
6. **EVO runtime blocked** — port `28080` is held by a foreign process.

---

## G. Final Summary (numerical)

- Repositories discovered / migrated: **5 / 5**.
- Products discovered: **5**.
- Products successfully built: **5** (EVO after repair).
- Products successfully exported: **5**.
- Products with all automated tests passing: **2** (ENR 146, EVO-Code 216);
  TRYST tests passing at time of report (E2E pending); EVO-Studio 222/225;
  EVO test runtime could not launch.
- Products successfully started (headless): **3** (EVO-Code, EVO-Studio, TRYST).
- Products passing runtime health check: **2** (EVO-Code `ready:true`, EVO-Studio `ready:true`).
- Products blocked at runtime (port conflict): **2** (ENR 28780, EVO 28080).
- Failed tests (non-environmental): **0** confirmed; EVO-Studio's 3 failures and
  TRYST's E2E are external-service/environment dependent.
- Repairs performed: **3** (Java 25 baseline, EVO OLE exclusion, ENR authoritative ports).
- Unresolved issues: **6** (see section F).
