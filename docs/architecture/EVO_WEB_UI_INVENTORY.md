# EVO Family Web UI Inventory

Inventory of all web interfaces of the EVO ecosystem, with their differences
from the UPM reference and the changes required for unification.
The design standard itself: [UPM_WEB_UI_STANDARD.md](UPM_WEB_UI_STANDARD.md).
Startup modes / web server lifecycle: [WEB_SERVER_LIFECYCLE_STANDARD.md](WEB_SERVER_LIFECYCLE_STANDARD.md).

Reference implementation (authoritative):
`UniversalProjectManager/web/` — `index.html` (215 ln), `app.css` (413 ln),
`app.js` (3191 ln), `charts.js` (274 ln), `graph.js` (417 ln).
Vanilla JS + CSS variables, light/dark themes, sticky topbar SPA.

## Migration status (2026-11-21)

| App | Visual standard | Theme pair + toggle | Lifecycle config | RCP Web menu | Build |
|---|---|---|---|---|---|
| UPM | reference (unchanged) | n/a (unchanged) | `webServerEnabled`/`webServerAutoStart` in `config/application.jsonc` + Settings → Runtime tab | n/a (headless-only app) | jar built, smoke-tested ✓ |
| EVO | done (EvoStyleManager + evo-style.css + 13 pages + view bundle + servers portal + cognitive/help) | light (UPM palette), dark tokens available | EMF `ServerSettings.enabled` (new) + `autoStart` (existing), port `ServerSettings.port`, bind address via NetworkInterfaceHelper | Server → Web Interface (Open/Open URL/Start/Stop/Restart/Status) | javac-verified (model/controller/view) ✓ |
| ENR | done (style.css/index.html/app.js) | dark default + light + Theme toggle | `web_server.enabled` / `web_server.auto_start` in `enr.yaml` | n/a (server-only product) | `mvn compile` ✓ |
| DCode | done (app.css/help.html/topbar + Theme toggle) | dark default + light + Theme toggle | `webServerEnabled`/`webServerAutoStart` in `~/.dcode/dcode-config.json` (port=`httpPort`, bind=`bindAddress`) | Web Interface menu + headless CLI `server-*` commands | Tycho compile ✓ |
| DStudio | done (style.css/help.html/topbar + Theme toggle) | dark default + light + Theme toggle | `webServer.enabled`/`webServer.autoStart` in `~/.dstudio/dstudio.properties` (port=`teip.port`, bind=`teip.bindAddress`) | Web Interface menu | Tycho compile + test-compile ✓ |
| TRYST | done (embedded SPA CSS + help.html + Theme toggle) | light default + dark + Theme toggle | `webServer.enabled`/`webServer.autoStart` in `~/.tryst/tryst.properties` (port=`server.http.port`, bind=`server.bindAddress`) | Web menu (was empty) filled with lifecycle controls | Tycho compile ✓ |

Screenshots: not capturable in this session (no headless-browser sandbox available on
the live desktop); visual verification is based on token/component audits of the
migrated files and build verification.

---

## 1. UPM — Universal Project Manager

| Aspect | Value |
|---|---|
| Entry point | `web/index.html` |
| Server | Java 21 single-jar, `com.sun.net.httpserver` (`src/main/java/upm/Server.java`), port 29080 |
| UI files | `web/index.html`, `web/app.css`, `web/app.js`, `web/charts.js`, `web/graph.js` |
| API | `/api/state`, `/api/events` (SSE), `/api/ops`, `/api/projects`, `/api/settings`, `/api/graph`, … |
| Status | **REFERENCE — no changes** |

## 2. EVO — Evolution Platform (`evolution-121126`)

| Aspect | Value |
|---|---|
| Entry points | `eu.kalafatic.evolution.controller/src/eu/kalafatic/evolution/controller/orchestration/*.html` served by `EvolutionServer` (NanoHTTPD), port **28080**; portal `eu.kalafatic.evolution.servers/src/.../servers/web/` (login.html, dashboard.html) served by servers-bundle `EvolutionServer`, port **28180**; MCP demo port 28280 |
| UI files | orchestration: `index.html`, `chat.html`, `admin.html`, `mcp.html`, `develop.html`, `mutation.html`, `forge.html`, `teip.html`, `evolution_tree.html`, `cognitive.html`, `help.html` (+ dead duplicates `forge2.html`==`forge3.html`); template/genome for architecture; view bundle: `view/chat.html` + 7 css modules + 7 js modules, `view/workflow/workflow.html`, orphaned `chat2.html`/`chat3.html`; servers: `login.html`, `dashboard.html`, `css/style.css`, `js/auth.js` |
| Shared CSS | `EvoStyleManager.java` (Java text block, injected into 9 pages) + packaged `evo-style.css` (360 ln), `creatic.css` (196 ln), `shared.css`, `architecture.css` (581 ln), `genome.css` |
| Current design | Light "workstation" theme: bg `#e8ecef`, accent `#1d5296`, Segoe UI 11–12 px, gradient buttons, blue-uppercase panel headers; dark outliers: `cognitive.html` (`#1e1e2e`), `chat3.html` (`#1e1e1e`); XP-style window chrome on login/dashboard; bootstrap-blue `#007bff` on mcp.html; `#evo-global-nav` topbar copy-pasted inline in 6 pages |
| Embedding | SWT Browser tabs + dedicated views (ChatGroup, ForgePage, AdminPage, …); `auth-integration.js` detects SWT |
| Differences vs UPM | Palette, gradient buttons, uppercase panel headers, per-page duplicated nav, missing dark theme, inconsistent outliers |
| Required changes | Align EvoStyleManager tokens/component CSS to UPM standard (keep `--evo-*` names as aliases); rewrite packaged `evo-style.css` identically; replace hardcoded palette values in page-inline CSS with token refs; unify `#evo-global-nav` into UPM topbar look (brand mark + nav pills + actions) in the injected CSS; align servers `css/style.css`, login/dashboard; align cognitive.html and view-bundle chat CSS modules to UPM tokens (dark theme allowed); align help.html |

## 3. ENR — EVO Native Runtime (`evo-native-runtime-161126`)

| Aspect | Value |
|---|---|
| Entry point | `enr-server/src/main/resources/web/index.html` (47 ln) |
| Server | Java 21/25 Maven, `com.sun.net.httpserver` + virtual threads, `WebUiHandler`, port **8111** |
| UI files | `index.html`, `style.css` (141 ln), `app.js` (1095 ln) |
| Pages | Dashboard, Models, Model Manager, Providers, Runtime, Playground, Diagnostics; Bearer token via "Key" button (localStorage) |
| Current design | Own palette (to be checked) — small hand-rolled SPA |
| Embedding | None (server + external browser) |
| Required changes | Replace `style.css` token set with UPM tokens (light+dark), align components (topbar, panels, tables, buttons, toasts, progress), align `index.html` header to UPM topbar structure |

## 4. DCode (`DCode-121126`)

| Aspect | Value |
|---|---|
| Entry point | `plugins/eu.kalafatic.dcode.server/web/index.html` (93 ln) |
| Server | NanoHTTPD `DCodeHttpServer`, port **48080** (range 48080–48579); SPA fallback to index.html; SSE `/api/v1/events/stream` |
| UI files | `index.html`, `help.html` (239 ln, own inline palette), `css/app.css` (565 ln), `js/{api,sse,diff,explorer,editor,sessions,tooling,runtimes,models,app}.js` (~3400 ln) |
| Layout | `#topbar` + left nav panel (280 px) + center tabs/editor + right panel (300 px) + bottom console (200 px) |
| Current design | **Dark-only** (`--bg-0 #1a1d23`, accent `#4d9de0`), CSS variables on `:root`, no theme toggle; `help.html` uses a divergent inline palette (accent `#4da3ff`) |
| Embedding | Served from OSGi bundle; headless CLI; also opened in browsers |
| Required changes | Replace token values with UPM light+dark sets (keep dark as default runtime theme, matching UPM); add UPM-style Theme toggle in `#topbar`; align buttons/panels/tables/badges/modals/context menus; add missing variable definitions used by JS inline styles (`--danger`, `--panel-2`, …); align `help.html` inline palette |

## 5. DStudio (`DStudio-121126`)

| Aspect | Value |
|---|---|
| Entry point | `plugins/eu.kalafatic.dstudio.teip/web/index.html` (72 ln) |
| Server | JDK `com.sun.net.httpserver` `DStudioServer` + `WebAssetsHandler` (bundle classpath `/web/*`), port **48080** |
| UI files | `index.html`, `style.css` (89 ln), `app.js` (816 ln), `help.html` (233 ln, duplicated inline palette) |
| Layout | `#topbar` + sidebar 360 px (tree/AI/model-manager/tasks/events panels) + editor area + ctx menu/toast |
| Current design | Dark-only: `--bg #1e1f26`, accent `#4f8cff`; palette duplicated in style.css + help.html |
| Embedding | Embedded browser served by the same backend (one authoritative core) |
| Required changes | Replace token set with UPM light+dark (dark default), add Theme toggle in `#topbar`, align panels/tabs/buttons/toasts/ctx-menu, unify help.html palette |

## 6. TRYST (`tryst-121126`)

| Aspect | Value |
|---|---|
| Entry point | Web SPA embedded as Java text block `APP_HTML` (~4000 ln) in `plugins/eu.kalafatic.tryst.infrastructure/src/eu/kalafatic/tryst/infrastructure/server/TrystHttpServer.java` (lines 4617–8593); plus `src/web/help.html` (216 ln) |
| Server | JDK `com.sun.net.httpserver`, port **38080** (range 38080–38580; 8080 rejected), context path `/tryst`; SSE `/api/notifications/stream`; TCP 38280 |
| UI files | `src/web/help.html` + inline SPA (dashboard, projects, queue, reviews, workspaces, pools, workflows, studio, administration) |
| Current design | Own card-based theme (to be checked); help.html light card-based docs |
| Embedding | SWT `EmbeddedBrowserView`, `StudioEditorPage`, status/progress composites |
| Required changes | Align SPA inline CSS tokens/components to UPM standard (both themes); align help.html palette and structure to UPM typography; keep all existing endpoints untouched |

## 7. Duplicates / dead files (documented, not migrated)

- `evolution-121126/.../orchestration/forge2.html` == `forge3.html` (unreferenced)
- `.../orchestration/mcp.html` duplicated in `servers/mcp/demo/`
- `template.html`, `genome.html` duplicated in `src/` and bundle root (root authoritative)
- `view/chat2.html`, `view/chat3.html` orphaned prototypes (packaged only)
- `evolution.module/index.html` — Eclipse Help XSLT template (not a web UI)

## 8. Migration priority

1. **EVO** — largest visual distance, largest page count (14 live pages).
2. **DCode** — dark-only divergence, big CSS + JS inline fallbacks.
3. **DStudio** — small, quick win.
4. **ENR** — small SPA.
5. **TRYST** — inline Java SPA (highest edit risk), help page.

## 9. Verification plan per application

- Static: token audit (no palette outside `:root`/`[data-theme]` blocks),
  component-class audit against the standard.
- Build: EVO controller bundle (javac-level check for EvoStyleManager),
  TRYST infrastructure bundle (javac-level check for TrystHttpServer),
  ENR `mvn -pl enr-server package` if feasible, UPM `build.sh`.
- Runtime: serve pages from the respective servers and compare against UPM
  in light and dark themes; verify no JS errors, no broken controls.
- Embedded: SWT Browser pages keep working (no ES modules/workers added).
