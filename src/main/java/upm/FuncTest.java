package upm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Instance-level functional testing engine.
 *
 * <p>A functional test verifies that a managed product instance actually
 * <em>works</em> in a specific execution mode — not merely that it can be built
 * (BUILD) or started (RUN). Each test produces a structured, evidence-bearing
 * run record with per-step PASS/FAIL/PARTIAL/BLOCKED/NOT_IMPLEMENTED outcomes,
 * captured output and artifacts.
 *
 * <p>The engine is deliberately honest: it performs real operations (invoke a
 * real CLI, start a real process, issue real HTTP requests, submit a real LLM
 * inference) and reports the measured result. When a product is not built, a
 * display is unavailable, a provider is not configured, or GUI automation is
 * missing, the affected step is reported explicitly — never simulated as
 * success.
 */
public final class FuncTest {

    /** Execution modes, each tested independently per instance. */
    public enum Mode {
        HEADLESS("HEADLESS/TERMINAL"),
        RCP("RCP"),
        WEB("WEB");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public static Mode from(String s) {
            if (s == null) {
                return WEB;
            }
            return switch (s.trim().toLowerCase(Locale.ROOT)) {
                case "headless", "terminal", "cli" -> HEADLESS;
                case "rcp", "gui", "eclipse" -> RCP;
                default -> WEB;
            };
        }
    }

    /** Outcome status for a run and its individual steps. */
    public enum Status {
        PASS, FAIL, PARTIAL, SKIP, BLOCKED, NOT_IMPLEMENTED, NOT_RUN;

        public static Status worst(Status a, Status b) {
            return rank(a) >= rank(b) ? a : b;
        }

        private static int rank(Status s) {
            return switch (s) {
                case FAIL -> 6;
                case BLOCKED -> 5;
                case PARTIAL -> 4;
                case NOT_IMPLEMENTED -> 3;
                case SKIP -> 2;
                case NOT_RUN -> 1;
                case PASS -> 0;
            };
        }
    }

    /** A single execution step with expected/actual result and evidence. */
    public static final class Step {
        public final int seq;
        public final String name;
        public final String description;
        public final String expected;
        public volatile Status status = Status.NOT_RUN;
        public volatile String actual = "";
        public volatile String error = "";
        public volatile long startedMs = -1;
        public volatile long endedMs = -1;
        public final StringBuilder log = new StringBuilder();
        public final List<Map<String, Object>> evidence = new ArrayList<>();

        Step(int seq, String name, String description, String expected) {
            this.seq = seq;
            this.name = name;
            this.description = description == null ? "" : description;
            this.expected = expected == null ? "" : expected;
        }

        public Step start() {
            startedMs = System.currentTimeMillis();
            return this;
        }

        public Step done(Status status, String actual, String error) {
            this.status = status;
            this.actual = redact(actual == null ? "" : actual);
            this.error = redact(error == null ? "" : error);
            this.endedMs = System.currentTimeMillis();
            return this;
        }

        public Step pass(String actual) {
            return done(Status.PASS, actual, "");
        }

        public Step fail(String actual, String error) {
            return done(Status.FAIL, actual, error);
        }

        public Step partial(String actual, String error) {
            return done(Status.PARTIAL, actual, error);
        }

        public Step blocked(String actual, String reason) {
            return done(Status.BLOCKED, actual, reason);
        }

        public Step notImplemented(String actual, String reason) {
            return done(Status.NOT_IMPLEMENTED, actual, reason);
        }

        public Step skip(String actual, String reason) {
            return done(Status.SKIP, actual, reason);
        }

        public void log(String text) {
            if (text == null || text.isEmpty()) {
                return;
            }
            synchronized (log) {
                log.append(text);
                if (!text.endsWith("\n")) {
                    log.append('\n');
                }
                if (log.length() > 64 * 1024) {
                    log.delete(0, log.length() - 64 * 1024);
                }
            }
        }

        public void evidence(String kind, String name, String value) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("kind", kind);
            e.put("name", name);
            e.put("value", redact(value));
            evidence.add(e);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seq", seq);
            m.put("name", name);
            m.put("description", description);
            m.put("expected", expected);
            m.put("status", status.name());
            m.put("actual", actual);
            m.put("error", error);
            m.put("startedMs", startedMs);
            m.put("endedMs", endedMs);
            m.put("durationMs", startedMs >= 0 && endedMs >= 0 ? endedMs - startedMs : -1);
            synchronized (log) {
                m.put("log", redact(log.toString()));
            }
            m.put("evidence", new ArrayList<>(evidence));
            return m;
        }
    }

    /** A complete functional test run. */
    public static final class Run {
        public final long id;
        public final String projectId;
        public final String projectName;
        public final String launchConfigId;
        public final Mode mode;
        public final String scenario;
        public volatile long startMs;
        public volatile long endMs = -1;
        public volatile Status status = Status.NOT_RUN;
        public volatile String summary = "";
        public final List<Step> steps = new ArrayList<>();
        public final Map<String, Object> diagnostics = new LinkedHashMap<>();
        public final List<String> artifacts = new ArrayList<>();
        public volatile boolean cleanedUp = false;

        // Runtime context (not serialized).
        public transient Config.Project project;
        public transient Config.LaunchConfig lc;

        Run(long id, String projectId, String projectName, String launchConfigId,
                Mode mode, String scenario) {
            this.id = id;
            this.projectId = projectId;
            this.projectName = projectName;
            this.launchConfigId = launchConfigId;
            this.mode = mode;
            this.scenario = scenario;
            this.startMs = System.currentTimeMillis();
        }

        public Step step(String name, String description, String expected) {
            Step s = new Step(steps.size() + 1, name, description, expected);
            steps.add(s);
            return s;
        }

        public void diagnostic(String key, Object value) {
            diagnostics.put(key, value);
        }

        public void finish(Status status, String summary) {
            this.status = status;
            this.summary = summary == null ? "" : summary;
            this.endMs = System.currentTimeMillis();
        }

        public long durationMs() {
            return endMs > 0 ? endMs - startMs : System.currentTimeMillis() - startMs;
        }

        public int count(Status s) {
            int n = 0;
            for (Step st : steps) {
                if (st.status == s) {
                    n++;
                }
            }
            return n;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("project", projectId);
            m.put("projectName", projectName);
            m.put("launchConfig", launchConfigId);
            m.put("mode", mode.name());
            m.put("modeLabel", mode.label);
            m.put("scenario", scenario);
            m.put("status", status.name());
            m.put("summary", summary);
            m.put("startMs", startMs);
            m.put("endMs", endMs);
            m.put("durationMs", durationMs());
            m.put("cleanedUp", cleanedUp);
            m.put("steps", stepsJson());
            m.put("diagnostics", new LinkedHashMap<>(diagnostics));
            m.put("artifacts", new ArrayList<>(artifacts));
            return m;
        }

        public Map<String, Object> summaryJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("project", projectId);
            m.put("launchConfig", launchConfigId);
            m.put("mode", mode.name());
            m.put("modeLabel", mode.label);
            m.put("status", status.name());
            m.put("summary", summary);
            m.put("startMs", startMs);
            m.put("endMs", endMs);
            m.put("durationMs", durationMs());
            m.put("scenario", scenario);
            return m;
        }

        private List<Map<String, Object>> stepsJson() {
            List<Map<String, Object>> out = new ArrayList<>();
            for (Step s : steps) {
                out.add(s.toJson());
            }
            return out;
        }
    }

    /** A testable instance: a (project, launch config, mode) triple. */
    public static final class Instance {
        public final String projectId;
        public final String projectName;
        public final String launchConfigId;
        public final String launchConfigName;
        public final Mode mode;
        public final String scenario;

        Instance(String projectId, String projectName, String launchConfigId,
                String launchConfigName, Mode mode, String scenario) {
            this.projectId = projectId;
            this.projectName = projectName;
            this.launchConfigId = launchConfigId;
            this.launchConfigName = launchConfigName;
            this.mode = mode;
            this.scenario = scenario;
        }

        public String key() {
            return FuncTest.key(projectId, launchConfigId, mode);
        }

        public Map<String, Object> toJson(Map<String, Object> last) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("key", key());
            m.put("project", projectId);
            m.put("projectName", projectName);
            m.put("launchConfig", launchConfigId);
            m.put("launchConfigName", launchConfigName);
            m.put("mode", mode.name());
            m.put("modeLabel", mode.label);
            m.put("scenario", scenario);
            m.put("lastResult", last);
            return m;
        }
    }

    private FuncTest() {
    }

    // ---------------------------------------------------------------- detect

    /** Modes derivable from a launch configuration. */
    public static List<Mode> deriveModes(Config.LaunchConfig lc) {
        List<Mode> out = new ArrayList<>();
        if (lc == null) {
            return out;
        }
        if (lc.requiresDisplay) {
            out.add(Mode.RCP);
            return out;
        }
        out.add(Mode.HEADLESS);
        if (lc.readiness != null && "http".equals(lc.readiness.type)) {
            out.add(Mode.WEB);
        }
        return out;
    }

    /** All independently-testable instances across the workspace. */
    public static List<Instance> instances(Config cfg) {
        List<Instance> out = new ArrayList<>();
        if (cfg == null) {
            return out;
        }
        for (Config.Project p : cfg.projects) {
            for (Config.LaunchConfig lc : p.launchConfigs) {
                for (Mode mode : deriveModes(lc)) {
                    out.add(new Instance(p.id, p.name, lc.id, lc.name, mode,
                            scenarioFor(p, lc, mode)));
                }
            }
        }
        return out;
    }

    public static String key(String projectId, String launchConfigId, Mode mode) {
        return projectId + "|" + launchConfigId + "|" + mode.name();
    }

    /** Classifies a project into a product family for scenario selection. */
    public static String classify(Config.Project p) {
        if (p.enrMonitor) {
            return "enr";
        }
        String s = (p.id + " " + p.name + " " + p.repoDir).toLowerCase(Locale.ROOT);
        if (s.contains("dcode") || s.contains("evo-code") || s.contains("evo_code")
                || s.contains("evocode")) {
            return "evocode";
        }
        if (s.contains("dstudio") || s.contains("evo-studio") || s.contains("evo_studio")
                || s.contains("evostudio")) {
            return "evostudio";
        }
        if (s.contains("tryst")) {
            return "tryst";
        }
        if (s.contains("enr") && "maven".equals(p.type)) {
            return "enr";
        }
        if (s.contains("evolution") || s.contains("evo")) {
            return "evo";
        }
        return "generic";
    }

    /** Human-readable scenario description for an instance. */
    public static String scenarioFor(Config.Project p, Config.LaunchConfig lc, Mode mode) {
        return switch (classify(p)) {
            case "enr" -> switch (mode) {
                case HEADLESS -> "ENR terminal LLM mediation: invoke the ENR CLI, submit a real "
                        + "inference request and verify the response returns through the configured provider.";
                case WEB -> "ENR web LLM mediation: call the ENR HTTP API, route a real inference "
                        + "request and verify the generated answer returns to the caller.";
                case RCP -> "ENR has no RCP interface.";
            };
            case "evocode", "evostudio", "tryst" -> switch (mode) {
                case HEADLESS -> p.name + " headless/terminal functional test: invoke the headless "
                        + "entrypoint and verify a representative operation completes.";
                case WEB -> p.name + " web functional test: verify the HTTP UI/API, backend integration "
                        + "and a representative user operation.";
                case RCP -> p.name + " RCP functional test: start the Eclipse RCP workbench and verify "
                        + "it reaches an operational state.";
            };
            default -> switch (mode) {
                case HEADLESS -> p.name + " headless/terminal functional test.";
                case WEB -> p.name + " web/HTTP functional test.";
                case RCP -> p.name + " RCP functional test.";
            };
        };
    }

    // ------------------------------------------------------------------ run

    /** Executes a functional test, populating and returning the run record. */
    public static Run run(App app, State state, Proc proc, State.Op op,
            Config.Project p, Config.LaunchConfig lc, Mode mode) {
        Run run = new Run(op.id, p.id, p.name, lc.id, mode, scenarioFor(p, lc, mode));
        run.project = p;
        run.lc = lc;
        op.phase = "Functional test";
        op.task = run.scenario;
        opAppend(op, "\n=== Functional test: " + p.id + " — " + lc.name + " (" + mode.label
                + ") ===\n" + run.scenario + "\n");
        try {
            switch (classify(p)) {
                case "enr" -> runEnr(app, state, proc, op, run);
                case "evocode", "evostudio", "tryst" -> runCoding(app, state, proc, op, run);
                default -> runGeneric(app, state, proc, op, run);
            }
        } catch (Throwable e) {
            opAppend(op, "Engine error: " + e + "\n");
            run.diagnostic("engineError", String.valueOf(e));
        }
        if (run.status == Status.NOT_RUN) {
            run.finish(Status.BLOCKED, "No steps executed.");
        }
        // Derive the overall status from steps if it has not been set explicitly.
        if (run.endMs < 0) {
            Status worst = Status.PASS;
            int executed = 0;
            for (Step s : run.steps) {
                if (s.status == Status.NOT_RUN) {
                    continue;
                }
                executed++;
                worst = Status.worst(worst, s.status);
            }
            run.finish(executed == 0 ? Status.BLOCKED : worst, summarize(run));
        }
        opAppend(op, "\nResult: " + run.status + " — " + run.summary + "\n");
        return run;
    }

    private static String summarize(Run run) {
        int pass = run.count(Status.PASS);
        int fail = run.count(Status.FAIL);
        int partial = run.count(Status.PARTIAL);
        int blocked = run.count(Status.BLOCKED);
        int notImpl = run.count(Status.NOT_IMPLEMENTED);
        int skipped = run.count(Status.SKIP);
        return pass + " passed, " + fail + " failed, " + partial + " partial, " + blocked
                + " blocked, " + notImpl + " not implemented, " + skipped + " skipped ("
                + run.steps.size() + " steps)";
    }

    // ---------------------------------------------------------- generic run

    private static void runGeneric(App app, State state, Proc proc, State.Op op, Run run) {
        switch (run.mode) {
            case RCP -> rcpScenario(app, state, proc, op, run, false);
            case WEB -> webScenario(app, state, proc, op, run, false);
            case HEADLESS -> headlessScenario(app, state, proc, op, run, false);
        }
    }

    private static void runCoding(App app, State state, Proc proc, State.Op op, Run run) {
        switch (run.mode) {
            case RCP -> rcpScenario(app, state, proc, op, run, true);
            case WEB -> webScenario(app, state, proc, op, run, true);
            case HEADLESS -> headlessScenario(app, state, proc, op, run, true);
        }
    }

    /** Starts the instance unless it is already running; returns reuse info. */
    private static final class StartResult {
        final boolean started;
        final boolean reuse;
        final String error;

        StartResult(boolean started, boolean reuse, String error) {
            this.started = started;
            this.reuse = reuse;
            this.error = error;
        }
    }

    private static StartResult ensureRunning(App app, State state, Proc proc, State.Op op, Run run) {
        Config.Project p = run.project;
        Config.LaunchConfig lc = run.lc;
        State.ProjectState ps = state.stateFor(p.id);
        if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
            return new StartResult(false, true, null);
        }
        int port = Probe.resolvePort(p, lc, lc.port > 0 ? lc.port : 0);
        if (port > 0 && lc.readiness != null && Probe.probeReady(p, lc, port)) {
            // Something already answers on the readiness endpoint (external).
            return new StartResult(false, true, null);
        }
        if (lc.requiresDisplay && (System.getenv("DISPLAY") == null
                || System.getenv("DISPLAY").isBlank())) {
            return new StartResult(false, false, "DISPLAY is not set; cannot start " + lc.name + ".");
        }
        String err;
        try {
            err = proc.start(p, lc, null, op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err = "Interrupted";
        }
        if (err != null) {
            return new StartResult(false, false, err);
        }
        return new StartResult(true, false, null);
    }

    private static void stopIfStarted(App app, State state, Proc proc, Run run, boolean started) {
        if (!started) {
            return;
        }
        try {
            String err = proc.stop(run.projectId, run.launchConfigId);
            run.cleanedUp = err == null;
        } catch (Exception e) {
            run.cleanedUp = false;
        }
    }

    /** HEADLESS/TERMINAL: invoke the entrypoint and verify a terminal operation. */
    private static void headlessScenario(App app, State state, Proc proc, State.Op op, Run run,
            boolean coding) {
        Config.Project p = run.project;
        Config.LaunchConfig lc = run.lc;
        Path launcher = resolveLauncher(p, lc);
        Step launcherStep = run.step("launcher", "Resolve the headless entrypoint",
                "The configured launcher exists and is executable.");
        launcherStep.start();
        if (lc.requiresDisplay) {
            launcherStep.blocked("requiresDisplay=true", "This launch configuration is an RCP "
                    + "interface, not a headless/terminal entrypoint.");
            opAppend(op, launcherStep.name + ": BLOCKED\n");
            run.finish(Status.BLOCKED, "Not a headless instance.");
            return;
        }
        boolean exists = launcher != null && Files.exists(launcher)
                && ("jar".equals(lc.kind) ? Files.isRegularFile(launcher) : Files.isExecutable(launcher));
        if (!exists) {
            launcherStep.blocked("launcher not built: " + (launcher == null ? lc.launcher : launcher),
                    "Build the product first (BUILD action).");
            opAppend(op, launcherStep.name + ": BLOCKED (not built)\n");
            run.finish(Status.BLOCKED, "Headless launcher is not built.");
            return;
        }
        launcherStep.pass("launcher present: " + launcher);

        // For products with a real HTTP readiness contract, "terminal" means
        // starting it from the terminal and verifying it reaches an operational
        // state; otherwise it is a one-shot CLI invocation.
        Step invoke = run.step("invoke", "Invoke the entrypoint from the terminal",
                "The entrypoint starts and produces terminal output.");
        invoke.start();
        StartResult start = ensureRunning(app, state, proc, op, run);
        if (start.error != null) {
            invoke.blocked("could not start: " + start.error, start.error);
            opAppend(op, invoke.name + ": BLOCKED — " + start.error + "\n");
            run.finish(Status.BLOCKED, "Could not start headless entrypoint.");
            return;
        }
        if (start.started) {
            opAppend(op, invoke.name + ": started (pid " + state.stateFor(p.id).pid + ")\n");
        } else {
            opAppend(op, invoke.name + ": instance already running, reusing\n");
        }
        // Verify operational state.
        State.ProjectState ps = state.stateFor(p.id);
        boolean operational = ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING");
        int port = Probe.resolvePort(p, lc, lc.port > 0 ? lc.port : 0);
        if (!operational && lc.readiness != null && port > 0) {
            operational = Probe.probeReady(p, lc, port);
        }
        if (operational) {
            invoke.pass("operational (state " + ps.runtimeState
                    + (port > 0 ? ", port " + port : "") + ")");
        } else {
            invoke.fail("not operational (state " + ps.runtimeState + ")",
                    "Entrypoint did not reach an operational state.");
        }
        Path log = proc.runtimeLog(p.id, lc.id);
        String tail = Proc.tailLog(log, 4000);
        invoke.log(tail);
        invoke.evidence("console-log", "runtime.log", tail);
        if (!tail.isBlank()) {
            opAppend(op, invoke.name + ": console output captured (" + tail.length() + " chars)\n");
        }
        stopIfStarted(app, state, proc, run, start.started);
        finishFromSteps(run, op);
    }

    /** WEB: start the server and exercise its HTTP interface with real calls. */
    private static void webScenario(App app, State state, Proc proc, State.Op op, Run run,
            boolean coding) {
        Config.Project p = run.project;
        Config.LaunchConfig lc = run.lc;
        if (lc.readiness == null || !"http".equals(lc.readiness.type)) {
            Step s = run.step("web-api", "Verify the HTTP interface",
                    "The launch configuration exposes an HTTP readiness endpoint.");
            s.start();
            s.blocked("no HTTP readiness configured",
                    "This launch configuration does not expose an HTTP interface; WEB mode is not applicable.");
            opAppend(op, s.name + ": BLOCKED\n");
            run.finish(Status.BLOCKED, "No HTTP interface for WEB mode.");
            return;
        }

        Step startStep = run.step("start", "Ensure the web instance is running",
                "The instance is running (reused or started for this test).");
        startStep.start();
        StartResult start = ensureRunning(app, state, proc, op, run);
        if (start.error != null) {
            startStep.blocked(start.error, start.error);
            opAppend(op, startStep.name + ": BLOCKED — " + start.error + "\n");
            run.finish(Status.BLOCKED, "Web instance could not be started.");
            return;
        }
        startStep.pass(start.reuse ? "already running (reused)" : "started for this test");

        int port = Probe.resolvePort(p, lc, lc.port > 0 ? lc.port : 0);
        String host = lc.readiness.host;
        run.diagnostic("host", host);
        run.diagnostic("port", port);
        String base = "http://" + host + ":" + port;

        // 1. Readiness contract.
        Step ready = run.step("readiness", "Verify the configured readiness contract",
                "HTTP " + lc.readiness.path + " answers with the expected status.");
        ready.start();
        HttpResult r = httpGet(base + lc.readiness.path, 4000);
        boolean readyOk = r.ok() && readinessMatches(r.status, lc.readiness.expect);
        if (readyOk) {
            ready.pass("HTTP " + r.status + " (" + r.durationMs + " ms)");
        } else {
            ready.fail("HTTP " + r.status + " " + describe(r), r.error);
        }
        ready.evidence("http-response", lc.readiness.path, r.body);
        opAppend(op, ready.name + ": " + ready.status + "\n");

        // 2. Health.
        Step health = run.step("health", "Verify the health endpoint",
                "The service reports itself as healthy.");
        health.start();
        HttpResult h = httpGet(base + "/health", 4000);
        if (h.ok()) {
            health.pass("HTTP " + h.status + " — " + truncate(h.body, 200));
        } else {
            // Fall back to the readiness path as the health signal.
            HttpResult h2 = httpGet(base + lc.readiness.path, 4000);
            if (h2.ok()) {
                health.partial("no /health endpoint; readiness endpoint answered HTTP " + h2.status,
                        "No /health endpoint on this product.");
            } else {
                health.fail("health unreachable", h.error);
            }
        }
        health.evidence("http-response", "/health", h.body);
        opAppend(op, health.name + ": " + health.status + "\n");

        // 3. Representative API / status.
        Step statusStep = run.step("status", "Verify the status API",
                "A status/info endpoint answers with JSON.");
        statusStep.start();
        HttpResult st = firstReachable(base, "/api/v1/status", "/api/status", "/api/v1/info");
        if (st != null && st.ok()) {
            statusStep.pass("HTTP " + st.status + " — " + truncate(st.body, 200));
            statusStep.evidence("http-response", st.url, st.body);
        } else {
            statusStep.partial("no reachable status endpoint",
                    "Could not reach a status endpoint; health/readiness still verified.");
        }
        opAppend(op, statusStep.name + ": " + statusStep.status + "\n");

        // 4. Backend integration (models/providers).
        Step models = run.step("models", "Verify LLM backend integration",
                "The product reports its available models/providers (ENR integration).");
        models.start();
        HttpResult md = firstReachable(base, "/api/v1/models", "/api/v1/providers", "/api/models");
        if (md != null && md.ok()) {
            models.pass("HTTP " + md.status + " — " + truncate(md.body, 200));
            models.evidence("http-response", md.url, md.body);
        } else {
            models.partial("no reachable models/providers endpoint",
                    "LLM backend listing was not reachable through the HTTP interface.");
        }
        opAppend(op, models.name + ": " + models.status + "\n");

        // 5. A representative user operation (real inference where discoverable).
        Step operation = run.step("operation", "Perform a representative user operation",
                "A real request completes and its result is visible in the response.");
        operation.start();
        HttpResult opRes = realInference(base, run, op);
        if (opRes == null) {
            operation.blocked("no reachable inference endpoint",
                    "No inference/chat endpoint was reachable on the HTTP interface.");
        } else if (opRes.ok()) {
            operation.pass("HTTP " + opRes.status + " — " + truncate(opRes.body, 200));
            operation.evidence("http-response", opRes.url, opRes.body);
        } else {
            operation.fail("HTTP " + opRes.status + " " + describe(opRes), opRes.error);
            operation.evidence("http-response", opRes.url, opRes.body);
        }
        opAppend(op, operation.name + ": " + operation.status + "\n");

        if (coding) {
            Step workflow = run.step("dev-workflow", "Development workflow (code modification)",
                    "A source-code modification is performed and verified on disk.");
            workflow.start();
            workflow.notImplemented("full source-modification workflow not exercised",
                    "The exact source-modification API contract was not discoverable from the "
                            + "running product; only health/status/models/inference were verified.");
            opAppend(op, workflow.name + ": NOT_IMPLEMENTED\n");
        }

        stopIfStarted(app, state, proc, run, start.started);
        finishFromSteps(run, op);
    }

    /** RCP: start the workbench and verify operational state (GUI automation optional). */
    private static void rcpScenario(App app, State state, Proc proc, State.Op op, Run run,
            boolean coding) {
        Step display = run.step("display", "Verify a graphical display is available",
                "DISPLAY is set for the Eclipse RCP workbench.");
        display.start();
        String disp = System.getenv("DISPLAY");
        if (disp == null || disp.isBlank()) {
            display.blocked("DISPLAY unset", "An Eclipse RCP GUI requires a graphical display.");
            opAppend(op, display.name + ": BLOCKED\n");
            run.finish(Status.BLOCKED, "No graphical display available for RCP.");
            return;
        }
        display.pass("DISPLAY=" + disp);

        Step built = run.step("built", "Verify the RCP product is built",
                "The RCP launcher exists and is executable.");
        built.start();
        Path launcher = resolveLauncher(run.project, run.lc);
        if (launcher == null || !Files.isExecutable(launcher)) {
            built.blocked("launcher not built: " + (launcher == null ? run.lc.launcher : launcher),
                    "Build the product first (BUILD action).");
            opAppend(op, built.name + ": BLOCKED\n");
            run.finish(Status.BLOCKED, "RCP product is not built.");
            return;
        }
        built.pass("launcher present: " + launcher);

        Step start = run.step("start", "Start the RCP workbench",
                "The workbench process starts and stays alive.");
        start.start();
        StartResult sr = ensureRunning(app, state, proc, op, run);
        if (sr.error != null) {
            start.blocked(sr.error, sr.error);
            opAppend(op, start.name + ": BLOCKED — " + sr.error + "\n");
            run.finish(Status.BLOCKED, "RCP workbench could not be started.");
            return;
        }
        State.ProjectState ps = state.stateFor(run.projectId);
        boolean operational = ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING");
        if (operational) {
            start.pass("operational (state " + ps.runtimeState + ", pid " + ps.pid + ")");
        } else {
            start.fail("not operational (state " + ps.runtimeState + ")",
                    "RCP workbench did not reach an operational state.");
        }
        Path log = proc.runtimeLog(run.projectId, run.lc.id);
        String tail = Proc.tailLog(log, 4000);
        start.log(tail);
        start.evidence("console-log", "runtime.log", tail);
        opAppend(op, start.name + ": " + start.status + "\n");

        Step gui = run.step("gui", "Verify GUI functionality",
                "Relevant functionality can be accessed through the GUI.");
        gui.start();
        gui.notImplemented("GUI automation unavailable",
                "No GUI automation driver is available; only startup and operational state "
                        + "were verified. The interactive GUI portion is NOT_VERIFIED.");
        opAppend(op, gui.name + ": NOT_IMPLEMENTED\n");

        stopIfStarted(app, state, proc, run, sr.started);
        finishFromSteps(run, op);
    }

    // ------------------------------------------------------------ ENR (Phase D)

    private static void runEnr(App app, State state, Proc proc, State.Op op, Run run) {
        switch (run.mode) {
            case WEB -> enrWeb(app, state, proc, op, run);
            case HEADLESS -> enrTerminal(app, state, proc, op, run);
            case RCP -> {
                Step s = run.step("rcp", "RCP interface", "ENR has no RCP interface.");
                s.start();
                s.notImplemented("no RCP interface", "ENR is a headless service; no RCP mode.");
                opAppend(op, s.name + ": NOT_IMPLEMENTED\n");
                run.finish(Status.NOT_IMPLEMENTED, "ENR has no RCP interface.");
            }
        }
    }

    private static String enrBase(Config.Project p) {
        String base = p.apiBase;
        if (base == null || base.isBlank()) {
            base = "http://127.0.0.1:28780/api/v1";
        }
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base;
    }

    /** Starts ENR unless it is already answering on its API base. */
    private static StartResult ensureEnrRunning(App app, State state, Proc proc, State.Op op, Run run) {
        Config.Project p = run.project;
        HttpResult h = httpGet(enrHealthUrl(enrBase(p)), 3000);
        if (h.ok()) {
            return new StartResult(false, true, null);
        }
        State.ProjectState ps = state.stateFor(p.id);
        if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
            return new StartResult(false, true, null);
        }
        return ensureRunning(app, state, proc, op, run);
    }

    private static String enrHealthUrl(String base) {
        // base like http://host:port/api/v1 -> health at /api/v1/health
        if (base.endsWith("/api/v1")) {
            return base + "/health";
        }
        return base + "/health";
    }

    private static void enrWeb(App app, State state, Proc proc, State.Op op, Run run) {
        Config.Project p = run.project;
        String base = enrBase(p);
        run.diagnostic("enrApiBase", base);

        Step start = run.step("enr-reachable", "Ensure ENR is running",
                "ENR answers on its HTTP API.");
        start.start();
        StartResult sr = ensureEnrRunning(app, state, proc, op, run);
        if (sr.error != null) {
            start.blocked(sr.error, sr.error);
            opAppend(op, start.name + ": BLOCKED — " + sr.error + "\n");
            run.finish(Status.BLOCKED, "ENR is not reachable and could not be started.");
            return;
        }
        start.pass(sr.reuse ? "already running (reused)" : "started for this test");

        // 1. Health (CALLER -> ENR).
        Step health = run.step("health", "Verify ENR health", "ENR reports status=healthy.");
        health.start();
        HttpResult h = httpGet(enrHealthUrl(base), 4000);
        if (h.ok() && h.body.contains("healthy")) {
            health.pass("HTTP " + h.status + " — " + truncate(h.body, 200));
        } else if (h.ok()) {
            health.partial("HTTP " + h.status + " but body: " + truncate(h.body, 200),
                    "Health body did not contain 'healthy'.");
        } else {
            health.fail("HTTP " + h.status + " " + describe(h), h.error);
        }
        health.evidence("http-response", "health", h.body);

        // 2. Status.
        Step status = run.step("status", "Verify ENR runtime status",
                "ENR reports running=true with version and metrics.");
        status.start();
        HttpResult st = httpGet(base + "/status", 4000);
        if (st.ok()) {
            status.pass("HTTP " + st.status + " — " + truncate(st.body, 300));
            status.evidence("http-response", "status", st.body);
        } else {
            status.fail("status unreachable", st.error);
        }
        opAppend(op, status.name + ": " + status.status + "\n");

        // 3. Providers (routing configuration + Ollama detection).
        Step providers = run.step("providers", "Inspect configured inference providers",
                "The configured provider(s) are listed; routing target identified.");
        providers.start();
        HttpResult pr = httpGet(base + "/providers", 4000);
        boolean ollamaConfigured = false;
        if (pr.ok()) {
            providers.pass("HTTP " + pr.status + " — " + truncate(pr.body, 300));
            providers.evidence("http-response", "providers", pr.body);
            ollamaConfigured = pr.body.toLowerCase(Locale.ROOT).contains("ollama");
        } else {
            providers.partial("providers endpoint unreachable",
                    "Could not list providers; routing configuration unknown.");
        }
        run.diagnostic("ollamaConfigured", ollamaConfigured);

        // 4. Model discovery (what a caller sees).
        Step models = run.step("models", "Discover routable models",
                "At least one model is routable through ENR.");
        models.start();
        HttpResult tags = httpGet(ollamaTagsUrl(base), 4000);
        String model = null;
        if (tags.ok()) {
            model = firstModel(tags.body);
            if (model != null) {
                models.pass("discovered model: " + model);
            } else {
                models.partial("no models listed", "ENR returned no routable models.");
            }
            models.evidence("http-response", "models", tags.body);
        } else {
            models.partial("model list unreachable", tags.error);
        }
        run.diagnostic("model", model == null ? "" : model);
        opAppend(op, models.name + ": " + models.status + "\n");

        // 5. Real non-streaming inference (CALLER -> ENR -> provider -> ENR -> CALLER).
        Step infer = run.step("inference", "Submit a real inference request",
                "A non-empty assistant answer is returned with usage/timing evidence.");
        infer.start();
        if (model == null) {
            infer.blocked("no model to test", "No routable model discovered.");
        } else {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", model);
            req.put("messages", List.of(Map.of("role", "user",
                    "content", "Reply with exactly the single word: PONG")));
            req.put("stream", false);
            long t0 = System.currentTimeMillis();
            HttpResult inf = httpPost(chatUrl(base), Json.write(req), 120_000);
            long reqMs = System.currentTimeMillis() - t0;
            run.diagnostic("inferenceRequestMs", reqMs);
            if (inf.ok()) {
                String content = extractChatContent(inf.body);
                run.diagnostic("inferenceModel", jsonField(inf.body, "model"));
                run.diagnostic("inferenceEvalCount", jsonField(inf.body, "eval_count"));
                run.diagnostic("inferencePromptEvalCount", jsonField(inf.body, "prompt_eval_count"));
                if (content != null && !content.isBlank()) {
                    infer.pass("content: " + truncate(content, 200) + " (" + reqMs + " ms)");
                } else {
                    infer.fail("empty assistant content (0 tokens generated)",
                            "ENR routed the request but the model generated no content. Raw: "
                                    + truncate(inf.body, 300));
                }
                infer.evidence("http-response", "inference", inf.body);
            } else {
                infer.fail("HTTP " + inf.status + " " + describe(inf), inf.error);
                infer.evidence("http-response", "inference", inf.body);
            }
        }
        opAppend(op, infer.name + ": " + infer.status + "\n");

        // 6. Streaming inference (if supported).
        Step stream = run.step("streaming", "Test streaming inference",
                "Streaming returns incremental chunks and a final completion.");
        stream.start();
        if (model == null) {
            stream.skip("no model", "No model to test streaming.");
        } else {
            StringBuilder chunks = new StringBuilder();
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", model);
            req.put("messages", List.of(Map.of("role", "user",
                    "content", "Count from one to three.")));
            req.put("stream", true);
            HttpResult sr2 = httpPostStream(chatUrl(base), Json.write(req), 120_000, chunks);
            if (sr2.ok() && chunks.length() > 0) {
                stream.pass("streamed " + chunks.length() + " bytes");
            } else if (sr2.ok()) {
                stream.partial("HTTP 200 but no streamed chunks",
                        "Streaming responded but produced no incremental output.");
            } else {
                stream.partial("streaming failed: HTTP " + sr2.status + " " + describe(sr2),
                        sr2.error + (chunks.length() > 0 ? "\n" + truncate(chunks.toString(), 400) : ""));
            }
            stream.evidence("http-stream", "inference/stream", truncate(chunks.toString(), 1000));
        }
        opAppend(op, stream.name + ": " + stream.status + "\n");

        // 7. Ollama leg — explicit and honest.
        Step ollama = run.step("ollama-leg", "Verify the Ollama mediation leg",
                "The request is routed to a configured Ollama provider and Ollama performs real inference.");
        ollama.start();
        if (ollamaConfigured) {
            ollama.partial("Ollama provider configured; inference leg exercised via the routed "
                    + "request above.", "Routing evidence captured in the inference step.");
        } else {
            // Report the truth and capture direct Ollama reachability as evidence.
            HttpResult direct = httpGet("http://127.0.0.1:11434/api/tags", 3000);
            String directInfo = direct.ok()
                    ? "Ollama reachable directly at 127.0.0.1:11434 (but not configured as an ENR "
                            + "inference provider)."
                    : "Ollama not reachable at 127.0.0.1:11434.";
            ollama.blocked(directInfo,
                    "Ollama is not configured as an ENR inference provider; the ENR->Ollama leg "
                            + "could not be exercised. Configure the Ollama provider in ENR and re-run.");
            if (direct.ok()) {
                ollama.evidence("http-response", "ollama-direct-tags", direct.body);
            }
        }
        opAppend(op, ollama.name + ": " + ollama.status + "\n");

        stopIfStarted(app, state, proc, run, sr.started);
        finishFromSteps(run, op);
    }

    private static void enrTerminal(App app, State state, Proc proc, State.Op op, Run run) {
        Config.Project p = run.project;
        String base = enrBase(p);

        // Ensure ENR is running (the terminal caller talks to it).
        Step server = run.step("enr-running", "Ensure ENR is running",
                "ENR answers on its HTTP API.");
        server.start();
        StartResult sr = ensureEnrRunning(app, state, proc, op, run);
        if (sr.error != null) {
            server.blocked(sr.error, sr.error);
            opAppend(op, server.name + ": BLOCKED — " + sr.error + "\n");
            run.finish(Status.BLOCKED, "ENR is not reachable and could not be started.");
            return;
        }
        server.pass(sr.reuse ? "already running (reused)" : "started for this test");

        // Resolve the ENR CLI.
        Step cli = run.step("cli", "Resolve the ENR terminal CLI",
                "The enr CLI (shaded jar or classes) is available.");
        cli.start();
        List<String> cliCmd = enrCliCommand(p, proc);
        if (cliCmd == null) {
            cli.blocked("ENR CLI not built",
                    "Build the ENR CLI (enr-cli/target/enr-cli-*.jar) first.");
            opAppend(op, cli.name + ": BLOCKED\n");
            run.finish(Status.BLOCKED, "ENR terminal CLI is not built.");
            return;
        }
        cli.pass("CLI: " + String.join(" ", cliCmd));

        // enr version — tolerant of older CLI builds that lack this optional subcommand.
        Step version = run.step("version", "Invoke 'enr version'",
                "The ENR CLI prints its version, or explicitly reports the subcommand as unsupported.");
        version.start();
        ExecResult v = exec(cliCmd(List.of("version"), cliCmd), p.repoDir, 30_000);
        switch (versionStepStatus(v)) {
            case PASS -> version.pass(truncate(v.output, 120));
            case SKIP -> version.skip(UNSUPPORTED_VERSION_MSG,
                    "The 'version' subcommand is not available in this ENR CLI build; "
                            + "'status' and 'infer' remain the primary terminal checks.");
            default -> version.fail("exit " + v.exit + " — " + truncate(v.output, 200), v.error);
        }
        version.log(v.output);

        // enr status.
        Step status = run.step("status", "Invoke 'enr status'",
                "The CLI reports the running ENR server status.");
        status.start();
        ExecResult st = exec(cliCmd(List.of("status"), cliCmd), p.repoDir, 30_000);
        if (st.exit == 0 && st.output.contains("running")) {
            status.pass(truncate(st.output, 200));
        } else {
            status.fail("exit " + st.exit + " — " + truncate(st.output, 200), st.error);
        }
        status.log(st.output);
        status.evidence("cli-output", "enr-status", st.output);
        opAppend(op, status.name + ": " + status.status + "\n");

        // Real inference through the terminal caller (one-shot, non-interactive).
        Step infer = run.step("inference", "Submit a real inference via the terminal",
                "The CLI returns a non-empty generated answer (not merely a summary line).");
        infer.start();
        String model = discoverModel(base);
        run.diagnostic("model", model == null ? "" : model);
        if (model == null) {
            infer.blocked("no routable model", "Could not discover a routable model.");
        } else {
            ExecResult inf = exec(cliCmd(List.of("infer", model,
                    "Reply with exactly the single word: PONG", "--max-tokens", "32"), cliCmd),
                    p.repoDir, 120_000);
            String content = stripSummaryMeta(inf.output);
            boolean zeroTokens = inf.output.contains("generated=0")
                    || inf.output.contains("0 tokens")
                    || inf.output.contains("MAX_SEQ_LEN_REACHED");
            if (inf.exit != 0) {
                infer.fail("exit " + inf.exit + " — " + truncate(inf.output, 200), inf.error);
            } else if (zeroTokens) {
                infer.fail("0 tokens generated",
                        "The terminal caller submitted the request and ENR routed it, but the model "
                                + "generated no content. Raw: " + truncate(inf.output, 300));
            } else if (!content.isBlank()) {
                infer.pass(truncate(content, 200));
            } else {
                infer.fail("empty response",
                        "The terminal caller received an empty answer. Raw output: "
                                + truncate(inf.output, 300));
            }
            infer.log(inf.output);
            infer.evidence("cli-output", "enr-infer", inf.output);
        }
        opAppend(op, infer.name + ": " + infer.status + "\n");

        stopIfStarted(app, state, proc, run, sr.started);
        finishFromSteps(run, op);
    }

    // ------------------------------------------------------- ENR version

    /** SKIP explanation used when the ENR CLI lacks the optional 'version' subcommand. */
    static final String UNSUPPORTED_VERSION_MSG = "Version subcommand not supported by this ENR CLI build.";

    /** Explicit CLI messages that indicate a subcommand is unknown/unsupported. */
    private static final List<String> UNSUPPORTED_SUBCOMMAND_MARKERS = List.of(
            "unknown command", "unknown subcommand",
            "unrecognized command", "unrecognized subcommand",
            "unsupported command", "unsupported subcommand",
            "invalid command", "invalid subcommand",
            "no such command", "no such subcommand",
            "not a command", "not a subcommand");

    /**
     * Classifies the {@code enr version} invocation result.
     * <ul>
     *   <li>{@code exit == 0} — the subcommand is supported → PASS.</li>
     *   <li>explicit "unknown/unsupported subcommand" output → SKIP.</li>
     *   <li>any other failure (missing executable, Java error, timeout,
     *       permission denied, unexpected termination) → FAIL.</li>
     * </ul>
     */
    static Status versionStepStatus(ExecResult v) {
        if (v.exit == 0) {
            return Status.PASS;
        }
        if (isUnsupportedSubcommand(v.output)) {
            return Status.SKIP;
        }
        return Status.FAIL;
    }

    /** True when the CLI explicitly reports a subcommand as unknown/unsupported. */
    static boolean isUnsupportedSubcommand(String output) {
        if (output == null || output.isEmpty()) {
            return false;
        }
        String s = output.toLowerCase(Locale.ROOT);
        for (String marker : UNSUPPORTED_SUBCOMMAND_MARKERS) {
            if (s.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // --------------------------------------------------------- step helpers

    private static void finishFromSteps(Run run, State.Op op) {
        Status worst = Status.PASS;
        int executed = 0;
        for (Step s : run.steps) {
            if (s.status == Status.NOT_RUN) {
                continue;
            }
            executed++;
            worst = Status.worst(worst, s.status);
        }
        run.finish(executed == 0 ? Status.BLOCKED : worst, summarize(run));
    }

    private static void opAppend(State.Op op, String text) {
        if (op == null || text == null) {
            return;
        }
        synchronized (op.live) {
            op.live.append(text);
            if (op.live.length() > 512 * 1024) {
                op.live.delete(0, op.live.length() - 512 * 1024);
            }
        }
    }

    // ------------------------------------------------------------- http io

    static final class HttpResult {
        final int status;
        final String body;
        final String error;
        final long durationMs;
        final String url;

        HttpResult(int status, String body, String error, long durationMs, String url) {
            this.status = status;
            this.body = body == null ? "" : body;
            this.error = error == null ? "" : error;
            this.durationMs = durationMs;
            this.url = url;
        }

        boolean ok() {
            return error.isEmpty() && status >= 200 && status < 300;
        }
    }

    static HttpResult httpGet(String url, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/json, text/plain");
            int code = conn.getResponseCode();
            String body = readStream(code, conn);
            conn.disconnect();
            return new HttpResult(code, body, "", System.currentTimeMillis() - t0, url);
        } catch (IOException e) {
            return new HttpResult(-1, "", e.getMessage(), System.currentTimeMillis() - t0, url);
        }
    }

    static HttpResult httpPost(String url, String jsonBody, int timeoutMs) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            String body = readStream(code, conn);
            conn.disconnect();
            return new HttpResult(code, body, "", System.currentTimeMillis() - t0, url);
        } catch (IOException e) {
            return new HttpResult(-1, "", e.getMessage(), System.currentTimeMillis() - t0, url);
        }
    }

    /** POST that reads a newline-delimited / chunked streaming response body. */
    static HttpResult httpPostStream(String url, String jsonBody, int timeoutMs,
            StringBuilder collector) {
        long t0 = System.currentTimeMillis();
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = "";
            if (in != null) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    String piece = new String(buf, 0, n, StandardCharsets.UTF_8);
                    collector.append(piece);
                    body += piece;
                }
            }
            conn.disconnect();
            return new HttpResult(code, body, "", System.currentTimeMillis() - t0, url);
        } catch (IOException e) {
            return new HttpResult(-1, "", e.getMessage(), System.currentTimeMillis() - t0, url);
        }
    }

    private static String readStream(int code, HttpURLConnection conn) throws IOException {
        InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
        if (in == null) {
            return "";
        }
        try (InputStream s = in) {
            return new String(s.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static boolean readinessMatches(int code, String expect) {
        if ("any".equals(expect)) {
            return code > 0;
        }
        try {
            return code == Integer.parseInt(expect);
        } catch (NumberFormatException e) {
            return code >= 200 && code < 300;
        }
    }

    private static String describe(HttpResult r) {
        return r.error.isEmpty() ? "(" + r.durationMs + " ms)" : r.error;
    }

    /** Returns the first reachable endpoint's result, or null. */
    private static HttpResult firstReachable(String base, String... paths) {
        for (String p : paths) {
            HttpResult r = httpGet(base + p, 4000);
            if (r.ok()) {
                return r;
            }
        }
        return null;
    }

    /** Attempts a real inference through a set of candidate endpoints. */
    private static HttpResult realInference(String base, Run run, State.Op op) {
        List<String> candidates = List.of(
                "/api/v1/inference/chat", "/api/v1/chat", "/api/chat", "/api/v1/inference");
        for (String p : candidates) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("model", "");
            req.put("messages", List.of(Map.of("role", "user",
                    "content", "Reply with exactly the single word: PONG")));
            req.put("prompt", "Reply with exactly the single word: PONG");
            HttpResult r = httpPost(base + p, Json.write(req), 120_000);
            if (r.status >= 200 && r.status < 300) {
                opAppend(op, "  (inference endpoint " + p + " answered HTTP " + r.status + ")\n");
                return r;
            }
        }
        return null;
    }

    // ------------------------------------------------------------ ENR utils

    private static String ollamaTagsUrl(String base) {
        String root = base;
        if (root.endsWith("/api/v1")) {
            root = root.substring(0, root.length() - "/api/v1".length());
        }
        return root + "/api/tags";
    }

    private static String chatUrl(String base) {
        String root = base;
        if (root.endsWith("/api/v1")) {
            root = root.substring(0, root.length() - "/api/v1".length());
        }
        return root + "/api/chat";
    }

    private static String discoverModel(String base) {
        HttpResult tags = httpGet(ollamaTagsUrl(base), 4000);
        if (tags.ok()) {
            String m = firstModel(tags.body);
            if (m != null) {
                return m;
            }
        }
        // Fall back to ENR native model listing.
        HttpResult models = httpGet(base + "/models", 4000);
        if (models.ok()) {
            String m = firstModel(models.body);
            if (m != null) {
                return m;
            }
        }
        return null;
    }

    private static String firstModel(String body) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(body));
            List<Map<String, Object>> models = Json.objList(m, "models");
            if (!models.isEmpty()) {
                String name = Json.str(models.get(0), "name");
                if (name == null) {
                    name = Json.str(models.get(0), "id");
                }
                return name;
            }
            List<Map<String, Object>> loaded = Json.objList(m, "loaded");
            if (!loaded.isEmpty()) {
                return Json.str(loaded.get(0), "id");
            }
            Object id = m.get("id");
            if (id instanceof String s && !s.isBlank()) {
                return s;
            }
        } catch (Exception ignored) {
            // not JSON
        }
        return null;
    }

    private static String extractChatContent(String body) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(body));
            Map<String, Object> message = Json.objField(m, "message");
            if (message != null) {
                String c = Json.str(message, "content");
                if (c != null) {
                    return c;
                }
            }
            String c = Json.str(m, "content");
            if (c != null) {
                return c;
            }
            String r = Json.str(m, "response");
            if (r != null) {
                return r;
            }
            String text = Json.str(m, "text");
            if (text != null) {
                return text;
            }
        } catch (Exception ignored) {
            // best effort
        }
        return "";
    }

    private static String jsonField(String body, String key) {
        try {
            Map<String, Object> m = Json.obj(Json.parse(body));
            Object v = m.get(key);
            return v == null ? "" : String.valueOf(v);
        } catch (Exception e) {
            return "";
        }
    }

    /** Resolves the ENR CLI invocation (shaded jar preferred, else classes + cp). */
    private static List<String> enrCliCommand(Config.Project p, Proc proc) {
        Path repo = Path.of(p.repoDir);
        // Prefer the shaded CLI jar.
        Path cliTarget = repo.resolve("enr-cli/target");
        if (Files.isDirectory(cliTarget)) {
            try (var stream = Files.list(cliTarget)) {
                for (Path f : (Iterable<Path>) stream::iterator) {
                    String n = f.getFileName().toString();
                    if (n.startsWith("enr-cli-") && n.endsWith(".jar")
                            && !n.startsWith("original-")) {
                        List<String> cmd = new ArrayList<>();
                        cmd.add(javaBin());
                        cmd.add("--enable-native-access=ALL-UNNAMED");
                        cmd.add("-jar");
                        cmd.add(f.toString());
                        return cmd;
                    }
                }
            } catch (IOException ignored) {
                // fall through
            }
        }
        // Fall back to classes + classpath.
        Path classes = repo.resolve("enr-cli/target/classes");
        if (Files.isDirectory(classes)) {
            String cp = proc.enrClasspath(p);
            if (cp != null) {
                List<String> cmd = new ArrayList<>();
                cmd.add(javaBin());
                cmd.add("--enable-native-access=ALL-UNNAMED");
                cmd.add("-cp");
                cmd.add(classes + ":" + cp);
                cmd.add("ai.enr.cli.EnrCli");
                return cmd;
            }
        }
        return null;
    }

    private static String javaBin() {
        String jdk = String.valueOf(Adapters.detectTools().getOrDefault("java25", ""));
        if (!jdk.isBlank()) {
            return Path.of(jdk, "bin", "java").toString();
        }
        return System.getProperty("java.home") + "/bin/java";
    }

    private static List<String> cliCmd(List<String> args, List<String> cli) {
        List<String> cmd = new ArrayList<>(cli);
        cmd.addAll(args);
        return cmd;
    }

    // --------------------------------------------------------------- exec io

    static final class ExecResult {
        final int exit;
        final String output;
        final String error;

        ExecResult(int exit, String output, String error) {
            this.exit = exit;
            this.output = output == null ? "" : output;
            this.error = error == null ? "" : error;
        }
    }

    private static boolean isJvmNoise(String line) {
        if (line == null) {
            return false;
        }
        String t = line.strip();
        return t.startsWith("Picked up ") && t.contains("OPTIONS");
    }

    /** Removes one-shot inference summary lines (e.g. {@code [reason=…, generated=0]}). */
    private static String stripSummaryMeta(String output) {
        if (output == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : output.split("\n")) {
            String t = line.trim();
            if (t.startsWith("[") && t.endsWith("]")
                    && (t.contains("reason=") || t.contains("tokens") || t.contains("generated="))) {
                continue;
            }
            sb.append(line).append('\n');
        }
        return sb.toString().strip();
    }

    static ExecResult exec(List<String> cmd, String workDir, long timeoutMs) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workDir != null && !workDir.isBlank()) {
                pb.directory(Path.of(workDir).toFile());
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (isJvmNoise(line)) {
                        continue;
                    }
                    out.append(line).append('\n');
                }
            }
            if (!proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                proc.destroyForcibly();
                return new ExecResult(-1, out.toString(), "Timed out after " + timeoutMs + " ms");
            }
            return new ExecResult(proc.exitValue(), out.toString(), "");
        } catch (IOException e) {
            return new ExecResult(-1, "", "Failed to start: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new ExecResult(-1, "", "Interrupted");
        }
    }

    // --------------------------------------------------------------- launcher

    static Path resolveLauncher(Config.Project p, Config.LaunchConfig lc) {
        if (lc == null || lc.launcher == null || lc.launcher.isBlank()) {
            return null;
        }
        String l = lc.launcher;
        l = l.replace("{repo}", p.repoDir)
                .replace("{app}", "");
        if (l.contains("{java21}") || l.contains("{java25}") || l.contains("{enrCp}")) {
            // Java-binary launchers are resolved by the CLI helpers, not as files.
            return null;
        }
        l = App.expandHome(l);
        Path lp = Path.of(l);
        if (!lp.isAbsolute()) {
            lp = Path.of(p.repoDir, l);
        }
        return lp;
    }

    // -------------------------------------------------------------- redaction

    /** Redacts secrets (api keys, tokens, passwords) from arbitrary text. */
    public static String redact(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String r = text;
        // Authorization scheme + token first (Bearer/Basic), so the token is
        // removed before any generic key-value redaction sees the header name.
        r = r.replaceAll("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._\\-]+",
                "$1 ***REDACTED***");
        // Generic key/value secrets (handles key: value, key=value and the
        // JSON "key": "value" form).
        r = r.replaceAll(
                "(?i)([\"']?(?:api[_-]?key|apikey|access[_-]?token|refresh[_-]?token|"
                        + "auth[_-]?token|secret|password|passwd|private[_-]?key|token)[\"']?"
                        + "\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,\"'}]+)",
                "$1***REDACTED***");
        return r;
    }

    static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "…";
    }

    // ------------------------------------------------------------ persistence

    public static Path ftestDir(App app) {
        return app.dataDir.resolve("ftests");
    }

    public static Path ftestFile(App app, long id) {
        return ftestDir(app).resolve(id + ".json");
    }

    public static Path indexFile(App app) {
        return ftestDir(app).resolve("index.json");
    }

    public static void save(App app, Run run) {
        try {
            Path dir = ftestDir(app);
            Files.createDirectories(dir);
            Files.writeString(ftestFile(app, run.id), Json.write(run.toJson()));
        } catch (IOException e) {
            Main.log("WARN", "FuncTest persistence failed: " + e.getMessage());
        }
    }

    public static Run load(App app, long id) {
        try {
            Path f = ftestFile(app, id);
            if (!Files.exists(f)) {
                return null;
            }
            return fromJson(Json.obj(Json.parse(Files.readString(f))));
        } catch (Exception e) {
            return null;
        }
    }

    private static Run fromJson(Map<String, Object> m) {
        Run run = new Run(Json.longVal(m, "id", 0), Json.str(m, "project", ""),
                Json.str(m, "projectName", ""), Json.str(m, "launchConfig", ""),
                Mode.from(Json.str(m, "mode", "web")), Json.str(m, "scenario", ""));
        run.status = statusFrom(Json.str(m, "status", "NOT_RUN"));
        run.summary = Json.str(m, "summary", "");
        run.startMs = Json.longVal(m, "startMs", System.currentTimeMillis());
        run.endMs = Json.longVal(m, "endMs", -1);
        run.cleanedUp = Json.bool(m, "cleanedUp", false);
        Map<String, Object> d = Json.objField(m, "diagnostics");
        if (d != null) {
            run.diagnostics.putAll(d);
        }
        for (String a : Json.strList(m, "artifacts")) {
            run.artifacts.add(a);
        }
        for (Map<String, Object> sm : Json.objList(m, "steps")) {
            Step s = run.step(Json.str(sm, "name", "step"), Json.str(sm, "description", ""),
                    Json.str(sm, "expected", ""));
            s.status = statusFrom(Json.str(sm, "status", "NOT_RUN"));
            s.actual = Json.str(sm, "actual", "");
            s.error = Json.str(sm, "error", "");
            s.startedMs = Json.longVal(sm, "startedMs", -1);
            s.endedMs = Json.longVal(sm, "endedMs", -1);
            s.log(Json.str(sm, "log", ""));
            for (Map<String, Object> e : Json.objList(sm, "evidence")) {
                s.evidence.add(e);
            }
        }
        return run;
    }

    private static Status statusFrom(String s) {
        try {
            return Status.valueOf(s == null ? "NOT_RUN" : s);
        } catch (IllegalArgumentException e) {
            return Status.NOT_RUN;
        }
    }

    /** The persisted index: instance key -> last run summary. */
    public static Map<String, Object> latest(App app) {
        Map<String, Object> index = new LinkedHashMap<>();
        try {
            Path f = indexFile(app);
            if (Files.exists(f)) {
                Map<String, Object> root = Json.obj(Json.parse(Files.readString(f)));
                for (Map.Entry<String, Object> e : root.entrySet()) {
                    index.put(e.getKey(), e.getValue());
                }
            }
        } catch (Exception ignored) {
            // empty index
        }
        return index;
    }

    public static void writeLatest(App app, Run run) {
        try {
            Path f = indexFile(app);
            Files.createDirectories(f.getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            try {
                if (Files.exists(f)) {
                    root = Json.obj(Json.parse(Files.readString(f)));
                }
            } catch (Exception ignored) {
                root = new LinkedHashMap<>();
            }
            root.put(key(run.projectId, run.launchConfigId, run.mode), run.summaryJson());
            Files.writeString(f, Json.write(root));
        } catch (IOException e) {
            Main.log("WARN", "FuncTest index persistence failed: " + e.getMessage());
        }
    }

    /** All persisted run summaries (from index), newest first. */
    public static List<Map<String, Object>> allRuns(App app) {
        Map<String, Object> index = latest(app);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object v : index.values()) {
            if (v instanceof Map<?, ?> m) {
                @SuppressWarnings("unchecked")
                Map<String, Object> mm = (Map<String, Object>) m;
                out.add(new LinkedHashMap<>(mm));
            }
        }
        out.sort((a, b) -> Long.compare(Json.longVal(b, "endMs", 0), Json.longVal(a, "endMs", 0)));
        return out;
    }
}
