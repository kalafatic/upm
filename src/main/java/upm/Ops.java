package upm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Operation execution engine. Adapter-defined commands are executed here —
 * the engine owns process spawning, streaming, parsing, cancellation and
 * history; adapters only supply commands and capabilities.
 */
public final class Ops {

    private static final int MAX_LIVE_BUFFER = 512 * 1024;

    private final App app;
    private final State state;
    private final Proc proc;
    private final Map<Long, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, String> projectOp = new ConcurrentHashMap<>();

    private static final Pattern BUILDING = Pattern.compile("\\[INFO\\] Building\\s+([^\\s]+)(?:\\s+(\\d+)/(\\d+))?.*");
    private static final Pattern TESTS_RUN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    public Ops(App app, State state, Proc proc) {
        this.app = app;
        this.state = state;
        this.proc = proc;
    }

    // ------------------------------------------------------------- submission

    public synchronized String submit(String projectId, String type, String mode,
            Integer port, Map<String, Object> extra) {
        boolean global = "launchAll".equals(type) || "stopAll".equals(type)
                || "restartAll".equals(type);
        boolean profileOp = "profileLaunch".equals(type) || "profileStop".equals(type);
        Config cfg = Main.currentConfig;
        if (!global && !profileOp) {
            if (cfg == null || cfg.project(projectId) == null) {
                return "{\"error\":\"Unknown project: " + projectId + "\"}";
            }
            if ("run".equals(type)) {
                Config.Project p = cfg.project(projectId);
                Config.LaunchConfig lc = p.launch(mode);
                if (lc == null) {
                    return "{\"error\":\"Unsupported launch configuration '" + mode
                            + "' for " + projectId + " (available: "
                            + String.join(", ",
                                    p.launchConfigs.stream().map(l -> l.id).toList()) + ")\"}";
                }
                String current = projectOp.get(projectId);
                if (current != null && !current.equals("stop") && !current.equals("restart")) {
                    return "{\"error\":\"Project " + projectId + " already has an active operation ("
                            + current + ").\"}";
                }
                if (state.stateFor(projectId).runtimeState.equals("STOPPING")) {
                    return "{\"error\":\"Project " + projectId + " is stopping; wait until STOPPED.\"}";
                }
            } else if ("stop".equals(type) || "restart".equals(type)) {
                // allowed alongside runs
            } else {
                String current = projectOp.get(projectId);
                if (current != null) {
                    return "{\"error\":\"Project " + projectId + " already has an active operation ("
                            + current + ").\"}";
                }
                State.ProjectState ps = state.stateFor(projectId);
                if (("build".equals(type) || "cleanBuild".equals(type) || "update".equals(type)
                        || "test".equals(type) || "package".equals(type) || "verify".equals(type)
                        || "install".equals(type))
                        && (ps.runtimeState.equals("STARTING") || ps.runtimeState.equals("RUNNING")
                        || ps.runtimeState.equals("READY"))) {
                    return "{\"error\":\"Project " + projectId + " runtime is "
                            + ps.runtimeState + "; stop it before building.\"}";
                }
            }
        }
        State.Op op = state.newOp(projectId, type, mode);
        Thread t = new Thread(() -> execute(op, type, mode, port, extra), "upm-op-" + op.id);
        t.setDaemon(true);
        threads.put(op.id, t);
        if (!global && !profileOp) {
            projectOp.put(projectId, type);
        }
        t.start();
        return "{\"opId\":" + op.id + "}";
    }

    private void release(State.Op op) {
        if (!("launchAll".equals(op.type) || "stopAll".equals(op.type)
                || "restartAll".equals(op.type))) {
            projectOp.remove(op.projectId, op.type);
        }
        threads.remove(op.id);
    }

    // --------------------------------------------------------------- execute

    private void execute(State.Op op, String type, String mode, Integer port,
            Map<String, Object> extra) {
        try {
            switch (type) {
                case "build", "cleanBuild", "update", "package", "verify", "install" ->
                        runAdapterOp(op, op.projectId, type);
                case "test" -> runTests(op, op.projectId);
                case "run" -> runRuntime(op, op.projectId, mode, port);
                case "stop" -> runStop(op, op.projectId);
                case "restart" -> runRestart(op, op.projectId, mode);
                case "launchAll" -> launchAll(op);
                case "stopAll" -> stopAll(op);
                case "restartAll" -> restartAll(op);
                case "profileLaunch" -> profileLaunch(op, mode);
                case "profileStop" -> profileStop(op, mode);
                case "gitFetch" -> runGit(op, op.projectId, "fetch", null);
                case "gitPull" -> runGit(op, op.projectId, "pull", null);
                case "gitPush" -> runGit(op, op.projectId, "push", null);
                case "gitCommit" -> runGit(op, op.projectId, "commit",
                        extra == null ? null : (String) extra.get("message"));
                default -> state.finishOp(op, "FAILED", null, "Unknown operation type: " + type);
            }
        } catch (Throwable e) {
            state.finishOp(op, "FAILED", null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            release(op);
        }
    }

    // ------------------------------------------------------------ adapter ops

    private void runAdapterOp(State.Op op, String projectId, String kind) {
        Config.Project p = Main.currentConfig.project(projectId);
        Config.CommandBlock block = p.op(kind);
        if (block == null || !block.supported || block.cmd.isEmpty()) {
            state.finishOp(op, "FAILED", null, "Operation '" + kind
                    + "' is not configured for this project (adapter " + p.type + ").");
            return;
        }
        int exit = exec(op, p, block, kind, null, null);
        if (exit == Integer.MIN_VALUE) {
            return;
        }
        String what = opLabel(kind);
        if (exit == 0) {
            state.finishOp(op, "SUCCEEDED", what + " completed successfully.", null);
        } else {
            state.finishOp(op, "FAILED", what + " failed with exit code " + exit + ".",
                    lastErrorLine(op));
        }
    }

    private static String opLabel(String kind) {
        return switch (kind) {
            case "build" -> "Build";
            case "cleanBuild" -> "Clean build";
            case "update" -> "Dependency update";
            case "package" -> "Package";
            case "verify" -> "Verify";
            case "install" -> "Install";
            default -> kind;
        };
    }

    private void runTests(State.Op op, String projectId) {
        Config.Project p = Main.currentConfig.project(projectId);
        if (p.testSuites.isEmpty()) {
            Config.CommandBlock block = p.op("test");
            if (block != null && block.supported && !block.cmd.isEmpty()) {
                int exit = exec(op, p, block, "test", "all", "Tests");
                if (exit == Integer.MIN_VALUE) {
                    return;
                }
                if (exit == 0) {
                    state.finishOp(op, "SUCCEEDED", "Tests completed successfully.", null);
                } else {
                    state.finishOp(op, "FAILED", "Tests failed with exit code " + exit + ".",
                            lastErrorLine(op));
                }
                return;
            }
            state.finishOp(op, "FAILED", null, "No test configuration for this project.");
            return;
        }
        int suitesOk = 0;
        int suitesFailed = 0;
        int totalRun = 0;
        int totalFail = 0;
        int totalErr = 0;
        int totalSkip = 0;
        List<String> suiteResults = new ArrayList<>();
        for (Config.TestSuite s : p.testSuites) {
            if (op.cancelRequested) {
                state.finishOp(op, "CANCELLED", "Tests cancelled.",
                        "Ran " + suitesOk + " suites before cancellation.");
                return;
            }
            op.phase = "Tests";
            op.suite = s.id;
            op.task = s.label;
            op.testsRun = 0;
            op.testsFailed = 0;
            op.testsErrors = 0;
            op.testsSkipped = 0;
            op.finalTestsRun = -1;
            append(op, "\n=== Test suite: " + s.label + " (" + s.id + ") ===\n");
            Config.CommandBlock block = new Config.CommandBlock(s.cmd, s.env, "repo",
                    s.timeoutMs, 0, true);
            int exit = exec(op, p, block, "test", s.id, s.label);
            if (exit == Integer.MIN_VALUE) {
                return;
            }
            if (op.finalTestsRun >= 0) {
                op.testsRun = op.finalTestsRun;
                op.testsFailed = op.finalTestsFailed;
                op.testsErrors = op.finalTestsErrors;
                op.testsSkipped = op.finalTestsSkipped;
            }
            boolean ok = exit == 0;
            if (ok) {
                suitesOk++;
            } else {
                suitesFailed++;
            }
            if (op.testsRun >= 0) {
                totalRun += op.testsRun;
                totalFail += Math.max(op.testsFailed, 0);
                totalErr += Math.max(op.testsErrors, 0);
                totalSkip += Math.max(op.testsSkipped, 0);
            }
            suiteResults.add((ok ? "PASS" : "FAIL") + "  " + s.label + " (exit " + exit + ", "
                    + (op.testsRun >= 0 ? op.testsRun + " tests, " + op.testsFailed + " failures, "
                    + op.testsErrors + " errors, " + op.testsSkipped + " skipped" : "no surefire summary")
                    + ")");
            if (!ok && !p.testContinueOnFailure) {
                break;
            }
        }
        op.suite = "";
        String summary = "Test results: " + suitesOk + "/" + (suitesOk + suitesFailed)
                + " suites passed";
        if (totalRun > 0) {
            summary += ", " + totalRun + " tests, " + totalFail + " failures, " + totalErr
                    + " errors, " + totalSkip + " skipped";
        }
        if (suitesFailed > 0) {
            state.finishOp(op, "FAILED", summary, String.join("\n", suiteResults));
            state.notify("error", projectId, "Tests failed", summary,
                    "Open the operation log for the failing suite.");
        } else {
            state.finishOp(op, "SUCCEEDED", summary, null);
            state.notify("success", projectId, "Tests completed", summary, "");
        }
    }

    // ---------------------------------------------------------------- run op

    private void runRuntime(State.Op op, String projectId, String lcId, Integer port) {
        Config.Project p = Main.currentConfig.project(projectId);
        Config.LaunchConfig lc = p.launch(lcId);
        op.phase = "Process creation";
        op.task = "Starting " + (lc != null ? lc.name : lcId);
        String err;
        try {
            err = proc.start(p, lc, port, op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err = "Interrupted";
        }
        if (err == null) {
            state.finishOp(op, "SUCCEEDED", "Runtime started and reached READY ("
                    + (lc != null ? lc.name : lcId) + ").", null);
        } else if (op.cancelRequested) {
            state.finishOp(op, "CANCELLED", "Startup cancelled.", err);
        } else {
            state.finishOp(op, "FAILED", "Runtime start failed.", err);
        }
    }

    private void runStop(State.Op op, String projectId) {
        op.phase = "Shutdown";
        op.task = "Stopping " + projectId;
        State.ProjectState ps = state.stateFor(projectId);
        String err = ps.runtimeState.equals("EXTERNAL")
                ? proc.stopExternal(projectId)
                : proc.stop(projectId, null);
        if (err == null) {
            state.finishOp(op, "SUCCEEDED", "Stopped.", null);
        } else {
            state.finishOp(op, "FAILED", "Stop failed.", err);
        }
    }

    private void runRestart(State.Op op, String projectId, String lcId) {
        Config.Project p = Main.currentConfig.project(projectId);
        State.ProjectState ps = state.stateFor(projectId);
        Integer lastPort = ps.boundPort > 0 ? ps.boundPort : null;
        String lastLc = ps.launchConfig;
        op.phase = "Shutdown";
        op.task = "Stopping " + projectId + " (restart)";
        String err = proc.stop(projectId, null);
        if (err != null) {
            state.finishOp(op, "FAILED", "Restart failed at stop step.", err);
            return;
        }
        String lc = lcId != null && !lcId.isBlank() ? lcId
                : !lastLc.isBlank() ? lastLc
                : p.defaultLaunch() != null ? p.defaultLaunch().id : null;
        if (lc == null) {
            state.finishOp(op, "FAILED", "Restart failed: no launch configuration.", null);
            return;
        }
        Config.LaunchConfig cfg = p.launch(lc);
        op.phase = "Process creation";
        op.task = "Starting " + lc;
        if (lastPort != null && cfg != null && cfg.readiness != null
                && !"range".equals(cfg.readiness.resolve)) {
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline
                    && Probe.tcpOpen(cfg.readiness.host, lastPort, 600)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            err = proc.start(p, cfg, lastPort, op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err = "Interrupted";
        }
        if (err == null) {
            state.finishOp(op, "SUCCEEDED", "Restarted (" + lc + ", READY).", null);
        } else {
            state.finishOp(op, "FAILED", "Restart failed at start step.", err);
        }
    }

    // ------------------------------------------------------------ git ops

    private void runGit(State.Op op, String projectId, String action, String message) {
        Config.Project p = Main.currentConfig.project(projectId);
        op.phase = "Git";
        op.task = "git " + action + " — " + p.repoDir;
        if (!GitOps.installed()) {
            state.finishOp(op, "FAILED", null, "Git CLI not found. Install Git first.");
            return;
        }
        GitOps.Result r = switch (action) {
            case "fetch" -> GitOps.fetch(Path.of(p.repoDir));
            case "pull" -> GitOps.pull(Path.of(p.repoDir));
            case "push" -> GitOps.push(Path.of(p.repoDir));
            case "commit" -> GitOps.commit(Path.of(p.repoDir), message == null ? "Commit" : message);
            default -> new GitOps.Result(-1, "unknown", "");
        };
        op.phase = "Completion";
        if (r.ok()) {
            state.finishOp(op, "SUCCEEDED", "git " + action + " completed.", null);
            state.notify("success", projectId, "git " + action, "completed", "");
        } else {
            state.finishOp(op, "FAILED", "git " + action + " failed (rc " + r.rc() + ").",
                    r.error() + "\n" + r.output());
        }
    }

    // --------------------------------------------------------------- profiles

    /** Dependency-aware startup order for a profile's projects. */
    private List<String> orderedStartup(Config cfg, Config.Profile profile) {
        List<String> order = new ArrayList<>(profile.startupOrder);
        // Fill in selected projects missing from the explicit order, honoring
        // startupAfter dependencies via a simple topological pass.
        List<String> remaining = new ArrayList<>();
        for (String pid : profile.projects) {
            if (!order.contains(pid)) {
                remaining.add(pid);
            }
        }
        int guard = 0;
        while (!remaining.isEmpty() && guard++ < 100) {
            String placed = null;
            for (String pid : new ArrayList<>(remaining)) {
                Config.Project p = cfg.project(pid);
                boolean depsReady = true;
                if (p != null) {
                    for (String dep : p.startupAfter) {
                        if (remaining.contains(dep)) {
                            depsReady = false;
                            break;
                        }
                    }
                }
                if (depsReady) {
                    order.add(pid);
                    remaining.remove(pid);
                    placed = pid;
                    break;
                }
            }
            if (placed == null) {
                // cycle: append remaining as-is
                order.addAll(remaining);
                remaining.clear();
            }
        }
        return order;
    }

    private void profileLaunch(State.Op op, String profileId) {
        Config cfg = Main.currentConfig;
        Config.Profile profile = cfg.profile(profileId);
        if (profile == null) {
            state.finishOp(op, "FAILED", null, "Unknown profile: " + profileId);
            return;
        }
        List<String> order = orderedStartup(cfg, profile);
        int done = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        for (String id : order) {
            Config.Project p = cfg.project(id);
            State.ProjectState ps = state.stateFor(id);
            op.task = "Launching " + id + " (" + (order.indexOf(id) + 1) + "/" + order.size() + ")";
            if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
                results.add(id + ": already running");
                done++;
                continue;
            }
            Config.LaunchConfig lc = p.defaultLaunch();
            if (lc == null) {
                results.add(id + ": skipped (no launch configuration)");
                continue;
            }
            Integer port = ps.boundPort > 0 ? ps.boundPort : null;
            String err;
            try {
                err = proc.start(p, lc, port, op);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "Interrupted";
            }
            if (err == null) {
                done++;
                results.add(id + ": OK (" + lc.id + ")");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Profile " + profile.name + ": " + done + "/"
                    + order.size() + " ready.", null);
        } else {
            state.finishOp(op, "FAILED", "Profile " + profile.name + ": partial success — "
                    + done + " ready, " + failed + " failed.", String.join("\n", results));
        }
    }

    private void profileStop(State.Op op, String profileId) {
        Config cfg = Main.currentConfig;
        Config.Profile profile = cfg.profile(profileId);
        if (profile == null) {
            state.finishOp(op, "FAILED", null, "Unknown profile: " + profileId);
            return;
        }
        List<String> order = profile.stopOrder.isEmpty()
                ? new ArrayList<>(orderedStartup(cfg, profile))
                : new ArrayList<>(profile.stopOrder);
        if (profile.stopOrder.isEmpty()) {
            java.util.Collections.reverse(order);
        }
        int ok = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        for (String id : order) {
            State.ProjectState ps = state.stateFor(id);
            op.task = "Stopping " + id;
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                continue;
            }
            String err = ps.runtimeState.equals("EXTERNAL")
                    ? proc.stopExternal(id)
                    : proc.stop(id, null);
            if (err == null) {
                ok++;
                results.add(id + ": OK");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Profile " + profile.name + ": " + ok + " stopped.", null);
        } else {
            state.finishOp(op, "FAILED", "Profile " + profile.name + ": " + ok + " stopped, "
                    + failed + " failed.", String.join("\n", results));
        }
    }

    // ---------------------------------------------------------------- global

    private void launchAll(State.Op op) {
        Config cfg = Main.currentConfig;
        List<String> order = new ArrayList<>();
        for (Config.Project p : cfg.projects) {
            order.add(p.id);
        }
        // topological pass over startupAfter
        order.sort((a, b) -> Integer.compare(depRank(cfg, a, new LinkedHashMap<>()),
                depRank(cfg, b, new LinkedHashMap<>())));
        int done = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        for (String id : order) {
            Config.Project p = cfg.project(id);
            State.ProjectState ps = state.stateFor(id);
            op.task = "Launching " + id + " (" + (order.indexOf(id) + 1) + "/" + order.size() + ")";
            if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
                results.add(id + ": already running");
                done++;
                continue;
            }
            Config.LaunchConfig lc = p.defaultLaunch();
            if (lc == null) {
                results.add(id + ": skipped (no launch configuration)");
                continue;
            }
            Integer port = ps.boundPort > 0 ? ps.boundPort : null;
            String err;
            try {
                err = proc.start(p, lc, port, op);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "Interrupted";
            }
            if (err == null) {
                done++;
                results.add(id + ": OK (" + lc.id + ")");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Launch All: " + done + "/" + order.size() + " ready.", null);
        } else {
            state.finishOp(op, "FAILED", "Launch All: partial success — " + done + " ready, "
                    + failed + " failed.", String.join("\n", results));
        }
    }

    private int depRank(Config cfg, String id, Map<String, Integer> memo) {
        if (memo.containsKey(id)) {
            return memo.get(id);
        }
        memo.put(id, 0);
        Config.Project p = cfg.project(id);
        int rank = 0;
        if (p != null) {
            for (String dep : p.startupAfter) {
                rank = Math.max(rank, 1 + depRank(cfg, dep, memo));
            }
        }
        memo.put(id, rank);
        return rank;
    }

    private void stopAll(State.Op op) {
        List<String> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        Config cfg = Main.currentConfig;
        for (Config.Project p : cfg.projects) {
            State.ProjectState ps = state.stateFor(p.id);
            op.task = "Stopping " + p.id;
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                continue;
            }
            String err = ps.runtimeState.equals("EXTERNAL")
                    ? proc.stopExternal(p.id)
                    : proc.stop(p.id, null);
            if (err == null) {
                ok++;
                results.add(p.id + ": OK");
            } else {
                failed++;
                results.add(p.id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Stop All: " + ok + " stopped.", null);
        } else {
            state.finishOp(op, "FAILED", "Stop All: " + ok + " stopped, " + failed + " failed.",
                    String.join("\n", results));
        }
    }

    private void restartAll(State.Op op) {
        stopAllPhase(op);
        if (!op.status.equals("RUNNING")) {
            state.finishOp(op, "FAILED", "Restart All aborted: some projects could not be stopped.",
                    op.error);
            return;
        }
        launchAllPhase(op);
        if (op.status.equals("RUNNING")) {
            state.finishOp(op, "SUCCEEDED", "Restart All: all projects stopped and relaunched.", null);
        }
    }

    private void stopAllPhase(State.Op op) {
        List<String> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (Config.Project p : Main.currentConfig.projects) {
            State.ProjectState ps = state.stateFor(p.id);
            op.task = "Stopping " + p.id;
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                continue;
            }
            String err = ps.runtimeState.equals("EXTERNAL")
                    ? proc.stopExternal(p.id)
                    : proc.stop(p.id, null);
            if (err == null) {
                ok++;
                results.add(p.id + ": OK");
            } else {
                failed++;
                results.add(p.id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            op.task = "All stopped (" + ok + ")";
        } else {
            op.error = String.join("\n", results);
            op.status = "FAILED";
        }
    }

    private void launchAllPhase(State.Op op) {
        int done = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (Config.Project p : Main.currentConfig.projects) {
            order.add(p.id);
        }
        for (String id : order) {
            Config.Project p = Main.currentConfig.project(id);
            State.ProjectState ps = state.stateFor(id);
            op.task = "Launching " + id + " (" + (order.indexOf(id) + 1) + "/" + order.size() + ")";
            if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
                results.add(id + ": already running");
                done++;
                continue;
            }
            Config.LaunchConfig lc = p.defaultLaunch();
            if (lc == null) {
                results.add(id + ": skipped (no launch configuration)");
                continue;
            }
            Integer port = ps.boundPort > 0 ? ps.boundPort : null;
            String err;
            try {
                err = proc.start(p, lc, port, op);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "Interrupted";
            }
            if (err == null) {
                done++;
                results.add(id + ": OK (" + lc.id + ")");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        op.phase = "Completion";
        if (failed == 0) {
            op.task = "All launched: " + done + "/" + order.size() + " ready";
        } else {
            op.status = "FAILED";
            op.error = String.join("\n", results);
        }
    }

    private String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    // ------------------------------------------------------------- exec core

    private int exec(State.Op op, Config.Project p, Config.CommandBlock block,
            String kind, String suiteId, String suiteLabel) {
        List<String> cmd = block.cmd;
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path wd = Path.of(p.repoDir);
        if (block.workDir != null && !block.workDir.equals("repo")) {
            Path w = Path.of(App.expandHome(block.workDir));
            if (!w.isAbsolute()) {
                w = Path.of(p.repoDir, block.workDir);
            }
            wd = w;
        }
        pb.directory(wd.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> envExpanded = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : block.env.entrySet()) {
            String v = e.getValue();
            v = v.replace("{java21}", String.valueOf(Adapters.detectTools().getOrDefault("java21", "")));
            v = v.replace("{java25}", String.valueOf(Adapters.detectTools().getOrDefault("java25", "")));
            v = v.replace("{repo}", p.repoDir).replace("{app}", app.appDir.toString());
            envExpanded.put(e.getKey(), v);
        }
        String javaBin = System.getProperty("java.home") + "/bin";
        if (envExpanded.containsKey("JAVA_HOME") && !envExpanded.get("JAVA_HOME").isBlank()) {
            javaBin = Path.of(envExpanded.get("JAVA_HOME"), "bin").toString();
        }
        String path = System.getenv("PATH");
        pb.environment().put("PATH", javaBin + (path == null ? "" : ":" + path));
        pb.environment().putAll(envExpanded);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            state.finishOp(op, "FAILED", null, "Failed to start command: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
        Path opLog = app.opsDir.resolve(op.id + ".log");
        op.phase = "Initialization";
        op.task = String.join(" ", cmd);
        try {
            Files.createDirectories(opLog.getParent());
        } catch (IOException ignored) {
            // best effort
        }
        long deadline = System.currentTimeMillis() + block.timeoutMs;
        List<String> modules = new ArrayList<>();
        boolean sawReactor = false;
        java.io.BufferedWriter logWriter = null;
        try {
            logWriter = Files.newBufferedWriter(opLog, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // best effort
        }
        final java.io.BufferedWriter writer = logWriter;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                append(op, line + "\n");
                if (writer != null) {
                    try {
                        writer.write(line);
                        writer.write('\n');
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
                parseLine(op, line, kind, block.expectedModules);
                if (line.contains("Reactor Build Order:")) {
                    sawReactor = true;
                }
                if (sawReactor && line.trim().startsWith("[INFO] ")) {
                    String t = line.trim().substring(7);
                    if (t.matches("\\S+\\s+.*")) {
                        String mod = t.split("\\s+")[0];
                        if (!modules.contains(mod) && !mod.contains("---")) {
                            modules.add(mod);
                        }
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    destroyTree(process);
                    closeWriter(writer);
                    String msg = "Operation timed out after " + (block.timeoutMs / 60000) + " min";
                    state.finishOp(op, "FAILED", null, msg);
                    state.notify("error", op.projectId, kind + " timed out", msg,
                            "Check " + opLog + " for the point where it stalled.");
                    return Integer.MIN_VALUE;
                }
                if (op.cancelRequested) {
                    destroyTree(process);
                    closeWriter(writer);
                    state.finishOp(op, "CANCELLED", kind + " cancelled by user.", null);
                    return Integer.MIN_VALUE;
                }
            }
        } catch (IOException e) {
            closeWriter(writer);
            state.finishOp(op, "FAILED", null, "Reading process output failed: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            closeWriter(writer);
            state.finishOp(op, "CANCELLED", "Interrupted.", null);
            return Integer.MIN_VALUE;
        }
        closeWriter(writer);
        op.totalModules = op.totalModules > 0 ? op.totalModules
                : (modules.size() > 0 ? modules.size() : (block.expectedModules > 0 ? block.expectedModules : 0));
        if (op.totalModules > 0) {
            op.completedModules = exit == 0 ? op.totalModules : Math.max(op.completedModules, 0);
            op.progressKnown = true;
        }
        op.phase = "Completion";
        return exit;
    }

    private static void closeWriter(java.io.BufferedWriter w) {
        if (w == null) {
            return;
        }
        try {
            w.flush();
            w.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void parseLine(State.Op op, String line, String kind, int expectedModules) {
        if (line.contains("Scanning for projects") || line.contains("Scanning for modules")) {
            op.phase = "Initialization";
            op.task = "Scanning modules";
        } else if (line.contains("maven-clean-plugin") || line.contains("clean:clean")) {
            op.phase = "Initialization";
            op.task = "Clean";
        } else if (line.contains("Downloading") || line.contains("Downloaded")
                || line.contains("p2.index")) {
            if (!op.phase.equals("Tests")) {
                op.phase = "Dependency resolution";
                op.task = "Resolving dependencies";
            }
        } else if (line.contains("maven-compiler-plugin") || line.contains("tycho-compiler")
                || line.contains("Compiling")) {
            op.phase = "Compilation";
            op.task = "Compiling";
        } else if (line.contains("testCompile")) {
            op.phase = "Test compilation";
            op.task = "Compiling tests";
        } else if (line.contains("surefire") || line.contains("tycho-surefire")) {
            op.phase = "Tests";
            op.task = "Running tests";
        } else if (line.contains("tycho-p2-publisher") || line.contains("maven-package-plugin")) {
            op.phase = "Packaging";
            op.task = "Packaging";
        } else if (line.contains("tycho-p2-director") || line.contains("materialize-products")) {
            op.phase = "Product assembly";
            op.task = "Assembling product";
        } else if (line.contains("Reactor Summary") || line.contains("BUILD SUCCESS")
                || line.contains("BUILD FAILURE")) {
            op.phase = "Completion";
            op.task = "Finishing";
        }
        Matcher m = BUILDING.matcher(line);
        if (m.find()) {
            String mod = m.group(1);
            if (mod.contains(":")) {
                return;
            }
            op.currentModule = mod;
            op.task = "Building " + mod;
            if (m.group(2) != null && m.group(3) != null) {
                op.completedModules = Integer.parseInt(m.group(2)) - 1;
                op.totalModules = Integer.parseInt(m.group(3));
                op.progressKnown = true;
            } else {
                op.completedModules = Math.max(op.completedModules, 0);
                if (op.totalModules == 0) {
                    op.totalModules = expectedModules;
                }
                if (op.totalModules > 0) {
                    op.progressKnown = true;
                }
            }
        }
        Matcher t = TESTS_RUN.matcher(line);
        if (t.find()) {
            if (line.contains("-- in ")) {
                op.testsRun += Integer.parseInt(t.group(1));
                op.testsFailed += Integer.parseInt(t.group(2));
                op.testsErrors += Integer.parseInt(t.group(3));
                op.testsSkipped += Integer.parseInt(t.group(4));
            } else {
                op.finalTestsRun = Integer.parseInt(t.group(1));
                op.finalTestsFailed = Integer.parseInt(t.group(2));
                op.finalTestsErrors = Integer.parseInt(t.group(3));
                op.finalTestsSkipped = Integer.parseInt(t.group(4));
            }
            op.phase = "Tests";
            op.task = op.suite.isEmpty() ? "Tests" : op.suite + ": tests";
        }
    }

    private void destroyTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            Thread.sleep(1500);
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void append(State.Op op, String text) {
        synchronized (op.live) {
            op.live.append(text);
            if (op.live.length() > MAX_LIVE_BUFFER) {
                op.live.delete(0, op.live.length() - MAX_LIVE_BUFFER);
            }
        }
    }

    private String lastErrorLine(State.Op op) {
        String log = op.logTail(4000);
        String[] lines = log.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i];
            if (l.contains("[ERROR]") || l.contains("BUILD FAILURE") || l.contains("FAILURE:")) {
                return l.trim() + "\n" + (i + 1 < lines.length ? lines[i + 1].trim() : "");
            }
        }
        return lines.length > 0 ? lines[lines.length - 1].trim() : "Unknown error.";
    }

    public boolean cancel(long opId) {
        State.Op op;
        synchronized (state.ops) {
            op = state.ops.get(opId);
        }
        if (op == null || !op.status.equals("RUNNING")) {
            return false;
        }
        if (op.type.equals("run")) {
            op.cancelRequested = true;
            State.ProjectState ps = state.projects.get(op.projectId);
            if (ps != null && ps.runtimeState.equals("STARTING")) {
                proc.stop(op.projectId, null);
            }
            return true;
        }
        op.cancelRequested = true;
        return true;
    }
}
