# UPM (Universal Project Manager)

A standalone, portable, minimalist application for managing software
development projects: discover, add, build, test, launch, monitor and stop —
from one simple dashboard.

- **One executable JAR** (`build.sh` → `universal-project-manager.jar`): embedded
  HTTP server + web UI, opens the browser, single-instance, clean shutdown.
- **Universal project administration** — Maven, Gradle, Eclipse RCP/Tycho/OSGi,
  executable JAR, C/C++ (CMake/Make), Rust/Cargo, Go, Python, Node.js, Docker,
  generic and custom projects. Read-only discovery, Git management, build/test/
  install operations, multiple launch configurations, safe process lifecycle.
- **Architecture Explorer** — interactive, evidence-based diagrams (workspace
  overview, runtime topology, execution flow, AI/LLM topology, dependencies,
  infrastructure) with verified/inferred classification, layout persistence and
  SVG/PNG/JSON/CSV/print export.
- **Live system monitoring** — CPU (per-core), RAM, NVIDIA GPU/VRAM/temperature/
  power, network, per-process metrics, historical charts, local LLM monitoring
  (ENR runtime and Ollama, model inventory, sessions, token throughput).
- **Multiple workspaces, profiles, export/import** with path remapping, backups,
  WYSIWYG dashboard, light professional theme by default (dark optional).

Zero runtime dependencies beyond a JDK (21+). Configuration is JSONC
(`config/application.jsonc`, `workspaces/<id>/workspace.jsonc`) and everything
is relocatable — copy the folder to another machine, remap repository paths,
import/export workspace packages.

## Quick start

```bash
./build.sh     # compiles the backend + embeds the web UI into the JAR
./launch.sh    # starts the application and opens the browser
```

Or double-click `launch.desktop` after running `bin/install-desktop.sh`
(desktop paths in `launch.desktop` are examples — adjust them or use the
installer). Terminal fallback: `bin/upm.sh help` and `bin/upm-menu.sh`.

The initial workspace (`workspaces/default/workspace.jsonc`) demonstrates five
registered Darwin projects; its repository paths are machine-specific
configuration values. Add your own projects via **Projects → Add Project** or
the **Discover** workflow.

## Layout

```text
src/main/java/upm/     backend sources (plain Java 21, no dependencies)
web/                   UI sources (vanilla JS/CSS, embedded into the JAR)
bin/                   CLI (upm.sh), terminal menu, desktop installer
config/application.jsonc       application settings
workspaces/default/workspace.jsonc   project registry (JSONC)
docs/                  full documentation
```

See [docs/UNIVERSAL-PROJECT-MANAGER.md](docs/UNIVERSAL-PROJECT-MANAGER.md)
for architecture, project adapters, safety rules and the complete feature
reference, and [docs/QUICK-START.md](docs/QUICK-START.md) for the daily
workflow.
