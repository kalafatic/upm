# UPM Project Discovery Report

Verified against the five managed repositories (2026-11-19, UPM workspace `upm-itest`).

| Project | Repo root | Detected adapter | Confidence | Parent POM | Modules | Features | Products | Target platforms | Eclipse launch configs | requiresJava |
|---|---|---|---|---|---|---|---|---|---|---|
| evo | /home/petr/git/evolution-121126 | eclipse-tycho | DETECTED | pom.xml (repo root) | 30 | 1 | 5 | 1 | 8 | — (Java 21) |
| tryst | /home/petr/git/tryst-121126 | eclipse-tycho | DETECTED | pom.xml (repo root) | 10 | 1 | 2 (incl. legacy file) | 1 (+file) | 4 | — |
| dcode | /home/petr/git/DCode-121126 | eclipse-tycho | DETECTED | pom.xml (repo root) | 13 | 1 | 2 product files | 1 | 4 | — |
| dstudio | /home/petr/git/DStudio-121126 | eclipse-tycho | DETECTED | pom.xml (repo root) | 10 | 1 | 1 | 1 | 4 | — |
| enr | /home/petr/git/evo-native-runtime-161126 | maven | DETECTED | pom.xml (repo root) | 12 | 0 | 0 | 0 | 0 | **25** |
| gittest | disposable copy | generic | DETECTED | — | 0 | 0 | 0 | 0 | 0 | — |

## Hierarchy exposed per project (UI: Structure tab)
```
Application
  ├── Parent / Aggregator    (pom.xml at repo root — all five apps)
  ├── Modules                (reactor modules with roles: bundle / feature / product / repository / test-bundle / jar / target-platform)
  ├── Features               (feature.xml paths)
  ├── Products               (.product files + built launchers)
  ├── Target platforms       (.target files)
  ├── Eclipse launch configs (.launches/*.launch)
  └── Operations / Launch configs
```

## Key findings
1. For all five applications the authoritative parent IS the repository root pom.xml — repository root, administration root and Maven parent coincide; UPM's root-pom assumption holds today, and `parentPom()` additionally supports parent POMs that are not at the root.
2. Module roles are classified generically: EVO shows 25 bundles, 1 feature, 1 repository, 1 target-platform (module), 1 jar (supervisor), 1 jar module (gpu is outside the reactor and not listed); TRYST/DCode/DStudio show target/bundle/feature/product/test modules.
3. Launch-config discovery: built product launchers (tryst, dcode, dcode-headless, dstudio) detected as `eclipse-product` launch configs; EVO's products are not currently materialized (build output cleaned) — launcher status is reported as "not built" until a build runs; the 5 EVO product definitions and 8 `.launch` files are listed.
4. `requiresJava=25` is detected for ENR from `enr-compute-cuda/pom.xml`; JDK-25 environment selection is verified in build + launch operations.
5. Scanner (`/api/discover/scan`, remote inspection via ls-remote) unchanged and working; scan results now include parentPom/features/products/targetPlatforms/launch configs per candidate.
6. Refresh (`POST /api/projects/<id>/refresh`) re-detects and persists the structure snapshot; persisted snapshot is exposed via `GET /api/projects/<id>/structure` alongside a live re-detection.
