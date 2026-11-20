# UPM Launch Integration Report

UPM resolves each project's authoritative launch target from the repository metadata; it never invents launch commands.

## Launch target resolution (verified)

| Project | Adapter | Discovered launch targets |
|---|---|---|
| evo | eclipse-tycho | 5 product definitions (evolution, evo, headless…); 8 Eclipse `.launch` configs; built product launchers detected generically once products are materialized (currently not built → reported "not built") |
| tryst | eclipse-tycho | `Product: tryst` (built launcher, executable) |
| dcode | eclipse-tycho | `Product: dcode-headless`, `Product: dcode` (both built) |
| dstudio | eclipse-tycho | `Product: dstudio` (built) |
| enr | maven | `Module JAR: enr-cli/target/enr-cli-2.6.11-SNAPSHOT.jar` (auto-proposed); user-defined `enr-server` jar launch with HTTP readiness |

## Launch execution (actually performed)

| Project | Launch config | Kind | Outcome |
|---|---|---|---|
| enr | `enr-server` (`java -jar enr-cli….jar start -p 18081`, JDK 25) | jar | **Launched and verified**: runtime state reached **READY** (HTTP probe on 18081), API responded `{"models":[]}` on `/api/v1/models`; UPM **stop** → STOPPED, port released. |
| enr | (auto) thin JAR launch | jar | Exposed a packaging defect (`NoClassDefFoundError: ai/enr/core/json/JsonValue`) → fixed by shading enr-cli; re-verified. |
| evo/tryst/dcode/dstudio | RCP products (GUI) | eclipse-product | **Not launched** — headless host (no DISPLAY). Launch-target resolution verified; honest status: launches not GUI-verified. |

## Platform corrections (see compatibility audit)
1. `Proc.start` now executes `jar` launch configs as `setsid <java> -jar <jar>` with the java binary resolved from the launch env (`JAVA_HOME={java25}` templates expanded).
2. Product launcher discovery generalized (no hardcoded app names, correct depth).
3. Display-required launches report a clear error when `DISPLAY` is missing instead of failing obscurely.

## Cross-application isolation
Launch state is per project + launch config: pid files and logs under `state/runtime/<project>/<launchConfig>/`; starting ENR did not affect the other four projects (all remained STOPPED). Launching one application cannot start another's runtime.
