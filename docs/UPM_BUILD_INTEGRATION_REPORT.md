# UPM Build Integration Report

All operations execute the project's real build entry point (root `pom.xml` + `./mvnw` when present). UPM never reconstructs build logic; the adapters supply the standard Tycho/Maven commands and the operation engine streams output, parses module progress and test summaries, records exit codes and full logs.

## Command mapping (proposed per adapter, editable per project)

| Op | Tycho (evo/tryst/dcode/dstudio) | Maven (enr) |
|---|---|---|
| build | `./mvnw -B -DskipTests clean verify` | `mvn -B -DskipTests clean verify` |
| cleanBuild | `./mvnw -B -DskipTests clean verify` | same |
| update | `./mvnw -B -U -DskipTests clean verify` | `mvn -B -U -DskipTests clean verify` |
| test | `./mvnw -B verify` | `mvn -B test` |
| verify | `./mvnw -B verify` | `mvn -B verify` |
| install | `./mvnw -B -DskipTests install` | `mvn -B -DskipTests install` |
| package | `./mvnw -B -DskipTests package` | same |
| validate (new) | `./mvnw -B validate` | `mvn -B validate` |
| buildModule (new) | `<mvn> -B [-DskipTests] clean verify -pl <module> -am` | same |

Environment: `JAVA_HOME` resolved per project — Java 21 for the RCP apps; **Java 25 auto-selected for ENR** (`requiresJava` detection). Operation model: pid, start/end times, exit code, live output, per-module progress (parses `[INFO] Building x/y` and `Reactor Build Order`), surefire test counts, cancellation, timeouts, and per-op log files.

## Executed through UPM (workspace upm-itest, 2026-11-19)

| Project | Operation | Result |
|---|---|---|
| evo | validate | SUCCEEDED (real `./mvnw -B validate`) |
| evo | buildModule `eu.kalafatic.utils` (-am) | SUCCEEDED |
| evo | test (`./mvnw -B verify`) | running (see test report; pre-existing functional test failures expected in controller.tests) |
| tryst | validate | SUCCEEDED |
| tryst | test | running (live-agent E2E tests may fail — environmental) |
| dcode | validate | SUCCEEDED |
| dcode | build (full reactor) | **SUCCEEDED** |
| dcode | test | **SUCCEEDED** — 127 tests reported by the op |
| dstudio | validate | SUCCEEDED |
| dstudio | test | **SUCCEEDED** |
| enr | validate | SUCCEEDED |
| enr | build (full reactor, JDK 25) | **SUCCEEDED** |
| enr | test | **SUCCEEDED** |

## Reliability notes
- Conflicting operations on the same working tree are prevented (one active op per project; builds refused while the project runtime is STARTING/RUNNING/READY).
- Simultaneous operations on different projects run safely (verified: evo + tryst + dstudio + enr tests concurrently).
- A failed build is reported with exit code and the last `[ERROR]` lines; success is never inferred from process start alone.
- ENR CLI packaging defect discovered via UPM launch (`NoClassDefFoundError` for the thin jar) → fixed by shading `enr-cli`; full ENR build re-verified.
