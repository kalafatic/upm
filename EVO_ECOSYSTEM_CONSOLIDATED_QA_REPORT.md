# EVO Ecosystem — Consolidated QA & Integration Assessment

**Report type:** Consolidated QA / integration assessment (read-only)
**Generated:** 2026-11-24
**Authoritative audit data root:** `/home/petr/.evo/audit/`
**Repositories assessed:** 7 (evolution, enr, tryst, evo-studio, evo-code, upm, evo)
**Source evidence:** 6 completed audit runs + 90+ repository QA/test/audit reports

> This document consolidates existing evidence only. No new test campaign was executed, no source/config/DB/application state was modified, and no repair was performed. Every finding is traceable to a source report or audit run. Where a report states no date, branch, commit, or result, it is marked **NOT STATED**; nothing is inferred as fact without evidence.

---

## A. Executive Summary

**Overall ecosystem status: PARTIAL / DEGRADED — not production-ready.**

The ecosystem is **partially functional**. The integration backbone works end-to-end in its verified paths, but several core goals remain unmet and a non-trivial set of defects and un-triaged test debt remains.

| Question | Answer |
|---|---|
| **1. Is the ecosystem operational?** | Partially. The family integration backbone (TEIP 1.1, DCode harness, ENR inference/gateway) is implemented and live-verified in specific paths. The six authoritative audit runs all returned `overallResult: DEGRADED`. |
| **2. Which apps are healthy / partially functional / broken?** | Healthy (PASS): **evo-studio**, **upm**. Partially functional: **evolution** (EVO Platform), **enr**, **tryst**, **evo-code** (DCode). Broken: **none**. Not tested: **evo** (placeholder repo, README only). |
| **3. Which integrations work / are broken?** | Working (live-verified): OpenCode→ENR→DeepSeek agentic loop; TEIP 1.1 (EVO/TRYST/DStudio); DCode as default family harness; ENR as default family inference; ENR↔HuggingFace GGUF lifecycle; ENR↔real Ollama (reference). Broken/degraded: Darwin→DCode and TRYST→DCode delegation (wrong default endpoint 48080); TRYST↔DCode port divergence. Not tested: Claude Code, Anthropic, DeepSeek/OpenAI live credentials (no keys). |
| **4. What are the systemic problems?** | (1) Port-allocation convention not enforced centrally (48080 vs 58080 divergence, TRYST doc 9090 vs code 38280, historical ENR 28780 default); (2) native model training/data immaturity (undertrained, declarative-only corpus, no GPU); (3) EVO Platform's own test suite has a large stable un-triaged failure set; (4) security-posture inconsistency (dev-mode default-on, missing authz on one handler, single-token auth); (5) verification asymmetry (only 2 of 7 apps machine-audited). See Section N. |
| **5. What are the blockers?** | No P0 blockers were found. The most blocking are the native-model quality limitations (no Q&A/agentic ability from native models) and the DCode endpoint divergence that blocks two family delegation flows. |
| **6. Which defects to repair first?** | (1) DCode default-endpoint divergence (trivial fix, unblocks two family flows); (2) DCode headless launcher `Unable to acquire application service`; (3) TRYST `EvoIntegrationHandler` missing authorization; (4) triage the EVO Platform persistent test failures. See Sections F and O. |
| **7. What is the realistic repair scope?** | Targeted repairs only — no rewrites/migrations. The known defects are individually small (constant fixes, one missing authz check, test triage) except the native-model training quality, which is a data/pipeline limitation requiring retraining/SFT and quantized-tensor support, not a code patch. See Section P. |

**Defect counts (OPEN, normalized to a stated methodology — see Section B.2):**

| Severity | Count | Notes |
|---|---|---|
| P0 (blocker) | 0 | None found. |
| P1 (critical) | 2 | Native model cannot answer questions / undertrained; DCode headless launcher fails to acquire app service. |
| P2 (major) | 4 | DCode endpoint divergence (3 code sites); EVO Platform persistent test failures; TRYST `EvoIntegrationHandler` no authz; GPU/CUDA not implemented. |
| P3 (minor) | ~35 | Long tail of minor defects/limitations across enr, tryst, evo-studio, evo-code, evolution. |

**Resolved defects (verified or reported-fixed):** ~90+, including all TRYST Phase-2 security P1s (header spoofing CVSS 9.8, hardcoded admin bypass), the Forge RoPE/tokenizer corruption (F-1/F-9), 5 ENR protocol/catalog defects, and evo-studio's 28-defect Phase-4/7/8 closure. (One failure — `EVO-FORGE-TOKENIZER-METASPACE-PREFIX` — is the only failure the audit storage records as *verified* RESOLVED by a later run; 5 ENR defects are *fixed* but lack a later-run re-verification. See Section F/R.)

---

## B. Methodology & Evidence Classification

### B.1 Evidence classes

- **CONFIRMED** — verified by the authoritative machine audit (`/home/petr/.evo/audit/`) or by an executed test with recorded output.
- **REPORTED** — stated in a repository QA report but not independently re-confirmed by this consolidation or the authoritative audit.
- **INFERRED** — derived from multiple consistent REPORTED sources (no single direct statement).
- **HYPOTHESIS** — proposed explanation without supporting execution evidence.
- **UNKNOWN** — no evidence available.

### B.2 Severity methodology

- **P0 (blocker):** prevents the ecosystem from operating / total outage / data loss.
- **P1 (critical):** critical correctness or security defect affecting a core goal (e.g., unauthenticated admin, wrong inference math, broken agent loop).
- **P2 (major):** significant functional gap or defect in a component (e.g., wrong default endpoint, missing authorization on one handler, persistent failing test set).
- **P3 (minor):** minor defect, limitation, cosmetic, informational.

Source reports rarely state P0–P3; sub-agent mapping and this report's normalization apply: blocker→P0, critical/CVSS-high→P1, major/high→P2, minor/medium/low→P3. Where a report states a severity it is preserved. Counts are **not** a numerical score — they are a count under the stated methodology only.

### B.3 Status vocabulary

Capability statuses: PASS / PARTIAL / FAIL / NOT TESTED. Overall app status: HEALTHY / PARTIAL / BROKEN / NOT TESTED.

### B.4 Baseline & history

- **Previous audit history loaded:** YES — 6 runs in `runs-index.json`, `regression-history.json`, `module-history.json`, `failures-index.json`.
- **Approved baseline loaded:** NO / NOT_AVAILABLE — `/home/petr/.evo/audit/baseline/` is empty (no `baseline.json`).
- **Repository baselines:** all 6 active repos on `master` @ `sync-241126-3` (2026-11-24); `evo-221126` @ `Initial commit` (2026-11-22). Commits: evolution `0331e59`, enr `0219ef0`, tryst `526d4d6`, evo-studio `6015414`, evo-code `bc90d15`, upm `cd95eb4`.
- **No conflicting baselines to reconcile by date:** the two report sets that superficially disagree ("EVO BUILD SUCCESS" vs "1206 tests, 74F+79E") are different scopes (compile vs full test run), not a contradiction (Section D.1).

---

## C. Report Inventory & Baseline (Phase 1)

### C.1 Authoritative audit runs (`/home/petr/.evo/audit/`, immutable)

| Run ID | Date | Type | App | Modules | Pass | Fail | Result |
|---|---|---|---|---|---|---|---|
| `evo-native-v2-integrity-20261123` | 2026-11-23 | REGRESSION | evolution | 5 | 3 | 2 | DEGRADED |
| `enr-func-test-20261123-iter1` | 2026-11-23 | QUICK | enr | 13 | 10 | 0 (4 failures) | DEGRADED |
| `enr-harness-gateway-20261123-iter2` | 2026-11-23 | QUICK | enr | 8 | 7 | 0 (1 failure) | DEGRADED |
| `evo-native-v3-training-quality-20261123` | 2026-11-23 | REGRESSION | evolution | 6 | 4 | 1 | DEGRADED |
| `enr-hf-lifecycle-20261123-iter3` | 2026-11-23 | QUICK | enr | 6 | 4 | 0 (2 failures) | DEGRADED |
| `enr-dual-mode-20261124-iter4` | 2026-11-24 | QUICK | enr | 6 | 5 | 0 (0 failures, 1 NOT_TESTED) | DEGRADED |

**Key fact:** the authoritative audit storage covers only **evolution** and **enr**. The other five applications have **no** machine-verified audit runs; their status is REPORTED-only.

### C.2 Repository report corpus (inventory)

| Repository | Report files read | Coverage |
|---|---|---|
| evolution-221126 | ~45 (PHASE-6/7, Darwin-Forge, architecture audits, ECOS, genome-docs, testing) | EVO Platform, Darwin engine, Forge training pipeline |
| enr-221126 | 7 (architecture, test, performance, model-format, multi-model, simplification, initial-test) | ENR runtime + gateway |
| tryst-221126 | 22 (production-readiness, E2E, user-admin, full-system, security, stabilization, async-coder, unified) | TRYST platform |
| evo-studio-221126 | 17 (PHASE-4, PHASE-6/7/8, UX-standard) | EVO-Studio orchestration |
| evo-code-221126 | 17 (PHASE-5, PHASE-6, testing, UIUX, UX-standard) | EVO-Code/DCode engine |
| upm-221126 | 6 (build/compat/integration/launch/discovery/update) | UPM project manager |
| evo-221126 | README.md only | Test platform placeholder — no QA reports |

**Date/commit coverage:** most reports state a date but **no git commit SHA** (only two 2026-11-01 TRYST audits state `539768259664f6695bf44f7efce06615bca6fbeb`, and one evo-code report states `branch master`). This limits exact-baseline reproducibility for REPORTED findings — flagged throughout.

---

## D. Per-Application Health (Phase 2)

| App | Repo | Status | Evidence class | Basis |
|---|---|---|---|---|
| EVO Platform | evolution-221126 | PARTIAL | CONFIRMED (audit) + REPORTED | Builds; test suite has persistent failures; native training limited. |
| ENR | enr-221126 | PARTIAL | CONFIRMED (audit) | Gateway role P0-achieved; native inference limited to F32/F16; quantized unsupported. |
| TRYST | tryst-221126 | PARTIAL | REPORTED | 382 tests (381 pass); strong security; open P2 authz/port. |
| EVO-Studio | evo-studio-221126 | PASS | REPORTED (consistent, live-verified) | Reactor 57→224 tests all green; only minor open P3s; desktop GUI PARTIAL. |
| EVO-Code (DCode) | evo-code-221126 | PARTIAL | REPORTED (live-verified) | "PARTIALLY READY"; E2E coding works; open P1/P2 port/launcher defects. |
| UPM | upm-221126 | PASS | REPORTED (live-verified) | Functional; verified against all 5 managed repos. |
| EVO (test platform) | evo-221126 | NOT TESTED | — | `Initial commit`, README only; no code/QA content. |

### D.1 Per-app detail (with the EVO "build vs test" reconciliation)

- **evolution (EVO Platform):**
  - Compile/build: PASS ("EVO BUILD SUCCESS — 20-module chain", PHASE-4-END-TO-END; "zero source changes to EVO" in Phase 7).
  - Test suite: persistent failures — Phase 6 "333 tests in 72 classes — 27 failures + 18 errors in 20 pre-existing classes"; Phase 7 run2 "669 tests — 55F+52E, same set and signatures as Phase 6 baseline"; UPM integration "1206 tests — 74 failures + 79 errors, all pre-existing (assertion mismatches, SQLite BUSY_SNAPSHOT, port allocation, LLM stub expectations, genome milestone assertion)". **CONFIRMED** (three independent runs). These are different scopes (compile vs test), not a contradiction.
  - Native training/Forge: "ACCEPTED WITH LIMITATIONS", "Not accepted as PRODUCTION-READY" (DARWIN-FORGE-FINAL-STABILIZATION-REPORT). Real pure-Java float32 CPU training is authentic (forensic weight-change verification, CONFIRMED), but no GPU execution ("NOT IMPLEMENTED").
  - Release-readiness self-report (Nov 6): "100% pass, 14/14 scenarios" — a single-session claim later superseded by the Nov 17 "accepted with limitations" and Nov 22/23 validation work; reported as a **trajectory**, not a contradiction.
  - Nov 23 Darwin smoke test: "0 operational instances" (environmental — unresponsive provider, port 28080 occupied). CONFIRMED live attempt; NOT a code defect.
- **enr:** see Section D of the ENR extraction; gateway role "P0 ACHIEVED — READY FOR HARNESS INTEGRATION" (CONFIRMED, 7 chat requests logged); native numerical correctness vs llama.cpp oracle within 2e-3 (CONFIRMED); quantized GGUF, `/api/create`, Anthropic/Gemini tool translation not supported (CONFIRMED limitations).
- **tryst:** "implemented and verified" (REPORTED). Trajectory: 2026-11-01 audit found desktop-only app (no server/web) → later reports claim headless server + web + multi-user RBAC closed. Test suite grew 55→382. Security: PBKDF2 password hashing, RBAC deny-by-default, CSRF/path-traversal/symlink/rate-limit 9/9 pass.
- **evo-studio:** predominantly PASS; desktop RCP PARTIAL ("demonstrably runs… two genuine defects found and fixed; complete in-UI workflow driving not fully completed"); Web IDE and multi-harness chaining DEFERRED.
- **evo-code:** "PARTIALLY READY" (2026-11-24). Real coding task via DeepSeek (through ENR) works end-to-end (CONFIRMED). Darwin→DCode PARTIAL, TRYST→DCode BLOCKED (environment + port).
- **upm:** functional; "real integration matrix executed against the five repositories" (CONFIRMED); builds/tests/launch/update verified; honest reporting of pre-existing EVO/TRYST failures.

---

## E. Capability Matrix (Phase 3)

| Capability | Status | Evidence | Class |
|---|---|---|---|
| Family coding harness (DCode as default) | PASS (implemented) | EVO/TRYST/DStudio consume DCode | REPORTED |
| Family inference (ENR as default) | PARTIAL | TRYST/DStudio/DCode default; EVO selectable only | REPORTED |
| TEIP 1.1 protocol | PASS | EVO/TRYST/DStudio live E2E | CONFIRMED |
| OpenCode/Aider harness adapters | PASS | unit + live real-CLI | CONFIRMED |
| Native LLM training (Forge) | PARTIAL | end-to-end VERIFIED but "accepted with limitations", no GPU | CONFIRMED |
| Native LLM inference (ENR) | PARTIAL | F32/F16 verified; quantized GGUF unsupported | CONFIRMED |
| Provider gateway (ENR→DeepSeek/Ollama/OpenAI) | PASS | live tool-calling + streaming | CONFIRMED |
| Ollama/OpenAI protocol surface (ENR) | PARTIAL | `/api/create` missing; `/v1/embeddings` partial | CONFIRMED |
| HuggingFace GGUF lifecycle (ENR) | PARTIAL | download/verify/register/persist PASS; quantized load unsupported | CONFIRMED |
| Multi-user auth & RBAC (TRYST) | PASS | PBKDF2 + deny-by-default + tests | REPORTED |
| Headless server + web frontend (TRYST) | PASS | startup + E2E | REPORTED |
| Web IDE (EVO-Studio S6) | PARTIAL | API layer PASS; browser UI not verified | REPORTED |
| RCP desktop (EVO-Studio / EVO-Code) | PARTIAL | runs; full in-UI workflow not completed | CONFIRMED |
| UPM project management | PASS | discovery/build/test/launch/update verified | CONFIRMED |
| Darwin evolution engine | PASS | scenarios A–E; stagnation detection PARTIAL | REPORTED |
| GPU/CUDA execution | NOT IMPLEMENTED | driver verification only; CUDA tests env-BLOCKED (VRAM OOM) | CONFIRMED |
| Cross-client session interop (DCode RCP↔Web) | PASS | scenarios A/C/E/F/G | CONFIRMED |
| ENR dual-mode (menu/direct/systemd service) | PASS | user-level systemd verified; root + Windows NOT TESTED | CONFIRMED |

---

## F. Defect Register P0–P3 (Phase 4)

### F.1 Authoritative audit failures (machine-verified)

| Failure ID | App | Severity (audit) | Current status | Description | Evidence class |
|---|---|---|---|---|---|
| `EVO-FORGE-TOKENIZER-METASPACE-PREFIX` | evolution | LOW | **RESOLVED (verified by later run)** | `decode(encode(t))` prefixed with metaspace | CONFIRMED |
| `EVO-NATIVE-UNDERTRAINED-MODEL-GIBBERISH` | evolution | MEDIUM | **OPEN** | trained artifact emits incoherent text (effective vocab ~375 real tokens) | CONFIRMED |
| `EVO-NATIVE-V3-QA-FORMAT-MISMATCH` | evolution | HIGH | **OPEN** | V3 recalls facts declaratively but cannot answer questions | CONFIRMED |
| `enr-f1-malformed-json-404` | enr | MEDIUM | FIXED (no later-run re-verify) | malformed JSON → 404 not 400 | CONFIRMED |
| `enr-f2-unload-silent-error` | enr | MEDIUM | FIXED (no later-run re-verify) | unload/cancel swallowed HTTP errors | CONFIRMED |
| `enr-f3-stale-model-path` | enr | LOW | FIXED (no later-run re-verify) | `model_directories` pointed at old repo path | CONFIRMED |
| `enr-f4-concurrency-500` | enr | LOW | **OPEN** | >8 concurrent sessions → 500 not 429 | CONFIRMED |
| `enr-f5-provider-error-500` | enr | MEDIUM | FIXED (no later-run re-verify) | provider errors flattened to 500 | CONFIRMED |
| `enr-f6-hf-repo-parse` | enr | HIGH | FIXED (no later-run re-verify) | HF repo parsed as first path segment only | CONFIRMED |
| `enr-f7-quantized-unsupported` | enr | MEDIUM | **OPEN (documented limitation)** | quantized Q4_K_M (GGML type 8) not loadable natively | CONFIRMED |

> Note: the audit store distinguishes "fixed in a run" from "verified resolved by a later run". Only `EVO-FORGE-TOKENIZER-METASPACE-PREFIX` has the latter. Five ENR defects (`enr-f1/f2/f3/f5/f6`) are **fixed but not yet regression re-verified** — this is itself a regression-strategy gap (Section R).

### F.2 Consolidated OPEN defects (repository reports, normalized)

| ID | App(s) | Sev | Status | Description | Class | Source |
|---|---|---|---|---|---|---|
| S1 | evolution | P1 | OPEN | Native V3 model cannot answer questions (declarative-only corpus) + undertrained model gibberish | CONFIRMED | audit runs + TrainForgeV3 |
| S2 | evolution | P1 | OPEN | DCode headless launcher "Unable to acquire application service" | REPORTED | PHASE-7-DEFECT-REGISTER D-9 |
| S3 | evo-code ×2, tryst ×1 | P2 | OPEN | DCode default endpoint divergence 48080 vs 58080 (`DCodeCodingHarnessAdapter` evo-code, `DCodeHttpClient` fallback, TRYST `DCodeCodingHarnessAdapter`) — blocks Darwin→DCode (PARTIAL) and TRYST→DCode (BLOCKED) | CONFIRMED | EVO_CODE_TEST_REPORT, UNIFIED-CONTROL-PLATFORM-PHASE1-AUDIT §9 |
| S4 | evolution | P2 | OPEN | EVO Platform test suite persistent failures (27F+18E → 55F+52E → 74F+79E), untriaged | CONFIRMED | PHASE-6/7 TEST-REPORT, UPM_INTEGRATION_TEST_REPORT |
| S5 | tryst | P2 | OPEN | `EvoIntegrationHandler` performs no authorization | CONFIRMED | PHASE-3-WP-3.10-SECURITY-REPORT §8 |
| S6 | evolution | P2 | OPEN | GPU/CUDA execution NOT IMPLEMENTED (driver check only) | CONFIRMED | DARWIN-FORGE-FINAL-STABILIZATION-REPORT WP6 |
| S7 | enr | P3 | OPEN | concurrent-session-limit → 500 not 429 | CONFIRMED | audit run iter1 |
| S8 | enr | P3 | OPEN | quantized GGUF cannot load natively; `/api/create` unimplemented; Anthropic/Gemini tool translation unimplemented; F16/BF16/INT8/INT4 dtypes defined but never produced; GGUF exporter gate/norm placeholder (verify) | CONFIRMED | ENR-EVO-MODEL-FORMAT-AUDIT, ENR_INITIAL_TEST_REPORT |
| S9 | tryst | P3 | OPEN | dev-mode default-on grants unauthenticated admin; `restartHttpService()` loses handlers; orphaned `OpenCodeCliAdapter`; Phase1/2 docs & "FHC" absent; 3 pre-existing broken test classes; CWD-relative TEIP DB path; credential non-persistence asymmetry | REPORTED | ASYNC_CODER_2_VALIDATION, PHASE-3-*, UNIFIED-PHASE1 |
| S10 | evo-studio | P3 | OPEN | full-screen capture black; aider git-index side effect; non-git dirs no diff; `result.artifacts` always `[]`; Ollama runtimeStatus JVM stats; Console Clear/Copy/Save not implemented; terminal probing duplicated; session rename/dup/delete not owned | REPORTED | PHASE-4-*, PHASE-7, UX-STANDARD |
| S11 | evo-code | P3 | OPEN | `llm.evo` does not exist (configured LLM is DeepSeek); `/changes` empty for fs-only workspace; no correlation-ID propagation; token usage not surfaced; SSE no Last-Event-ID replay; browser-DOM not automated; JDT unavailable headless | REPORTED | EVO_CODE_TEST_REPORT, PHASE-5-* |
| S12 | enr/evolution | P3 | OPEN | config drift from repo relocations (stale `model_directories`, referenced paths that no longer exist) | CONFIRMED | enr-f3, ENR-SIMPLIFICATION-AUDIT |

### F.3 Resolved defects (highlights — full detail in source registers)

- **tryst** (~30): header-spoofing identity (CVSS 9.8), hardcoded `"admin"` bypass (CVSS 8.5), desktop-only→server, no embedded HTTP server, no durable task queue, no User/Auth models, in-memory audit trail, connected-applications no-authz — all RESOLVED. OpenCode/Aider code-agent fabrication, `repository_mirrors` columns, pause-as-cancel, static `/api/status` — RESOLVED (Async Coder 2).
- **evolution Forge** (~100-entry register, mostly FIXED): RoPE-vs-sinusoidal corruption (F-1/F-9), tokenizer fidelity, `sectionCount`/hash truncation (B-16), deterministic training (B-7), fabricated REST training data (F-1..F-6, "DUMMY OLLAMA MODEL CONTENT"), checkpoint bit-exactness, multi-head attention (24_Forge audit).
- **enr** (5): malformed-JSON 404, silent unload errors, stale path, provider-error 500, HF repo parse.
- **evo-studio** (~28): UI bundle missing activator, disposed-shell NPE, TRYST OSGi service leak, missing schema tables, port 48081 convention break, DCode endpoint correction to 58080, ENR `/health` probe, fallback 4-token toy model, `Map.of` NPE.
- **evo-code** (~12): RCP SCR not auto-started, JDT-before-workspace, DSML tool-call parsing, read-only-command config mutation, binary-file search crash.
- **upm** (8): empty launch configs, 5-level launcher depth, `evolution` launcher whitelist, non-executable JAR launch, duplicate `original-*.jar`, JDK-21-vs-25 mismatch, glob directory-as-file, ENR thin-JAR `NoClassDefFoundError`.

---

## G. Dependency Matrix (Phase 5)

| From ↓ / To → | evolution | enr | tryst | evo-studio | evo-code | upm | DeepSeek | Ollama | HF | OpenCode/Aider |
|---|---|---|---|---|---|---|---|---|---|---|
| evolution | — | selectable inference (`EnrLlmProvider`) | TEIP 1.1 | TEIP 1.1 (dev mode) | DCode default harness | managed | — | alt provider | — | fallback harness |
| enr | — | — | — | — | — | managed (maven, JDK25) | provider gateway | Ollama-compat surface | GGUF lifecycle | — |
| tryst | TEIP 1.1 + MCP tool plane | ENR default inference | — | DStudio adapter (TEIP) | DCode default harness | managed | via ENR | alt provider | — | OpenCode harness |
| evo-studio | TEIP 1.1 | ENR live adapter | TEIP 1.1 (client) | — | FHC 1.0 (58080) | — | (configured, not probed) | Ollama live | — | OpenCode/Aider live |
| evo-code | — | ENR wire-only | — | — | — | managed | via ENR `/v1` | Ollama `/api` | — | — |
| upm | manages build/test/launch | manages (maven) | manages (tycho) | manages (tycho) | manages (tycho) | — | — | — | — | — |

**Boundary rules (REPORTED):** wire-only integration, no circular dependencies, no cross-app class dependencies ("DCode never commits/pushes in delegated mode"; "TRYST must not depend on DStudio bundles"). Two harness SPI families cause "behavioral drift" risk (UNIFIED-PHASE1 §8).

---

## H. Integration Contracts (Phase 6)

| Contract | From → To | Status | Evidence | Class |
|---|---|---|---|---|
| TEIP 1.1 (`/teip/v1`) | EVO / TRYST / DStudio | PASS (live E2E) | PHASE-3/4 | CONFIRMED |
| FHC 1.0 (DCode `/api/v1`) | EVO / TRYST / DStudio → DCode | IMPLEMENTED (DCode caretaker) | PHASE-6 | REPORTED |
| ENR 1.0 (`/enr/v1`) | family → ENR | PASS | PHASE-6/7/8 | CONFIRMED |
| OpenCode harness adapter | EVO/TRYST/DStudio | PASS (live real CLI) | PHASE-3/4 | CONFIRMED |
| Aider harness adapter | EVO-Studio | PASS (live 0.86.2) | PHASE-4-AIDER | CONFIRMED |
| Ollama `/api` | ENR/DCode/Evo-Studio | PARTIAL (parity vs real Ollama verified; `/api/create` missing) | ENR_INITIAL_TEST_REPORT | CONFIRMED |
| OpenAI `/v1` | ENR/DCode | PARTIAL (`/v1/embeddings` local 501) | ENR_INITIAL_TEST_REPORT | CONFIRMED |
| HuggingFace GGUF | ENR | PASS (download/verify/register/persist) | audit iter3 | CONFIRMED |
| systemd --user | ENR | PASS | audit iter4 | CONFIRMED |
| systemd (root) | ENR | NOT TESTED (requires root) | audit iter4 | CONFIRMED |
| CUDA GPU | ENR | BLOCKED (VRAM OOM, env) | audit iter3/4 | CONFIRMED |
| Claude Code / Anthropic / DeepSeek-OpenAI (live keys) | EVO-Studio | NOT TESTED (no key / CLI absent) | PHASE-4/7 | REPORTED |

---

## I. Ports & Configuration (Phase 7)

**Authoritative family convention** (`port-allocation-architecture.md`, `~/.evo/ports.properties`): EVO 28xxx → TRYST 38xxx → EVO-Studio 48xxx → EVO-Code 58xxx (+20000 base, +100 increments).

| Service | Port(s) | Source / note |
|---|---|---|
| EVO HTTP_PRIMARY | 28080 | ports.properties; legacy 48080/8080 migrated |
| EVO HTTP_SECONDARY | 28180 | ports.properties |
| EVO MCP | 28280 | ports.properties |
| EVO DEVELOP | 28380 | ports.properties |
| EVO SUPERVISOR_API / CONTROL | 28480 / 28580 | ports.properties |
| EVO AUXILIARY | 28680 (disabled) | ports.properties |
| EVO ENR | 28780 (**enabled=false** in ports.properties) | historical ENR default |
| TRYST | 38080 (HTTP), 38180 (API), 38280 (TCP), 38380 (mgmt), 38480 (WS) | tryst final-stabilization §8 |
| EVO-Studio | 48080 (TEIP/API) + 48180 (diagnostics) | PHASE-7 §6 |
| EVO-Code/DCode | 58080 (HTTP), 58380 (mgmt), 58680 (aux) | FRONTEND-ARCHITECTURE |
| UPM backend | 29080 | UPM_INTEGRATION_TEST_REPORT |
| ENR standalone (test/dev) | 8111 | ENR_INITIAL_TEST_REPORT |
| Real Ollama reference | 11434 | ENR_INITIAL_TEST_REPORT |

**Discrepancies / drift (documented, not asserted as runtime defects):**
- DCode/DStudio collision at 48080 (evolution D-8) and the 48080-vs-58080 divergence (Section F S3) — the single most recurring port problem.
- TRYST documentation states TCP 9090 while code uses 38280 (evolution D-11).
- ENR appears at `28780` (family), `8111` (standalone test), `18081` (UPM-launched) across reports — environment-dependent, not a defect.
- ENR `ports.properties` shows `enabled=false`, while reports assume it is the default family inference engine at 28780 (TRYST/DStudio `enr-default`).

---

## J. Database & Persistence (Phase 8)

| App | Mechanism | Location | Notes |
|---|---|---|---|
| evolution | EMF config + JSON in EVO data dir | `data/evolution.db` (TEIP), `connected-applications.json` | CWD-relative TEIP DB path (tryst D30, P3) |
| tryst | SQLite (WAL, FK, forward-only migrations V1–V22, 40 tables) | `~/.tryst/tryst.db` | PBKDF2 users, RBAC, task_events/checkpoints |
| evo-studio | SQLite (V1–V3) + properties | `~/.dstudio/dstudio.db`, `dstudio.properties` | AES-GCM secrets at rest; TRYST-side config V18/V19 |
| evo-code | SQLite (WAL, migrations) + JSON | `~/.dcode/dcode.db`, `dcode-config.json`, `secrets.enc` | restart recovery marks RUNNING→FAILED |
| enr | registry persisted (iter3+); otherwise stateless | `~/.enr` (models, logs, provider registry) | "stateless per request" for inference; model registry survives restart (CONFIRMED) |
| upm | `State.java` op registry + structure snapshot | `state/history.json`, `state/runtime/<project>/` | structure block persisted + exposed via API |

---

## K. Authentication & Security (Phase 9)

**Verified-strong (REPORTED/CONFIRMED):**
- TRYST: PBKDF2WithHmacSHA256 (210k iters), 256-bit bearer tokens, deny-by-default RBAC, brute-force lockout, CSRF/path-traversal/symlink-escape/rate-limit tests 9/9 pass, `Set-Cookie` HttpOnly.
- EVO-Studio: AES-GCM at rest (per-value IV, 128-bit tag, `secrets.key` 0600), constant-time token compare, path containment (traversal→400), XSS-safe markdown, TEIP 1.1 auth mandatory (live-verified `AUTHENTICATION_FAILED`).
- ENR: HF token as Bearer header "never logged or returned"; env-sanitization strips `OPENAI_API_KEY`/`ANTHROPIC_API_KEY`/`GITHUB_TOKEN`.
- DCode: AES-GCM `secrets.enc`, env sanitization, shell `--dir` containment hard-error.

**Open security-posture items (classified as dev-mode/posture, not confirmed production defects):**
- TRYST `EvoIntegrationHandler` performs no authorization (P2, CONFIRMED).
- TRYST dev mode default-on grants unauthenticated admin (P3) — dev-mode config, environment not stated as production.
- DCode single server-token auth, no user accounts/RBAC (limitation, disclosed).
- EVO static-key token encryption "weak" (noted; DCode improved on it).
- DCode/DStudio tokens intentionally not persisted in TRYST (runtime-only; restart loses auth state).

---

## L. Inference & Model Management (Phase 10)

- **Native training (Forge, evolution):** authentic pure-Java float32 CPU training (CONFIRMED). **Limitations:** no GPU; effective vocabulary ~375 real tokens (undertrained → gibberish); declarative-only corpus (no Q&A); RoPE/tokenizer now llama.cpp-identical (CONFIRMED 2026-11-22).
- **Native inference (ENR):** F32/F16 only; numerically matches llama.cpp oracle within 2e-3 (CONFIRMED); quantized GGUF (Q4_K_M) unsupported; small toy models only.
- **Provider gateway (ENR):** DeepSeek tool-calling + streaming live-verified; Ollama/OpenAI protocol parity vs real Ollama 0.40.16 verified.
- **Model lifecycle (ENR):** HF `:QUANT` discovery, resumable downloads, SHA-256 verify, persistent registration, CLI copy/run/pull — CONFIRMED (real 100.6MB SmolLM2 download).
- **Model-format integrity:** `.evo` v2 header hash truncated, `sectionCount` mismatch, PE `rope`-vs-sinusoidal, GGUF exporter gate/norm placeholder, F16/BF16/INT8/INT4 dtypes never produced — mostly FIXED (RoPE/sectionCount/hash), with gate/norm and dtype-production listed as remaining verify items.
- **CUDA:** environmental BLOCKED (real Ollama occupies 14.4GB/16GB VRAM; `cuCtxCreate` OOM). NOT a code defect; GPU training NOT IMPLEMENTED.

---

## M. Duplicate Implementations (Phase 11)

Evidence-based only; no consolidation is recommended without proof of actual duplication.

- **Adapter pattern (by design, not duplication):** `DCodeCodingHarnessAdapter` / `DCodeHttpClient` / `DCodeHarnessAdapter` exist in evolution, tryst, and evo-code as independent wire-only adapters (no shared class dependency). This is the family adapter pattern. **However**, the duplicated hardcoded default endpoint (48080) across these adapters is a maintenance hazard and the root of defect S3 — a shared endpoint constant/spec is warranted, not a code merge.
- **Two harness SPI families** cause "behavioral drift" risk (UNIFIED-PHASE1 §8) — an actual duplication of SPI surface.
- **Dead/orphaned code (CONFIRMED):** TRYST `OpenCodeCliAdapter` ("Orphaned — no caller remains"); ECOS "duplicate IterationManager", "duplicated TargetScanner/MetadataAgent", "orphaned ExampleMain/SelfDevMain"; evo-studio "terminal-emulator probing duplicated between `TerminalLauncher` and `DStudioCli`".
- **No evidence of actual duplication** of services/managers/registries elsewhere; do not consolidate similarly-named components without proof.

---

## N. Systemic Root Causes (Phase 12)

1. **Port convention not centralized/enforced** — 48080-vs-58080 divergence (3 repos), TRYST doc 9090 vs code 38280, historical ENR 28780 default, DCode/DStudio 48080 collision. Root cause: hardcoded per-repo defaults with no single source of truth validated at build/test time. (CONFIRMED)
2. **Native model training/data immaturity** — undertrained model, declarative-only corpus, no GPU, quantized unsupported. Root cause: the training pipeline is a real but scope-limited implementation (float32 CPU, small effective vocab, declarative dataset). (CONFIRMED)
3. **EVO Platform test debt** — large stable untriaged failure set (27F+18E stable across phases). Root cause: pre-existing functional issues + non-hermetic tests (port allocation, SQLite BUSY_SNAPSHOT, LLM stub expectations, genome milestone assertion). (CONFIRMED)
4. **Security-posture inconsistency** — dev-mode default-on, missing authz on one handler, single-token auth, static-key encryption. Root cause: security hardened in phases but not applied uniformly across all handlers/modes. (CONFIRMED for the authz gap; REPORTED for posture)
5. **Verification asymmetry** — only evolution and enr have machine-verified audit runs; 5 apps REPORTED-only. Root cause: audit campaign started with ENR/evolution and has not yet extended to the other apps. (CONFIRMED)
6. **Config drift from repository relocations** — stale `model_directories` (enr-f3), references to non-existent paths (`evo-native-runtime-121126`/`161126`). Root cause: repo renames not propagated to config/docs. (CONFIRMED)

---

## O. Repair Backlog (Phase 13)

Ordered by impact/effort (all minimal, targeted, no rewrites):

1. **S3 — DCode default endpoint divergence.** Set `DCodeCodingHarnessAdapter.DEFAULT_ENDPOINT` and `DCodeHttpClient` fallback to `http://localhost:58080` (evo-code), and TRYST's `DCodeCodingHarnessAdapter` to 58080. Effort: trivial constant change + re-run Darwin→DCode and TRYST→DCode delegation tests. Unblocks two family flows.
2. **S2 — DCode headless launcher** "Unable to acquire application service" (evolution D-9). Investigate OSGi service acquisition ordering in the headless product. Effort: diagnosis first.
3. **S5 — TRYST `EvoIntegrationHandler` authorization.** Add the same deny-by-default/RBAC enforcement used by the other handlers. Effort: small, mirrors existing `AdminHandler` pattern.
4. **S4 — EVO Platform test debt triage.** Classify the 27F+18E (→74F+79E) into: non-hermetic (fix test), assertion drift (fix test), real defects (fix code). Do NOT mark RESOLVED without a passing re-run.
5. **S1/S6 — Native model quality & GPU.** Introduce instruction/Q-A training examples or an SFT stage (dataset change, not a code patch); add quantized-GGUF (GGML type 8) tensor support to ENR native runtime or route quantized models via the provider gateway. Effort: significant; schedule separately.
6. **S8/S12 — ENR protocol & config drift.** Map concurrent-session rejection to 429/503; implement `/api/create` (or document as deferred); update stale config paths.
7. **S7/S9/S10/S11 — minor tail.** Triage the P3 items; many are "documented limitation" and should be explicitly accepted or scheduled, not left silent.

---

## P. Phase Plan (Phase 14)

Recommended repair sequence (minimal, regression-guarded):

1. **Phase R1 — Integration unblock (S3, S2):** fix DCode endpoint + headless launcher; verify Darwin→DCode and TRYST→DCode delegation.
2. **Phase R2 — Security closure (S5 + posture review):** `EvoIntegrationHandler` authz; decide and document dev-mode default posture.
3. **Phase R3 — EVO test debt triage (S4):** classify and fix; restore a green EVO suite.
4. **Phase R4 — Native model capability (S1/S6):** dataset/SFT + quantized-tensor support (parallelizable with R1–R3).
5. **Phase R5 — Hygiene (S7/S8/S9/S10/S11/S12):** protocol status codes, config drift, minor tail; explicitly accept-or-schedule each documented limitation.

Each phase's acceptance criterion is a passing re-run of the specific regression test that currently fails or blocks (traceable to Section F IDs).

---

## Q. Cloud Handoff (Phase 15) — self-contained

### Q.1 Current state summary for cloud implementation

The ecosystem is a **Java 21 Eclipse RCP/Equinox/OSGi family** with an EMF data layer. It is **partially functional** and **not production-ready**: the integration backbone (TEIP 1.1, DCode harness, ENR inference/gateway) is live-verified, but native-model quality is limited, EVO's own test suite has untriaged failures, and security/port posture is inconsistent. **No P0 blockers**; 2 P1, 4 P2, ~35 P3 open defects (Section F).

### Q.2 Mandatory implementation constraints

Any cloud implementation MUST:
- Preserve **Java 21** and the **Eclipse RCP / Equinox / OSGi** runtime model; do not re-platform.
- Preserve the **EMF** data layer and existing persistence (SQLite/JSON) and **session lifecycle** semantics.
- Preserve **application boundaries** and the wire-only integration model (TEIP 1.1 `/teip/v1`, FHC 1.0 `/api/v1`, ENR 1.0 `/enr/v1`); no new cross-app class dependencies.
- Make only **minimal, targeted changes**; no rewrites, migrations, or parallel implementations.
- Add a **regression test for every repair**; a finding may be marked RESOLVED only when its verification test passes.
- Have the model **inspect source code first** and **stop and report on any discrepancy** between documentation and implementation (the corpus already shows doc/code drift, e.g., TRYST TCP 9090 vs 38280, ENR enabled=false vs default-inference assumption).

### Q.3 Cloud-specific considerations (from evidence)

- **Ports/config** are environment-dependent (ENR at 28780/8111/18081 across reports); cloud deployment must resolve the authoritative `~/.evo/ports.properties` and the family convention before binding. Note `ENR.enabled=false` there vs reports assuming it is the default inference engine.
- **Secrets/tokens** are intentionally not persisted in some paths (TRYST DCode/DStudio tokens runtime-only); a cloud deployment needs an explicit secret-management decision, not silent persistence.
- **Dev-mode default-on** (TRYST) and **single-token auth** (DCode) must be explicitly reviewed for cloud exposure before any non-loopback exposure.
- **GPU/CUDA** is currently BLOCKED by local VRAM and NOT IMPLEMENTED for training; cloud GPU availability does not change that the training path must first be completed locally/verified.

---

## R. Regression Strategy (Phase 16)

1. **Extend the authoritative audit to the 5 un-audited apps** (tryst, evo-studio, evo-code, upm, evo) using the existing `evo-audit` storage — do NOT create a parallel audit store.
2. **Close the "fixed but not re-verified" gap:** `enr-f1/f2/f3/f5/f6` are fixed but lack a later-run confirmation. Schedule re-runs so the failures-index can record verified RESOLVED (not "disappearedUnverified", as iter4 already reports for 2 items).
3. **Every repair (Section O) gets a named regression test** and a follow-up audit run; a finding becomes RESOLVED only on a passing re-run.
4. **Track systemic root causes as shared findings** (Section N), so one root cause is not re-counted as multiple defects (e.g., the port-divergence root cause has 3 code-site manifestations but is ONE systemic finding).
5. **Reconcile future reports by date/branch/commit**, never assuming different baselines describe the same state; the corpus's lack of commit SHAs (most reports) must be corrected going forward.

---

## Audit-Completion Classification

- **Task:** consolidation of existing reports (read-only). Not a new audit campaign.
- **Previous history loaded:** YES (6 runs).
- **Approved baseline loaded:** NO / NOT_AVAILABLE.
- **Test execution:** none performed (this task is documentation only).
- **Persistence:** this report is the sole deliverable; no audit-run was created (correct — no new tests were executed).
- **Classification:** COMPLETED_WITH_LIMITATIONS — evidence is comprehensive but REPORTED findings (5 of 7 apps) are not machine-verified, most reports lack commit SHAs, and no approved baseline exists.

**Remaining limitations:** (1) only 2 of 7 apps are machine-verified; (2) most reports lack git commit SHAs, limiting exact reproducibility; (3) the 48080-vs-58080 and doc/code port discrepancies are documented but their runtime resolution was not re-tested here; (4) `evo-221126` is an untested placeholder; (5) severity counts depend on the stated mapping methodology.
