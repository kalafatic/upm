# Universal Project Manager

A standalone, portable, minimalist application for managing software
development projects: add, build, test, launch, monitor, stop — from one
simple dashboard.

The five Darwin projects (EVO, TRYST, EVO-Code, EVO-Studio, ENR) are the initial
demonstration workspace. They are **not** hardcoded dependencies: the
application works with arbitrary Maven, Gradle, Eclipse RCP/Tycho/OSGi,
executable-JAR, native CMake/C/C++, generic and custom projects.

---

## 1. Distribution and portability

```text
UniversalProjectManager/
├── universal-project-manager.jar   ← the whole application (one executable JAR)
├── launch.sh                       ← start script (single instance)
├── launch.desktop / launch-stop.desktop
├── build.sh                        ← rebuilds the JAR (javac + jar, JDK 21)
├── config/application.jsonc        ← application settings (port, theme, paths…)
├── workspaces/
│   └── default/workspace.jsonc     ← project registry (JSONC, human-editable)
├── state/                          ← runtime state, operation logs, backups
├── logs/                           ← application log
├── web/                            ← UI sources (embedded into the JAR)
├── src/main/java/upm/              ← backend sources (zero dependencies)
└── docs/
```

- One JAR starts everything: embedded HTTP server + web UI + browser launch.
- Relocatable: copy the folder anywhere (or to another machine), adjust
  `config/application.jsonc` and remap repository paths in
  `workspaces/<id>/workspace.jsonc`. Missing repositories are **reported**,
  never silently replaced.
- No hardcoded `/home/petr`, repository paths, ports or GPU assumptions in
  the source. JDKs and tools are detected at runtime (see Settings → Tools).
- Single instance: a second start (desktop click, `launch.sh`, JAR) detects
  the running instance and just opens the browser — no duplicate servers.
- Clean shutdown: the Stop desktop entry or `bin/upm.sh shutdown` (managed
  project processes are left untouched; use Stop All first if desired).

## 2. User interface

Minimal navigation: **Dashboard · Projects · Workspaces · Operations ·
Settings**.

- **Dashboard** — compact project table (name, type, git branch + clean/dirty
  state, build, tests, runtime, last operation, warnings) with small actions
  (Open, Build, Run, Stop, More), plus an optional WYSIWYG widget grid
  (Edit Layout: add/remove/move/resize/toggle, Save/Cancel/Reset; layout is
  stored per workspace in `dashboard.jsonc` and survives restarts).
- **Projects** — detail view with tabs: Overview, Git, Build, Launch, Logs,
  Configuration. **Add Project** wizard: existing local directory or Git URL
  (HTTPS/SSH/file); detection preview (type, confidence, evidence, proposed
  commands) before registration; clone never overwrites an existing
  directory. Edit (basic + advanced JSON), Duplicate, Unregister
  (repository untouched) and Delete Local Repository (separate, explicit,
  path shown, protected paths refused).
- **Workspaces** — multiple independent workspaces (create, rename,
  duplicate, switch, export ZIP, import with preview + path remapping,
  backup/restore, archive/delete — repositories are never deleted), and
  **profiles** (project selection, startup order, dependency-aware launch,
  stop order).
- **Operations** — live operations with phase, module/test progress, live
  logs, cancel, error details and full history.
- **Settings** — General (theme, default workspace), Paths, Runtime (host,
  port, browser), Tools (automatic detection), Monitoring (refresh,
  retention). Validated before saving.

Light and dark themes; keyboard-accessible controls; no decorative
animations; color is used for state (success/warning/error/running/stopped).

## 3. Project adapter architecture

Project-specific logic lives in built-in, configuration-driven adapters
(`upm.Adapters`), never in the UI:

```java
interface ProjectAdapter {
    String typeId();
    DetectionResult detect(Path repoDir);   // read-only inspection
    Set<String> supportedOperations();
    Map<String, CommandBlock> proposeOperations(Path repoDir, Map<String,String> env);
    boolean supportsLaunchKind(String kind);
}
```

| Adapter | Detection evidence |
|---|---|
| `maven` | `pom.xml` (Tycho content escalates to the Eclipse adapter) |
| `gradle` | `build.gradle(.kts)`, `settings.gradle`, wrapper |
| `eclipse-tycho` | Tycho POM, `plugin.xml`, `MANIFEST.MF`, `*.product`, `feature.xml` (covers Eclipse RCP / PDE / OSGi) |
| `executable-jar` | JARs with `Main-Class` |
| `native-cmake` | `CMakeLists.txt` |
| `generic` | build scripts (`Makefile`, `build.sh`, …) |
| `custom` | fully user-defined commands and launches |

Detection confidence is always labeled: `DETECTED`, `PROPOSED`,
`USER_CONFIRMED`, `UNKNOWN`, `UNSUPPORTED` — inferred launches are presented
for review, never presented as verified. Repository inspection is read-only;
no repository script is executed automatically. Adding a project that an
existing adapter can handle requires **no source-code changes**.

## 4. Git management

Per project (Git tab): fetch, pull, push (explicit confirmation), commit
(message dialog), branch listing/switching/creation, status, changed files
(staged/modified/untracked), recent commits. Branch switching is refused
while uncommitted changes exist. Nothing is committed, pushed, reset or
cleaned automatically. Credentials are never stored in configuration — the
normal Git credential helpers / SSH agent are used.

## 5. Build, test, install, run

Adapter-defined operations per project: Build, Clean Build, Resolve
Dependencies, Test, Package, Verify, Install — with live progress (Maven
reactor modules, surefire counters), logs and accurate results. Install
means the project's own install mechanism (e.g. `mvn install` into the local
repository) — never OS deployment.

Launch management: multiple launch configurations per project (Eclipse
product, Eclipse application, JAR, native, HTTP server, custom command),
add/edit/duplicate/delete/default; per-config launcher, args, environment,
port policy (deterministic ports fail fast with the owner when occupied),
readiness probe (fixed port, ports file, allocations file, port range,
config file) and ready markers, stop grace, startup timeout. Runtime state
shows PID, launch config, start time, uptime, bound port, log location.
Processes are stopped only after PID-file ownership verification
(pid + process group + `/proc` starttime + owner), SIGTERM → grace →
SIGKILL per process group; broad process killing is never used; the daemon
refuses to signal its own group. Crashes are detected within seconds
(FAILED + notification).

## 6. Configuration management

- **Structure**: `config/application.jsonc` (application) +
  `workspaces/<id>/workspace.jsonc` (workspace: projects, profiles,
  dashboard) + `workspaces/<id>/profiles/` + `state/` (runtime, operations,
  backups) + `logs/`. Configuration is separated from volatile state.
- **Validation** on load and import: syntax, schema, duplicate ids, path
  references, adapter availability, missing repositories, launch
  configuration integrity, dependency references. Errors are reported;
  unknown properties are preserved (not silently discarded).
- **Export** creates a portable configuration package (ZIP: workspace +
  profiles + dashboard metadata; never repositories or model files).
  **Import** previews projects, shows missing local paths, supports path
  remapping and workspace renaming, validates before saving.
- **Backup/Restore** per workspace (validated before restore). The version
  field enables future migrations.

## 7. Terminal fallback

```bash
UniversalProjectManager/bin/upm-menu.sh     # numbered interactive menu
UniversalProjectManager/bin/upm.sh help     # full CLI
```

CLI: `list`, `status`, `doctor`, `report`, `build|clean-build|update|test|
package|verify|install <project|all>`, `run <project> [launchConfig] [port]`,
`stop <project|all>`, `restart`, `launch-all`, `stop-all`, `restart-all`,
`logs`, `diagnostics`, `git-status|git-fetch|git-pull|git-push`,
`ws-list|ws-switch|ws-create|ws-export`, `profile-launch|profile-stop`,
`gui`, `shutdown`. Every command delegates to the same backend.

## 8. Safety rules (enforced, not just documented)

- Repository discovery is read-only; nothing executes automatically.
- Commands are shown before first execution; destructive actions
  (push, delete repository, delete workspace, restore) require explicit
  confirmation; the web API accepts only fixed operation verbs — arbitrary
  shell commands cannot be issued through it.
- Uncommitted Git changes are protected (switch refusal, no auto reset).
- Unregistering a project never deletes its repository or user data.
- The HTTP server binds 127.0.0.1 by default; secrets are never stored in
  configuration or logs (operation commands are displayed without env
  values).
- Model loading (ENR) is an explicit user action; nothing downloads models
  automatically.

## 9. Initial workspace

`workspaces/default/workspace.jsonc` registers the five Darwin repositories
with their real, discovered workflows (Tycho builds, per-suite test
commands, exported product launchers, readiness probes, port sources).
ENR is a standalone custom project using its own `mvn`/`EnrCli` commands;
the ENR monitor (runtime, devices, models, load/unload, inference metrics)
uses the existing ENR `/api/v1` contract — the control center never replaces
it. The initial paths are configuration values, not application code.

## 10. Building from source

```bash
UniversalProjectManager/build.sh        # compiles backend + embeds web → JAR
UniversalProjectManager/launch.sh       # starts the application
```

Requires JDK 21 (javac/jar only — no third-party libraries).

---

## 11. Professional Light UI

The default theme is a restrained professional **light** design: light
neutral surfaces, subtle gray separators, dark typography, a single blue
accent, compact tables, minimal shadows, no gradients/glows/decorative
animations. Color communicates state only (success / warning / error /
running / stopped / selected). A dark theme remains available in Settings →
General (the light theme is the default and the primary design target).
The same visual language applies to every view including the Architecture
Explorer and Monitoring charts.

## 12. External project discovery

**Projects → Discover** opens the discovery workflow:

- **Scan a directory**: recursively inspects a user-selected root (never the
  whole filesystem) with configurable depth and exclusion rules
  (`.git`, `node_modules`, `target`, `build`, `.gradle`, …). Detects
  repositories (`.git` dirs/files), projects and modules via build
  descriptors. Results show name, path, type, confidence, git branch,
  module count, launch capabilities and registration status.
- **Inspect a remote repository**: `git ls-remote` only (no clone) — URL
  validation, reachability, default branch, branch list.
- **Actions per result**: Register, Inspect (read-only detection preview),
  Ignore/Unignore, Rescan. Rescans never duplicate registrations; already
  registered repositories are marked; ignored paths stay ignored.
- **Moved repositories**: when a registered project's path disappears but a
  discovered repository with the same name exists, the app proposes an
  explicit **remap** (never a silent path change).
- **Multi-project repositories**: modules are reported; you can register the
  whole repository or a subdirectory; several modules of one repository can
  be registered without extra clones.

Discovery configuration (scan roots, exclusions, results, ignore list, last
scan time) persists per workspace in `workspaces/<id>/discovery.jsonc`.

Supported ecosystems (detection + proposed commands, tools detected at
runtime): Maven, Gradle, Eclipse RCP/PDE/Tycho/OSGi, executable JAR, C/C++
(CMake and Make), Rust/Cargo, Go, Python, Node.js, Docker, generic and
custom. Missing toolchains are reported, not faked; nothing executes
automatically.

## 13. Architecture Explorer

A dedicated **Architecture** view with six perspectives built from real
evidence:

| Perspective | Sources |
|---|---|
| Workspace Overview | workspace configuration, launch configs, endpoints, profiles |
| Runtime Topology | `/proc` processes (parent-child = SPAWNS), listening sockets (PROVIDES) |
| Execution Flow | the operations registry: request → operation → project → process |
| AI / LLM Topology | ENR `/api/v1` (runtime, models, sessions, devices), Ollama `/api/tags`, provider-client source evidence |
| Project Dependencies | `startupAfter` configuration + build-descriptor module lists |
| Infrastructure | CPU/RAM/GPU measurements, managed processes, `nvidia-smi` compute apps |

Every node and relationship carries an explicit classification —
`VERIFIED_RUNTIME`, `VERIFIED_STATIC`, `USER_CONFIRMED`, `INFERRED`,
`UNKNOWN` — plus evidence and timestamps. Inferred relationships are never
presented as verified calls (e.g. provider-client links from source-file
presence are labeled INFERRED; dash-dotted edges).

Interactions: zoom (wheel), pan, fit-to-screen, reset layout, drag nodes,
select nodes/relationships (Ctrl for multi-select), highlight connected
nodes, search, filter by project and type, details panel (double-click a
project node to open it), print, and export to **SVG, PNG, JSON, CSV**.
Custom layouts persist per workspace and perspective
(`workspaces/<id>/diagram-layouts.jsonc`).

## 14. Live system monitoring

A dedicated **Monitoring** view with real measurements only:

- **CPU** — overall and per-core utilization, load average, frequency.
- **RAM** — used/available, swap usage, JVM memory.
- **GPU** (NVIDIA via `nvidia-smi`, optional) — model, utilization, VRAM,
  temperature, power draw, driver, active compute processes with per-process
  VRAM. No GPU → a clear unavailable state; no invented metrics.
- **Storage** — filesystem usage, per-repository sizes, model storage.
- **Network** — aggregate throughput from `/proc/net/dev`.
- **Per-process table** — project, PID, CPU, RSS, GPU/VRAM, start time,
  uptime, status; only processes with established ownership are associated
  with projects.
- **LLM monitoring** — providers (ENR runtime + Ollama when reachable),
  loaded models vs. model files, active sessions, request counts, token
  throughput, and system-wide GPU/VRAM usage (no invented per-model
  attribution).

**Live charts** (CPU, RAM, GPU, VRAM, temperature, power, network,
per-project process usage, inference activity): live updates over SSE,
pause/resume, time-range selection (5 min … 6 hours), hover details with
timestamps, legend, CSV export, print-friendly layouts. Sampled data is
labeled by timestamp and unit; missing metrics show "no data" explicitly.
Sampling interval and history retention are configurable
(Settings → Monitoring / `/api/metrics/config`); history buffers are
bounded. GPU queries run at the sampling interval, never per refresh.

The Universal Project Manager remains an observer and administrator — no
second session manager, no duplicated orchestration logic, no fabricated
telemetry.
