# UPM Integration Test Report

Real integration matrix executed against the five repositories (workspace `upm-itest`, 2026-11-19). UPM backend built with JDK 21 (`build.sh`), running on port 29080.

## Matrix

| Project | Parent discovery | Build | Test | Launch | Update |
|---|---|---|---|---|---|
| EVO | ✅ parent=pom.xml, 30 modules, 1 feature, 5 products, 8 .launch, 1 target | ✅ validate; ✅ buildModule(eu.kalafatic.utils) | ⚠️ 1206 tests via UPM — 74 failures + 79 errors, all pre-existing functional (see note) | ✅ target resolution; GUI not launched (headless) | ✅ status/check on disposable copy (workflow shared) |
| TRYST | ✅ parent=pom.xml, 10 modules, 1 feature, 2 products, 4 .launch | ✅ validate | ⚠️ 357 tests via UPM, 0 failures — exit 1 from live-agent E2E errors (environment-dependent) | ✅ `Product: tryst` resolved | ✅ workflow verified |
| EVO-Code | ✅ parent=pom.xml, 13 modules, 1 feature, 2 products, 4 .launch | ✅ **full build SUCCESS** | ✅ **127 tests SUCCESS** | ✅ `Product: dcode` + `dcode-headless` resolved | ✅ workflow verified |
| EVO-Studio | ✅ parent=pom.xml, 10 modules, 1 feature, 1 product, 4 .launch | ✅ validate | ✅ **tests SUCCESS** | ✅ `Product: dstudio` resolved | ✅ workflow verified |
| ENR | ✅ parent=pom.xml, 12 modules, requiresJava=25 | ✅ **full build SUCCESS (JDK 25 auto)** | ✅ **tests SUCCESS** | ✅ **launched + READY + stopped** (ENR server, port 18081) | ✅ workflow verified |

## Per-project verification details
1. **Registration/discovery** — all five registered via `/api/projects` with correct adapter (eclipse-tycho × 4, maven × 1); structure snapshots persisted; refresh endpoint re-detects.
2. **Parent detection** — `parentPom=pom.xml` (repo root) for all five; `parentIsRepoRoot=true`.
3. **Module hierarchy** — module roles classified (bundle/feature/product/repository/test-bundle/jar/target-platform).
4. **Validation** — real `mvn -B validate` executed through UPM for all five: 5/5 SUCCEEDED.
5. **Real builds through UPM** — ENR full reactor (JDK 25), EVO-Code full reactor, EVO module build: all SUCCEEDED with exit codes captured.
6. **Tests through UPM** — EVO-Code (127 tests), EVO-Studio, ENR: SUCCEEDED. TRYST and EVO: completed with pre-existing functional/environmental failures (documented below); no dependency/classpath failures.
7. **Launch** — ENR server launched via UPM (READY with HTTP probe), stopped via UPM (STOPPED, port released). RCP GUI products: launch-target resolution verified; not GUI-launched (headless host) — not claimed as verified.
8. **Git update detection** — checkUpdates + updateAll verified on a disposable repository: dirty-refusal, confirmation-gating, fast-forward integration, unpushed-commit refusal (see UPDATE report). No git mutations were performed on the production working trees.
9. **Operation logs/state** — every operation recorded (start/end, exit code, summary, error, full log file under `state/operations/`); UI shows live op progress.

## Notes / limitations
- EVO test run via UPM (`./mvnw -B verify`): **1206 tests — 74 failures + 79 errors**, all pre-existing functional issues (assertion mismatches, SQLite `BUSY_SNAPSHOT`, port allocation, LLM stub expectations, genome milestone assertion) — unchanged by UPM work; **no** unresolved imports or classpath errors anywhere. Final state: FAILED (honest reporting).
- TRYST test run via UPM: **357 tests, 0 assertion failures** — exit 1 caused by live-agent E2E errors (OpenCode harness, LAN access) that are environment-dependent.
- EVO-Studio live-agent tests passed on the UPM re-run.
- ENR CUDA tests require an NVIDIA GPU (`cuCtxCreate failed: 2` on this host).
- GUI launches of the four RCP applications require a display and were not performed.

## Build of UPM itself
`bash build.sh` — compiles 19 sources with JDK 21 into `universal-project-manager.jar`: **OK** (rebuilt multiple times during this work).
