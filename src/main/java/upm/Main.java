package upm;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Universal Project Manager entry point. Starts the embedded HTTP server,
 * detects an already-running instance, opens the browser and watches managed
 * runtime processes.
 */
public final class Main {

    public static volatile int currentPort = 0;
    public static volatile long selfPid = 0;
    public static volatile Config currentConfig;
    private static volatile State stateRef;
    private static volatile Metrics metricsRef;

    public static State getState() {
        return stateRef;
    }

    public static Metrics getMetrics() {
        return metricsRef;
    }

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");

    public static void main(String[] args) throws Exception {
        selfPid = ProcessHandle.current().pid();
        Path appDir = App.locateAppDir();
        String configPath = args.length > 0 ? args[0] : "config/application.jsonc";
        App app = App.load(appDir, configPath);

        // Single-instance detection: if another UPM instance already answers
        // on one of the configured ports, reuse it instead of starting a new one.
        String existing = findExisting(app);
        if (existing != null) {
            log("INFO", "Universal Project Manager already running at " + existing);
            if (app.openBrowser) {
                openBrowser(existing, app);
            }
            return;
        }

        Proc.selfPgid = readOwnPgid();
        Files.createDirectories(app.runtimeDir);
        Files.createDirectories(app.opsDir);
        Files.createDirectories(app.workspacesDir);
        Files.createDirectories(app.logsDir);

        WsStore store = new WsStore(app);
        if (!Files.exists(store.workspaceFile(app.defaultWorkspace))) {
            // First run: create the default workspace.
            Config c = store.create(app.defaultWorkspace, "Default Workspace", "");
            c.dashboard = Dash.defaultLayoutMap();
            c.save();
        }
        currentConfig = store.load(app.defaultWorkspace);
        reportLoadIssues(currentConfig);

        State state = new State(app);
        state.restore();
        stateRef = state;
        Proc proc = new Proc(app, state);
        Ops ops = new Ops(app, state, proc);
        Metrics metrics = new Metrics();
        metrics.configure(app.monitorRefreshMs, 3600);
        metrics.startSampling();
        metricsRef = metrics;
        EnrProxy enr = new EnrProxy(state);
        Diag diag = new Diag(state, proc);
        Dash dash = new Dash(app);

        reconcile(state, proc);

        Server server = new Server(app, state, ops, proc, diag, enr, dash, metrics, store);
        server.start();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log("INFO", "Universal Project Manager shutting down");
            server.stop();
        }));

        Thread watchdog = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (currentConfig == null) {
                    continue;
                }
                for (Config.Project p : currentConfig.projects) {
                    String crash = proc.watch(p.id);
                    if (crash != null) {
                        log("WARN", crash);
                    }
                }
            }
        }, "upm-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        if (app.openBrowser) {
            openBrowser("http://" + app.host + ":" + currentPort + "/", app);
        }
    }

    /** Probes configured ports for a live UPM instance. */
    private static String findExisting(App app) {
        for (int i = 0; i <= app.portFallbackRange; i++) {
            int port = app.port + i;
            String url = "http://" + app.host + ":" + port + "/api/health";
            String body = Probe.httpGet(url, 700);
            if (body != null && body.contains("universal-project-manager")) {
                return "http://" + app.host + ":" + port + "/";
            }
        }
        return null;
    }

    private static void openBrowser(String url, App app) {
        String cmd = app.browserCommand != null && !app.browserCommand.isBlank()
                ? app.browserCommand : "xdg-open";
        try {
            new ProcessBuilder(cmd, url).start();
        } catch (IOException e) {
            log("WARN", "Could not open browser: " + e.getMessage()
                    + " — open " + url + " manually.");
        }
    }

    /** Switches the active workspace (used by the API). */
    public static synchronized void switchWorkspace(String workspaceId) throws IOException {
        WsStore store = new WsStore(App.load(App.locateAppDir(), "config/application.jsonc"));
        Config c = store.load(workspaceId);
        currentConfig = c;
        reportLoadIssues(c);
        log("INFO", "Switched to workspace '" + workspaceId + "' (" + c.projects.size()
                + " projects)");
    }

    private static void reportLoadIssues(Config c) {
        for (Config.Project p : c.projects) {
            if (p.repoDir == null || !Files.isDirectory(Path.of(p.repoDir))) {
                log("WARN", "Project " + p.id + ": repository not found: " + p.repoDir
                        + " (reported, not replaced)");
            }
        }
        List<String> errors = c.validate();
        for (String e : errors) {
            if (!e.contains("repository directory not found")) {
                log("WARN", "Workspace validation: " + e);
            }
        }
    }

    /** After daemon restart: verify pid files against live processes. */
    private static void reconcile(State state, Proc proc) {
        for (Config.Project p : currentConfig.projects) {
            State.ProjectState ps = state.projects.get(p.id);
            if (ps == null) {
                continue;
            }
            if (ps.pid <= 0) {
                if (!ps.runtimeState.equals("STOPPED") && !ps.runtimeState.equals("FAILED")
                        && !ps.runtimeState.equals("EXTERNAL")) {
                    ps.runtimeState = "STOPPED";
                }
                continue;
            }
            String lcId = ps.launchConfig.isBlank() ? "run" : ps.launchConfig;
            if (!Proc.isAlive(ps.pid)) {
                long deadPid = ps.pid;
                ps.pid = -1;
                if (proc.gracefulExit(p.id, lcId)) {
                    ps.runtimeState = "STOPPED";
                    ps.startedAt = -1;
                    ps.lastError = "";
                    log("INFO", p.id + ": process " + deadPid
                            + " exited gracefully while the control center was away");
                } else {
                    ps.runtimeState = "FAILED";
                    ps.lastError = "Process " + deadPid
                            + " was not running when the control center restarted.";
                }
                try {
                    Files.deleteIfExists(proc.pidFile(p.id, lcId));
                } catch (IOException ignored) {
                    // best effort
                }
                continue;
            }
            if (!proc.verifyOwnership(p.id, lcId, ps.pid)) {
                ps.runtimeState = "UNKNOWN";
                ps.lastError = "Pid file ownership verification failed after restart.";
                continue;
            }
            Config.LaunchConfig lc = p.launch(lcId);
            int port = lc != null ? lc.port : 0;
            if (Probe.probeReady(p, lc, port)) {
                ps.runtimeState = "READY";
                if (ps.readyAt <= 0) {
                    ps.readyAt = System.currentTimeMillis();
                }
            } else {
                ps.runtimeState = "RUNNING";
                log("INFO", p.id + ": process " + ps.pid
                        + " alive but not ready yet (readiness probe pending)");
            }
        }
    }

    /** Serves web resources from the jar, with a filesystem fallback for dev. */
    public static byte[] webResource(String path) {
        String p = path.startsWith("/") ? path.substring(1) : path;
        try (InputStream in = Main.class.getResourceAsStream("/web/" + p)) {
            if (in != null) {
                return in.readAllBytes();
            }
        } catch (IOException ignored) {
            // fall through
        }
        // Dev fallback: sibling web/ directory.
        try {
            Path f = App.locateAppDir().resolve("web").resolve(p);
            if (Files.isRegularFile(f)) {
                return Files.readAllBytes(f);
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    private static long readOwnPgid() {
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(selfPid), "stat"));
            int close = stat.lastIndexOf(')');
            String[] fields = stat.substring(close + 2).split(" ");
            return fields.length > 2 ? Long.parseLong(fields[2]) : selfPid;
        } catch (IOException e) {
            return selfPid;
        }
    }

    public static void log(String level, String msg) {
        System.out.println("[" + LocalTime.now().format(TS) + "] [" + level + "] " + msg);
    }
}
