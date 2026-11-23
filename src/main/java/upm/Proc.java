package upm;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project runtime lifecycle: start, stop, restart, readiness, crash
 * detection, PID files, ownership verification, graceful/forced stop.
 * PID files and logs are isolated per project and launch configuration.
 */
public final class Proc {

    /** Own process group (set by Main); never signal this group. */
    static volatile long selfPgid = -1;

    private final App app;
    private final State state;

    public Proc(App app, State state) {
        this.app = app;
        this.state = state;
    }

    public Path runDir(String projectId, String launchConfigId) {
        Path d = app.runtimeDir.resolve(projectId).resolve(launchConfigId);
        try {
            Files.createDirectories(d);
        } catch (IOException ignored) {
            // best effort
        }
        return d;
    }

    public Path pidFile(String projectId, String launchConfigId) {
        return runDir(projectId, launchConfigId).resolve("pid");
    }

    public Path runtimeLog(String projectId, String launchConfigId) {
        return runDir(projectId, launchConfigId).resolve("runtime.log");
    }

    public Path crashMarker(String projectId, String launchConfigId) {
        return runDir(projectId, launchConfigId).resolve("crashed");
    }

    public Path exitCodeFile(String projectId, String launchConfigId) {
        return runDir(projectId, launchConfigId).resolve("exit-code");
    }

    public static String runtimeLogKey(String projectId, String launchConfigId) {
        return projectId + "/" + launchConfigId;
    }

    // ---------------------------------------------------------------- start

    /** Starts a launch configuration. Returns error message or null on success. */
    public synchronized String start(Config.Project p, Config.LaunchConfig lc,
            Integer portOverride, State.Op op) throws InterruptedException {
        State.ProjectState ps = state.stateFor(p.id);
        if (lc == null) {
            return "Project " + p.id + " has no launch configurations.";
        }
        if (lc.requiresDisplay && (System.getenv("DISPLAY") == null
                || System.getenv("DISPLAY").isBlank())) {
            return "Launch configuration '" + lc.name + "' requires a graphical display, "
                    + "but DISPLAY is not set.";
        }
        if (ps.runtimeState.equals("STARTING") || ps.runtimeState.equals("RUNNING")
                || ps.runtimeState.equals("READY") || ps.runtimeState.equals("STOPPING")) {
            return "Project " + p.id + " is already " + ps.runtimeState
                    + " (launch config '" + ps.launchConfig + "', pid "
                    + (ps.pid > 0 ? ps.pid : "?") + "). Stop it first.";
        }
        if (isPidFileAlive(p.id, lc.id)) {
            long pid = readPidFilePid(p.id, lc.id);
            if (verifyOwnership(p.id, lc.id, pid)) {
                ps.runtimeState = "RUNNING";
                ps.pid = pid;
                ps.launchConfig = lc.id;
                return "Project " + p.id + " is already running as pid " + pid
                        + " (recovered from pid file).";
            }
        }
        // External pre-existing process detection (owned by user, matches repo path).
        String external = findExternal(p);
        if (external != null && !external.isBlank()) {
            ps.externalDetail = external;
            boolean conflict = false;
            int expected = requestedPort(p, lc, portOverride);
            if (lc.readiness != null && expected > 0
                    && Probe.tcpOpen(lc.readiness.host, expected, 800)) {
                String owner = Probe.ownerOf(expected);
                conflict = owner != null && owner.contains(p.repoDir);
            }
            if (conflict) {
                ps.runtimeState = "EXTERNAL";
                long extStart = earliestExternalStart(findExternalPids(p));
                if (extStart > 0) {
                    ps.startedAt = extStart;
                }
                return "A process that belongs to " + p.id
                        + " is already running the requested service on the configured port: "
                        + external + ". Stop it explicitly with the external flag if needed.";
            }
        }

        Path launcher = resolveLauncher(p, lc.launcher);
        boolean jarKind = "jar".equals(lc.kind);
        if (jarKind) {
            if (!Files.isRegularFile(launcher)) {
                return "JAR launcher not found: " + launcher + ". Run a build first.";
            }
        } else if (!Files.isExecutable(launcher)) {
            return "Launcher not found or not executable: " + launcher
                    + ". Run a build first.";
        }

        List<String> resolvedArgs = new ArrayList<>();
        String classpath = null;
        for (String a : lc.args) {
            String ra = expandTemplates(a, p, launcher);
            if (ra.contains("{enrCp}")) {
                if (classpath == null) {
                    classpath = enrClasspath(p);
                    if (classpath == null) {
                        return "Could not build the ENR classpath (mvn dependency:build-classpath failed).";
                    }
                }
                ra = ra.replace("{enrCp}", classpath);
            }
            resolvedArgs.add(ra);
        }

        int port = requestedPort(p, lc, portOverride);
        // Deterministic-port servers: fail fast when the requested port is occupied.
        boolean deterministic = lc.portFixed
                && lc.readiness != null && !"range".equals(lc.readiness.resolve);
        if (port > 0 && deterministic && Probe.tcpOpen(lc.readiness.host, port, 800)) {
            String owner = Probe.ownerOf(port);
            boolean ownerless = owner == null || owner.isBlank();
            boolean sameProject = owner != null && owner.contains(p.repoDir);
            if (!ownerless) {
                ps.lastError = "Port " + port + " is already in use"
                        + (sameProject ? " by a process of " + p.id : "")
                        + " (owner: " + owner + "). These ports are deterministic.";
                state.notify("error", p.id, "Port conflict", ps.lastError,
                        sameProject
                                ? "Stop the existing instance first."
                                : "Use another launch configuration or a free explicit port.");
                return ps.lastError;
            }
            long deadline = System.currentTimeMillis() + 15000;
            while (System.currentTimeMillis() < deadline
                    && Probe.tcpOpen(lc.readiness.host, port, 600)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (Probe.tcpOpen(lc.readiness.host, port, 600)) {
                ps.lastError = "Port " + port + " is still occupied after a grace wait.";
                state.notify("error", p.id, "Port conflict", ps.lastError,
                        "Use another launch configuration or a free explicit port.");
                return ps.lastError;
            }
        }

        List<String> args = new ArrayList<>(resolvedArgs.size());
        for (String a : resolvedArgs) {
            if (a.contains("{port}")) {
                args.add(a.replace("{port}", String.valueOf(port)));
            } else {
                args.add(a);
            }
        }

        Path log = runtimeLog(p.id, lc.id);
        List<String> cmdline = new ArrayList<>();
        cmdline.add("setsid");
        if (jarKind) {
            cmdline.add(javaForLaunch(lc));
            cmdline.add("-jar");
        }
        cmdline.add(launcher.toString());
        cmdline.addAll(args);

        ProcessBuilder pb = new ProcessBuilder(cmdline);
        pb.directory(workDir(p, lc).toFile());
        pb.redirectErrorStream(true);
        pb.redirectOutput(log.toFile());
        Map<String, String> envExpanded = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : lc.env.entrySet()) {
            envExpanded.put(e.getKey(), expandTemplates(e.getValue(), p, launcher));
        }
        String javaBin = envExpanded.containsKey("JAVA_HOME")
                ? Path.of(envExpanded.get("JAVA_HOME"), "bin").toString()
                : envJavaBin(envExpanded);
        String path = System.getenv("PATH");
        pb.environment().put("PATH", javaBin + (path == null ? "" : ":" + path));
        pb.environment().putAll(envExpanded);

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            ps.lastError = "Failed to start: " + e.getMessage()
                    + " [cmd: " + String.join(" ", cmdline) + "]";
            return "Failed to start process: " + e.getMessage();
        }

        // Record the real exit code when the process terminates so the watchdog
        // can distinguish a graceful shutdown from a crash.
        Path exitCodeFile = exitCodeFile(p.id, lc.id);
        try {
            Files.deleteIfExists(exitCodeFile);
        } catch (IOException ignored) {
            // best effort
        }
        proc.onExit().thenAccept(ph -> {
            try {
                Files.writeString(exitCodeFile, String.valueOf(ph.exitValue()));
            } catch (IOException e) {
                Main.log("WARN", "Could not record exit code for " + p.id + ": "
                        + e.getMessage());
            }
        });

        long pid = proc.pid();
        // Poll the process group until setsid() took effect; never record the
        // UPM process's own group.
        long pgid = -1;
        for (int i = 0; i < 25; i++) {
            long g = readPgid(pid);
            if (g > 0 && g != selfPgid && g != Main.selfPid) {
                pgid = g;
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (pgid <= 0) {
            pgid = pid;
        }
        writePidFile(p.id, lc.id, pid, pgid);

        ps.runtimeState = "STARTING";
        ps.pid = pid;
        ps.startedAt = System.currentTimeMillis();
        ps.readyAt = -1;
        ps.launchConfig = lc.id;
        ps.lastError = "";
        state.notify("info", p.id, "Starting " + lc.name,
                "pid " + pid + ", log: " + log, "Watch the runtime state until READY.");

        long deadline = System.currentTimeMillis() + lc.startupTimeoutMs;
        Pattern marker = buildMarker(lc);
        long markerPos = 0;
        boolean ready = false;
        while (System.currentTimeMillis() < deadline) {
            if (op != null && op.cancelRequested) {
                stop(p.id, lc.id);
                return "Startup cancelled.";
            }
            if (!proc.isAlive()) {
                int code = proc.exitValue();
                ps.runtimeState = "FAILED";
                ps.exitCode = code;
                String tail = tailLog(log, 1200);
                ps.lastError = "Process exited during startup with code " + code
                        + " [cmd: " + String.join(" ", cmdline) + "]";
                try {
                    Files.deleteIfExists(pidFile(p.id, lc.id));
                } catch (IOException ignored) {
                    // best effort
                }
                state.notify("error", p.id, "Runtime crashed during startup",
                        "exit code " + code, "See runtime log: " + log);
                return "Process exited during startup (code " + code + ").\n" + tail;
            }
            if (Probe.probeReady(p, lc, port)) {
                ready = true;
                break;
            }
            if (marker != null) {
                String logText = readLogFrom(log, markerPos);
                markerPos += logText.length();
                if (marker.matcher(logText).find()) {
                    ready = true;
                    break;
                }
            }
            Thread.sleep(1500);
        }

        if (!ready) {
            stop(p.id, lc.id);
            String tail = tailLog(log, 1200);
            ps.lastError = "Startup timeout after " + (lc.startupTimeoutMs / 1000) + "s";
            state.notify("error", p.id, "Startup timeout",
                    "Not ready within " + (lc.startupTimeoutMs / 1000) + "s",
                    "See runtime log: " + log);
            return "Startup timeout: the runtime did not become ready within "
                    + (lc.startupTimeoutMs / 1000) + " seconds.\n" + tail;
        }

        try {
            Files.deleteIfExists(crashMarker(p.id, lc.id));
        } catch (IOException ignored) {
            // best effort
        }
        ps.runtimeState = "READY";
        ps.readyAt = System.currentTimeMillis();
        ps.boundPort = port > 0 ? port : Probe.resolvePort(p, lc, port);
        int boundPort = ps.boundPort;
        state.notify("success", p.id, "Runtime ready",
                "pid " + pid + (boundPort > 0 ? ", port " + boundPort : ""), "");
        return null;
    }

    private int requestedPort(Config.Project p, Config.LaunchConfig lc, Integer portOverride) {
        if (portOverride != null && portOverride > 0) {
            return portOverride;
        }
        if (lc.port > 0) {
            return lc.port;
        }
        return Probe.resolvePort(p, lc, 0);
    }

    private Path workDir(Config.Project p, Config.LaunchConfig lc) {
        if (lc.workDir == null || lc.workDir.equals("repo")) {
            return Path.of(p.repoDir);
        }
        Path w = Path.of(App.expandHome(lc.workDir));
        if (!w.isAbsolute() && p.repoDir != null) {
            return Path.of(p.repoDir, lc.workDir);
        }
        return w;
    }

    private String envJavaBin(Map<String, String> env) {
        for (Map.Entry<String, String> e : env.entrySet()) {
            if (e.getKey().equals("JAVA_HOME") && e.getValue() != null) {
                return Path.of(e.getValue(), "bin").toString();
            }
        }
        return System.getProperty("java.home") + "/bin";
    }

    private Path resolveLauncher(Config.Project p, String launcher) {
        String l = App.expandHome(expandTemplates(launcher, p, null));
        Path lp = Path.of(l);
        if (!lp.isAbsolute()) {
            lp = Path.of(p.repoDir, l);
        }
        return lp;
    }

    private String expandTemplates(String s, Config.Project p, Path launcher) {
        String r = s;
        r = r.replace("{repo}", p.repoDir);
        r = r.replace("{app}", app.appDir.toString());
        r = r.replace("{java21}", String.valueOf(Adapters.detectTools().getOrDefault("java21", "java")));
        r = r.replace("{java25}", String.valueOf(Adapters.detectTools().getOrDefault("java25", "java")));
        return r;
    }

    /** Resolves the java binary used to execute a JAR launch configuration. */
    private String javaForLaunch(Config.LaunchConfig lc) {
        String home = lc.env == null ? null : lc.env.get("JAVA_HOME");
        if (home != null && !home.isBlank()) {
            home = home.replace("{java21}",
                    String.valueOf(Adapters.detectTools().getOrDefault("java21", "")));
            home = home.replace("{java25}",
                    String.valueOf(Adapters.detectTools().getOrDefault("java25", "")));
            if (home.isBlank() || home.startsWith("{")) {
                home = null;
            }
        }
        if (home == null || home.isBlank()) {
            home = System.getProperty("java.home");
        }
        return Path.of(home, "bin", "java").toString();
    }

    private Pattern buildMarker(Config.LaunchConfig lc) {
        if (lc.readyMarkers.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lc.readyMarkers.size(); i++) {
            if (i > 0) {
                sb.append('|');
            }
            sb.append("(?:").append(Pattern.quote(lc.readyMarkers.get(i))).append(')');
        }
        return Pattern.compile(sb.toString());
    }

    private String enrClasspath(Config.Project p) {
        Path cpFile = runDir(p.id, "run").resolve("enr-classpath.txt");
        try {
            if (Files.exists(cpFile) && Files.size(cpFile) > 0
                    && System.currentTimeMillis() - Files.getLastModifiedTime(cpFile).toMillis()
                    < 6 * 60 * 60 * 1000L) {
                String cached = Files.readString(cpFile).strip();
                if (!cached.isEmpty()) {
                    return cached;
                }
            }
            List<String> cmd = new ArrayList<>(List.of("mvn", "-q", "-pl", "enr-cli",
                    "dependency:build-classpath", "-Dmdep.outputFile=" + cpFile,
                    "-DincludeScope=runtime"));
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(p.repoDir).toFile());
            String java25 = String.valueOf(Adapters.detectTools().getOrDefault("java25", ""));
            if (!java25.isEmpty()) {
                pb.environment().put("JAVA_HOME", java25);
            }
            pb.redirectErrorStream(true);
            Process proc = pb.start();
            String out = new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                Main.log("ERROR", "ENR classpath generation timed out");
                return null;
            }
            if (proc.exitValue() != 0 || !Files.exists(cpFile) || Files.size(cpFile) == 0) {
                Main.log("ERROR", "ENR classpath generation failed: " + out);
                return null;
            }
            return Files.readString(cpFile).strip();
        } catch (Exception e) {
            Main.log("ERROR", "ENR classpath generation failed: " + e.getMessage());
            return null;
        }
    }

    // ----------------------------------------------------------------- stop

    private void killGroup(long pgid, String signal) {
        if (pgid <= 0) {
            return;
        }
        if (selfPgid > 0 && pgid == selfPgid) {
            Main.log("ERROR", "Refusing to signal process group " + pgid
                    + ": it is UPM's own group.");
            return;
        }
        try {
            Process k = new ProcessBuilder("kill", "-" + signal, "--", "-" + pgid).start();
            boolean done = k.waitFor(5, TimeUnit.SECONDS);
            Main.log("INFO", "killGroup: " + signal + " to -" + pgid + " rc="
                    + (done ? String.valueOf(k.exitValue()) : "TIMEOUT"));
        } catch (Exception e) {
            Main.log("WARN", "killGroup: " + signal + " to -" + pgid + " failed: " + e.getMessage());
        }
    }

    /** Stops a project runtime. Returns null on success. */
    public synchronized String stop(String projectId, String launchConfigId) {
        State.ProjectState ps = state.projects.get(projectId);
        if (ps == null) {
            return "Unknown project: " + projectId;
        }
        String lcId = launchConfigId != null && !launchConfigId.isBlank()
                ? launchConfigId : ps.launchConfig;
        if (lcId.isBlank()) {
            lcId = "run";
        }
        long[] ids = readPidFile(projectId, lcId);
        long pid = ids[0];
        long pgid = ids[1];
        boolean fromFile = pid > 0;
        if (!fromFile && ps.pid > 0 && isAlive(ps.pid)) {
            pid = ps.pid;
            pgid = readPgid(pid);
        }
        if (pid <= 0) {
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                ps.runtimeState = "STOPPED";
                return null;
            }
            return "Project " + projectId + " is not running (no pid file).";
        }
        if (fromFile && !verifyOwnership(projectId, lcId, pid)) {
            return "Refusing to stop pid " + pid + ": the process does not match project "
                    + projectId + " ownership records. Investigate manually.";
        }
        long livePgid = readPgid(pid);
        if (livePgid > 0 && livePgid != selfPgid && livePgid != Main.selfPid) {
            pgid = livePgid;
        }
        Config.Project p = Main.currentConfig != null ? Main.currentConfig.project(projectId) : null;
        Config.LaunchConfig lc = p != null ? p.launch(lcId) : null;
        int grace = lc != null ? lc.stopGraceSeconds : 20;
        ps.runtimeState = "STOPPING";
        state.notify("info", projectId, "Stopping", "pid " + pid + ", graceful timeout " + grace + "s", "");
        boolean dead = false;
        if (pgid > 0) {
            Main.log("INFO", "stop " + projectId + ": TERM to -" + pgid);
            killGroup(pgid, "TERM");
        }
        for (int i = 0; i < grace * 2; i++) {
            if (!isAlive(pid)) {
                dead = true;
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!dead && pgid > 0) {
            Main.log("WARN", "stop " + projectId + ": graceful timeout, KILL to -" + pgid);
            killGroup(pgid, "KILL");
        }
        for (int i = 0; i < 20 && !dead; i++) {
            if (!isAlive(pid)) {
                dead = true;
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (dead) {
            try {
                Files.deleteIfExists(pidFile(projectId, lcId));
            } catch (IOException ignored) {
                // best effort
            }
            ps.runtimeState = "STOPPED";
            ps.pid = -1;
            ps.launchConfig = "";
            ps.startedAt = -1;
            // boundPort intentionally kept: last known working port for relaunch.
            ps.lastError = "";
            state.notify("info", projectId, "Stopped", "process terminated cleanly", "");
            return null;
        }
        try {
            writePidFile(projectId, lcId, pid, pgid > 0 ? pgid : pid);
        } catch (Exception e) {
            Main.log("WARN", "stop " + projectId + ": could not rewrite pid file: " + e.getMessage());
        }
        ps.runtimeState = "RUNNING";
        ps.pid = pid;
        ps.lastError = "Process " + pid + " did not terminate after TERM and KILL (grace " + grace + "s).";
        state.notify("error", projectId, "Stop failed",
                ps.lastError, "Check the process manually: ps -p " + pid);
        return ps.lastError;
    }

    /** Stops a pre-existing (non-managed) process with verified ownership. */
    public synchronized String stopExternal(String projectId) {
        Config.Project p = Main.currentConfig != null ? Main.currentConfig.project(projectId) : null;
        State.ProjectState ps = state.projects.get(projectId);
        if (p == null || ps == null) {
            return "Unknown project: " + projectId;
        }
        List<long[]> matches = findExternalPids(p);
        if (matches.isEmpty()) {
            ps.runtimeState = "STOPPED";
            ps.externalDetail = "";
            ps.startedAt = -1;
            ps.lastError = "";
            return null;
        }
        List<Long> pids = new ArrayList<>();
        for (long[] m : matches) {
            pids.add(m[0]);
            if (m[1] > 0) {
                killGroup(m[1], "TERM");
            }
        }
        long deadline = System.currentTimeMillis() + 20000;
        boolean allDead = false;
        while (System.currentTimeMillis() < deadline) {
            allDead = true;
            for (long pid : pids) {
                if (isAlive(pid)) {
                    allDead = false;
                    break;
                }
            }
            if (allDead) {
                break;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (!allDead) {
            for (long[] m : matches) {
                if (m[1] > 0 && isAlive(m[0])) {
                    killGroup(m[1], "KILL");
                }
            }
        }
        ps.runtimeState = "STOPPED";
        ps.externalDetail = "";
        ps.startedAt = -1;
        ps.lastError = "";
        state.notify("info", projectId, "Stopped external process", "pids " + pids, "");
        return null;
    }

    // -------------------------------------------------------------- watchdog

    public String watch(String projectId) {
        State.ProjectState ps = state.projects.get(projectId);
        if (ps == null) {
            return null;
        }
        if (ps.runtimeState.equals("EXTERNAL")) {
            return watchExternal(projectId, ps);
        }
        if (!ps.runtimeState.equals("RUNNING") && !ps.runtimeState.equals("READY")
                && !ps.runtimeState.equals("STARTING")) {
            return null;
        }
        long pid = ps.pid;
        if (pid <= 0) {
            return null;
        }
        if (isAlive(pid)) {
            return null;
        }
        String lcId = ps.launchConfig.isBlank() ? "run" : ps.launchConfig;
        String tail = tailLog(runtimeLog(projectId, lcId), 800);
        int exitCode = readExitCodeWait(projectId, lcId);
        // A process that exits during STARTING is a failed start regardless of
        // how cleanly it shut down; a READY/RUNNING process that shuts down
        // gracefully is simply stopped.
        boolean graceful = !ps.runtimeState.equals("STARTING")
                && exitedGracefully(projectId, lcId, exitCode, tail);
        ps.pid = -1;
        try {
            Files.deleteIfExists(pidFile(projectId, lcId));
        } catch (IOException ignored) {
            // best effort
        }
        if (graceful) {
            ps.runtimeState = "STOPPED";
            ps.startedAt = -1;
            ps.launchConfig = "";
            ps.lastError = "";
            ps.exitCode = exitCode;
            try {
                Files.deleteIfExists(crashMarker(projectId, lcId));
            } catch (IOException ignored) {
                // best effort
            }
            state.notify("info", projectId, "Runtime stopped",
                    "Process " + pid + " exited gracefully"
                            + (exitCode != Integer.MIN_VALUE ? " (exit code " + exitCode + ")" : "")
                            + ".",
                    "Start it again when needed.");
            return null;
        }
        ps.runtimeState = "FAILED";
        ps.exitCode = exitCode;
        try {
            Files.createFile(crashMarker(projectId, lcId));
        } catch (IOException ignored) {
            // best effort
        }
        ps.lastError = "Process exited unexpectedly"
                + (exitCode != Integer.MIN_VALUE ? " with exit code " + exitCode : "")
                + "." + (tail.isEmpty() ? "" : "\n" + tail);
        state.notify("error", projectId, "Runtime crashed",
                "pid " + pid + " exited unexpectedly"
                        + (exitCode != Integer.MIN_VALUE ? " (exit code " + exitCode + ")" : ""),
                "See runtime log: " + runtimeLog(projectId, lcId));
        return "Project " + projectId + " process " + pid + " exited.";
    }

    /** Tracks pre-existing (non-managed) processes: keeps uptime fresh and
     *  transitions to STOPPED once they are gone. */
    private String watchExternal(String projectId, State.ProjectState ps) {
        Config.Project p = Main.currentConfig != null
                ? Main.currentConfig.project(projectId) : null;
        List<long[]> pids = p != null ? findExternalPids(p) : List.of();
        if (!pids.isEmpty()) {
            ps.externalDetail = describePids(pids);
            long start = earliestExternalStart(pids);
            if (start > 0) {
                ps.startedAt = start;
            }
            if (!ps.lastError.isBlank()) {
                // Stale error from a previous run: the service is live again.
                ps.lastError = "";
            }
            return null;
        }
        if (ps.externalDetail.isBlank() && ps.runtimeState.equals("EXTERNAL")) {
            ps.runtimeState = "STOPPED";
            ps.startedAt = -1;
            return null;
        }
        ps.runtimeState = "STOPPED";
        ps.externalDetail = "";
        ps.startedAt = -1;
        ps.lastError = "";
        state.notify("info", projectId, "External process exited",
                "The external process is no longer running.", "");
        return null;
    }

    /** Public classification helper (used on startup reconciliation). */
    public boolean gracefulExit(String projectId, String lcId) {
        int code = readExitCode(projectId, lcId);
        String tail = tailLog(runtimeLog(projectId, lcId), 800);
        return exitedGracefully(projectId, lcId, code, tail);
    }

    /** True when the process ended in a deliberate, orderly shutdown. */
    private boolean exitedGracefully(String projectId, String lcId, int exitCode,
            String logTail) {
        if (exitCode == 0 || exitCode == 143) { // 143 = terminated by SIGTERM
            return true;
        }
        Config.Project p = Main.currentConfig != null
                ? Main.currentConfig.project(projectId) : null;
        Config.LaunchConfig lc = p != null ? p.launch(lcId) : null;
        List<String> markers = lc != null && lc.shutdownMarkers != null
                ? lc.shutdownMarkers : Config.LaunchConfig.DEFAULT_SHUTDOWN_MARKERS;
        String lower = logTail.toLowerCase(java.util.Locale.ROOT);
        for (String m : markers) {
            if (lower.contains(m.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Reads the recorded exit code, briefly waiting for the onExit listener. */
    private int readExitCodeWait(String projectId, String lcId) {
        for (int i = 0; i < 6; i++) {
            int code = readExitCode(projectId, lcId);
            if (code != Integer.MIN_VALUE) {
                return code;
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return Integer.MIN_VALUE;
    }

    private int readExitCode(String projectId, String lcId) {
        try {
            Path f = exitCodeFile(projectId, lcId);
            if (!Files.exists(f)) {
                return Integer.MIN_VALUE;
            }
            String s = Files.readString(f).strip();
            return s.isEmpty() ? Integer.MIN_VALUE : Integer.parseInt(s);
        } catch (Exception e) {
            return Integer.MIN_VALUE;
        }
    }

    /** Wall-clock start time of a process (boot time + starttime ticks). */
    private long processStartMs(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
            int close = stat.lastIndexOf(')');
            String[] fields = stat.substring(close + 2).split(" ");
            if (fields.length <= 19) {
                return -1;
            }
            long startTicks = Long.parseLong(fields[19]);
            String boot = Files.readString(Path.of("/proc", "stat"));
            for (String line : boot.split("\n")) {
                if (line.startsWith("btime ")) {
                    long btime = Long.parseLong(line.substring(6).trim());
                    return btime * 1000L + startTicks * 10L;
                }
            }
            return -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private long earliestExternalStart(List<long[]> pids) {
        long earliest = -1;
        for (long[] m : pids) {
            long t = processStartMs(m[0]);
            if (t > 0 && (earliest < 0 || t < earliest)) {
                earliest = t;
            }
        }
        return earliest;
    }

    // ------------------------------------------------------------- ownership

    private void writePidFile(String projectId, String lcId, long pid, long pgid) {
        try {
            Files.writeString(pidFile(projectId, lcId), pid + "\n" + pgid + "\n"
                    + readStartTime(pid) + "\n" + System.getProperty("user.name") + "\n");
        } catch (IOException e) {
            Main.log("WARN", "Could not write pid file for " + projectId + ": " + e.getMessage());
        }
    }

    private long[] readPidFile(String projectId, String lcId) {
        try {
            Path f = pidFile(projectId, lcId);
            if (!Files.exists(f)) {
                return new long[] { -1, -1 };
            }
            List<String> lines = Files.readAllLines(f);
            if (lines.isEmpty()) {
                return new long[] { -1, -1 };
            }
            long pid = Long.parseLong(lines.get(0).strip());
            long pgid = lines.size() > 1 ? Long.parseLong(lines.get(1).strip()) : pid;
            return new long[] { pid, pgid };
        } catch (Exception e) {
            return new long[] { -1, -1 };
        }
    }

    private long readPidFilePid(String projectId, String lcId) {
        return readPidFile(projectId, lcId)[0];
    }

    private boolean isPidFileAlive(String projectId, String lcId) {
        long pid = readPidFilePid(projectId, lcId);
        return pid > 0 && isAlive(pid);
    }

    public boolean verifyOwnership(String projectId, String lcId, long pid) {
        if (pid <= 0) {
            return false;
        }
        try {
            Path f = pidFile(projectId, lcId);
            if (!Files.exists(f)) {
                return false;
            }
            List<String> lines = Files.readAllLines(f);
            if (lines.size() < 4) {
                return false;
            }
            String recordedStart = lines.get(2).strip();
            String recordedUser = lines.get(3).strip();
            String liveStart = readStartTime(pid);
            String liveUser = readOwner(pid);
            if (recordedStart.equals(liveStart) && recordedUser.equals(liveUser)) {
                return true;
            }
            if (recordedUser.equals(liveUser) && "0".equals(recordedStart) && !"0".equals(liveStart)) {
                Main.log("WARN", "Adopting process " + pid + " for " + projectId
                        + " with legacy pid-file start-time field");
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    private String readStartTime(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
            int close = stat.lastIndexOf(')');
            String[] fields = stat.substring(close + 2).split(" ");
            return fields.length > 19 ? fields[19] : "";
        } catch (IOException e) {
            return "";
        }
    }

    private long readPgid(long pid) {
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
            int close = stat.lastIndexOf(')');
            String[] fields = stat.substring(close + 2).split(" ");
            return fields.length > 2 ? Long.parseLong(fields[2]) : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private String readOwner(long pid) {
        try {
            return Files.getOwner(Path.of("/proc", String.valueOf(pid), "cmdline")).getName();
        } catch (IOException e) {
            return "";
        }
    }

    public static boolean isAlive(long pid) {
        if (pid <= 0) {
            return false;
        }
        Path stat = Path.of("/proc", String.valueOf(pid), "stat");
        if (!Files.exists(stat)) {
            return false;
        }
        try {
            String content = Files.readString(stat);
            int close = content.lastIndexOf(')');
            if (close < 0 || close + 2 >= content.length()) {
                return false;
            }
            return content.charAt(close + 2) != 'Z';
        } catch (IOException e) {
            return false;
        }
    }

    private String findExternal(Config.Project p) {
        return describePids(findExternalPids(p));
    }

    private static String describePids(List<long[]> pids) {
        if (pids.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (long[] m : pids) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append("pid ").append(m[0]);
        }
        return sb.toString();
    }

    private List<long[]> findExternalPids(Config.Project p) {
        List<long[]> out = new ArrayList<>();
        try (var stream = Files.list(Path.of("/proc"))) {
            for (Path pidDir : (Iterable<Path>) stream::iterator) {
                String name = pidDir.getFileName().toString();
                if (!name.matches("\\d+")) {
                    continue;
                }
                long pid = Long.parseLong(name);
                if (pid == ProcessHandle.current().pid()) {
                    continue;
                }
                try {
                    Path cmdline = pidDir.resolve("cmdline");
                    if (!Files.exists(cmdline) || !Files.isReadable(cmdline)) {
                        continue;
                    }
                    String cmd = Files.readString(cmdline).replace('\0', ' ');
                    if (!cmd.contains(p.repoDir)) {
                        continue;
                    }
                    String owner = Files.getOwner(cmdline).getName();
                    if (!owner.equals(System.getProperty("user.name"))) {
                        continue;
                    }
                    out.add(new long[] { pid, readPgid(pid) });
                } catch (IOException ignored) {
                    // skip
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return out;
    }

    // ------------------------------------------------------------------ logs

    public static String readLogFrom(Path log, long from) {
        try {
            if (!Files.exists(log)) {
                return "";
            }
            String s = Files.readString(log);
            if (from >= s.length()) {
                return "";
            }
            return s.substring((int) from);
        } catch (IOException e) {
            return "";
        }
    }

    public static String tailLog(Path log, int maxChars) {
        try {
            if (!Files.exists(log)) {
                return "";
            }
            String s = Files.readString(log);
            if (s.length() <= maxChars) {
                return s;
            }
            return "(...)\n" + s.substring(s.length() - maxChars);
        } catch (IOException e) {
            return "";
        }
    }
}
