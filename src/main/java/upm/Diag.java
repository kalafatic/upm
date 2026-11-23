package upm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Project diagnostics (doctor) — factual checks only. */
public final class Diag {

    private final State state;
    private final Proc proc;

    public Diag(State state, Proc proc) {
        this.state = state;
        this.proc = proc;
    }

    public List<Map<String, Object>> project(String projectId) {
        List<Map<String, Object>> checks = new ArrayList<>();
        Config cfg = Main.currentConfig;
        Config.Project p = cfg == null ? null : cfg.project(projectId);
        State.ProjectState ps = state.projects.get(projectId);
        if (p == null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("check", "registry");
            m.put("status", "FAIL");
            m.put("detail", "Project not registered: " + projectId);
            checks.add(m);
            return checks;
        }

        checks.add(check("registry", "Registered in workspace configuration",
                Files.isDirectory(Path.of(p.repoDir)), "repo " + p.repoDir + " (adapter " + p.type + ")"));

        Map<String, Object> git = GitOps.status(Path.of(p.repoDir));
        if (git.get("isRepo") == Boolean.TRUE) {
            boolean clean = !Boolean.TRUE.equals(git.get("dirty"));
            checks.add(check("git", "Git repository",
                    true,
                    "branch " + git.get("branch") + ", HEAD " + git.get("head")
                            + (clean ? " (clean)" : " (uncommitted changes present)")));
        } else {
            checks.add(check("git", "Git repository", false, "not a git repository"));
        }

        boolean anyLauncher = false;
        Map<String, Object> modeInfo = new LinkedHashMap<>();
        for (Config.LaunchConfig lc : p.launchConfigs) {
            Path launcher = Path.of(App.expandHome(lc.launcher));
            if (!launcher.isAbsolute()) {
                launcher = Path.of(p.repoDir, lc.launcher);
            }
            boolean ok = Files.isExecutable(launcher);
            anyLauncher |= ok;
            modeInfo.put(lc.id, (ok ? "OK: " : "MISSING: ") + launcher
                    + (lc.requiresDisplay ? " (requires DISPLAY"
                    + (System.getenv("DISPLAY") == null ? ", currently unset"
                            : ", DISPLAY=" + System.getenv("DISPLAY")) + ")" : ""));
        }
        checks.add(check("launchers", "Launch configurations materialized",
                anyLauncher, Json.write(modeInfo)));

        for (String cl : p.configLocations) {
            checks.add(check("config", "Configuration location " + cl,
                    Files.exists(Path.of(cl)), ""));
        }

        if (ps != null) {
            checks.add(check("runtime", "Runtime state",
                    !ps.runtimeState.equals("FAILED"),
                    ps.runtimeState + (ps.pid > 0 ? ", pid " + ps.pid : "")
                            + (ps.lastError.isEmpty() ? "" : ", last error: " + ps.lastError)));
        }

        for (String warn : p.warnings) {
            checks.add(check("warning", "Configured warning", false, warn));
        }

        State.Op lastBuild = lastOp(projectId, "build");
        State.Op lastTest = lastOp(projectId, "test");
        if (lastBuild != null) {
            checks.add(check("build", "Last build", lastBuild.status.equals("SUCCEEDED"),
                    lastBuild.status + " — " + lastBuild.summary));
        }
        if (lastTest != null) {
            checks.add(check("tests", "Last test run", lastTest.status.equals("SUCCEEDED"),
                    lastTest.status + " — " + lastTest.summary
                            + (lastTest.status.equals("FAILED")
                                    ? " (failures are reported, not hidden)" : "")));
        }
        return checks;
    }

    public List<Map<String, Object>> workspace() {
        List<Map<String, Object>> checks = new ArrayList<>();
        Config cfg = Main.currentConfig;
        if (cfg == null) {
            checks.add(check("config", "Workspace loaded", false, "no workspace loaded"));
            return checks;
        }
        checks.add(check("config", "Workspace configuration valid",
                cfg.validate().isEmpty(), String.join("; ", cfg.validate())));
        Map<String, Object> tools = Adapters.detectTools();
        checks.add(check("git", "Git CLI available",
                !String.valueOf(tools.get("git")).isBlank(),
                String.valueOf(tools.get("git"))));
        checks.add(check("java21", "JDK 21 available",
                !String.valueOf(tools.get("java21")).isBlank(),
                String.valueOf(tools.get("java21"))));
        checks.add(check("display", "Graphical display for RCP modes",
                System.getenv("DISPLAY") != null,
                System.getenv("DISPLAY") == null ? "unset" : System.getenv("DISPLAY")));
        checks.add(check("setsid", "setsid available for process-group management",
                !String.valueOf(Adapters.detectTools().get("xdgOpen")).isBlank()
                        && which("setsid"), ""));
        checks.add(check("browser", "xdg-open available for browser launch",
                !String.valueOf(tools.get("xdgOpen")).isBlank(),
                String.valueOf(tools.get("xdgOpen"))));
        int ccPort = Main.currentPort;
        boolean ccPortFree = !Probe.tcpOpen("127.0.0.1", ccPort, 500)
                || (Main.selfPid > 0 && Probe.ownerOf(ccPort).contains(String.valueOf(Main.selfPid)));
        checks.add(check("ccPort", "UPM port " + ccPort,
                ccPortFree, ccPortFree ? "" : "port in use by another process"));
        for (Config.Project p : cfg.projects) {
            if (!Files.isDirectory(Path.of(p.repoDir))) {
                checks.add(check("repo", "Repository " + p.id, false, "missing: " + p.repoDir));
            }
        }
        return checks;
    }

    private boolean which(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return false;
        }
        for (String dir : pathEnv.split(":")) {
            if (Files.isExecutable(Path.of(dir, name))) {
                return true;
            }
        }
        return false;
    }

    private Map<String, Object> check(String check, String label, boolean ok, String detail) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("check", check);
        m.put("label", label);
        m.put("status", ok ? "OK" : "FAIL");
        m.put("detail", detail);
        return m;
    }

    private State.Op lastOp(String projectId, String type) {
        synchronized (state.history) {
            for (int i = state.history.size() - 1; i >= 0; i--) {
                State.Op op = state.history.get(i);
                if (op.projectId.equals(projectId) && op.type.equals(type)) {
                    return op;
                }
            }
        }
        return null;
    }
}
