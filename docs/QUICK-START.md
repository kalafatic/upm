# Universal Project Manager — Quick Start

## The shortest path (no terminal needed)

1. **Double-click the desktop icon** — `Universal Project Manager`.
2. **Wait for the dashboard** — the browser opens automatically when the
   application is ready (a few seconds).
3. **See your projects** — the initial workspace contains EVO, TRYST,
   DCode, DStudio and ENR.
4. **Click Build, Run or Test** on a project row.
5. **Watch progress** — Operations shows the live log, current module/tests
   and the result.
6. **Open Logs if something fails** — every project has a Logs tab and an
   error message with a suggested next step.
7. **Click Stop when finished** — or Stop All in the top bar.

That is the whole daily workflow.

## Adding your own project

**Projects → Add Project** → pick “Existing local directory” or “Git
repository URL” → Detect → review the detected type, confidence and proposed
commands → Register. For Git URLs, the repository is cloned into the
directory you choose (existing directories are never overwritten).

## Where everything lives

| What | Where |
|---|---|
| Application | `/home/petr/git/UniversalProjectManager` |
| Executable JAR | `universal-project-manager.jar` |
| Interface | `http://127.0.0.1:29080/` (configured in `config/application.jsonc`) |
| Workspace configuration | `workspaces/default/workspace.jsonc` |
| Runtime logs per project | `state/runtime/<Project>/<launch>/runtime.log` |
| Operation logs | `state/operations/<id>.log` |
| Backups | `state/backups/` |
| Desktop launchers | `~/Desktop/Universal Project Manager.desktop`, applications menu |

## Terminal fallback

```bash
/home/petr/git/UniversalProjectManager/bin/upm-menu.sh   # menu
/home/petr/git/UniversalProjectManager/bin/upm.sh help   # full CLI
```

## Run modes

- **EVO** — Headless server · Product server-only · RCP (UI). EVO ports are
  deterministic: if 28080 is taken by another application, use an explicit
  port (`upm.sh run EVO headless 28100`); the app remembers the last working
  port for Launch All.
- **TRYST / DCode / DStudio** — Headless server or RCP (UI).
- **ENR** — native runtime server; the ENR monitor (dashboard widgets) shows
  devices, models, load/unload and inference activity.

## Workspaces, profiles, configuration

- **Workspaces**: independent project sets — create, switch, export (ZIP),
  import (with path remapping for another machine), backup and restore.
- **Profiles**: pick a project subset and a startup order; Launch Profile
  starts them dependency-aware; Stop Profile stops them.
- **Save/Restore**: the workspace auto-saves on every change; Export/Import
  moves a configuration to another machine; Backups are validated before
  restore.

## Dashboard customization

**Edit Layout** on the dashboard: add widgets from the palette, drag to
reorder, use the corner handle to resize, toggle or remove with the widget
buttons (or the ↑ ↓ buttons for keyboard users). **Save Layout** persists
across restarts; **Reset Layout** restores the default.

## Stopping everything safely

1. **Stop All** (top bar) — stops managed runtimes gracefully.
2. Optionally stop the application itself:
   `Universal Project Manager (Stop)` desktop entry or
   `bin/upm.sh shutdown`.

Project processes keep running when the application stops, and projects can
always be built or launched with their own existing scripts as before.
