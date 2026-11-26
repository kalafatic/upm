# UPM Compatibility Audit

Universal Project Manager (UPM) — audit of compatibility with the new parent/feature/build/launch/update structure of EVO, TRYST, EVO-Code, EVO-Studio, ENR.

## Architecture overview (as audited)

| Component | File | Role |
|---|---|---|
| Adapters | `Adapters.java` | Built-in, configuration-driven adapters: maven, gradle, eclipse-tycho, executable-jar, native-cmake, make, cargo, go, python, node, docker, generic, custom |
| Detection | `Adapters.detect / detectStructure / detectModules` | Read-only repository inspection |
| Scanning | `Scanner.java` | Recursive discovery of repos/projects, remote inspection (ls-remote) |
| Operations | `Ops.java` | Command execution engine: build/cleanBuild/update/test/package/verify/install/validate/buildModule/run/stop/restart + git ops + checkUpdates/updateAll |
| Git | `GitOps.java` | Git CLI wrapper; read-only by default; pull is `--ff-only` |
| Processes | `Proc.java` | Launch/stop/readiness/crash detection, PID files, `{java21}/{java25}/{enrCp}` templates |
| Config | `Config.java` | Workspace projects with operations, test suites, launch configs, **structure snapshot** (new) |
| State | `State.java` | Op registry/history, project runtime state |
| Server | `Server.java` | REST API + embedded web UI |
| UI | `web/` | Project tabs: Overview, **Structure (new)**, Git, Build, Launch, Logs, Configuration |

## Assumptions found and corrected

| # | Old assumption | Compatibility problem | Correction |
|---|---|---|---|
| 1 | Tycho detection routed through the Maven adapter produced no product launches | EVO/TRYST/EVO-Code/EVO-Studio registered with **empty launch configs** | Maven adapter now delegates to the Tycho adapter for detection → product launches are proposed |
| 2 | Built product launchers searched only 4 levels below `target/products` | Launchers live 5 levels deep (`…/products/<id>/<os>/<ws>/<arch>/<name>`); EVO-Studio/TRYST/EVO-Code launches were missed | Depth raised to 6 |
| 3 | Product launcher name whitelist (`eclipse, evo, tryst, dcode, dstudio, …`) | EVO's `evolution` launcher (and future apps) not covered | Generic executable scan — no hardcoded application names |
| 4 | JAR launches only from the repository-root `target/` | ENR CLI lives in `enr-cli/target/` | Module target dirs scanned for Main-Class JARs |
| 5 | JAR launch configs executed directly via `setsid <jar>` | JARs are not executable — launch failed | `Proc.start` special-cases kind `jar`: `setsid <java> -jar <jar>`; java binary resolved from launch env (`{java25}`) |
| 6 | Shade-plugin artifacts proposed as launchers | Duplicate `original-*.jar` launch configs for ENR | `isRunnableJarName` filters `original-*`, `*-shaded`, `*-tests`, sources/javadoc |
| 7 | Ops proposals always used the wizard-provided JDK 21 | ENR reactor (release 25) built with JDK 21 → failure | `javaEnv` selects the JDK matching `requiresJava` detected from module POMs (`<release>` / `maven.compiler.release`) |
| 8 | No module-level build | "Build Selected Module" requirement | New `buildModule` op: `<mvn> -B [-DskipTests] clean verify -pl <module> -am`, validated against persisted reactor modules |
| 9 | No `validate` op | Validation requirement | Added to maven/eclipse-tycho adapters (`mvn -B validate`) |
| 10 | Update = plain fetch/pull buttons only | "Update All" orchestrated workflow requirement | New `checkUpdates` (fetch + divergence) and `updateAll` (safe gated workflow) ops |
| 11 | Structure metadata not persisted | Hierarchy must be displayed and refreshable | New persisted `structure` block (parentPom, modules+roles, features, products, targetPlatforms, eclipseLaunchConfigs, requiresJava) + `GET /structure` (live) + `POST /refresh` |
| 12 | Glob-based file discovery matched directories (`*.product`, `*.target`) | `product/eu.kalafatic.dstudio.product` listed as a product file | `findPaths` only returns regular files |

## Preserved (unchanged)
- No hardcoded project-specific adapter — all five apps detected via generic adapters (`eclipse-tycho` for the four RCP apps, `maven` for ENR).
- Project IDs, bundle IDs, artifact IDs, repository paths, remotes — untouched.
- Existing endpoints, UI structure, operation history, state persistence — unchanged; new features are additive.
- Generic UPM remains fully capable of managing unrelated projects (git-only, CMake, Gradle, …).
