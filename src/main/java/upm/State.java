package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Shared state model: per-project runtime state, operation registry/history,
 * notifications. Persisted under state/history.json (workspace-scoped).
 */
public final class State {

    public static final class ProjectState {
        public volatile String runtimeState = "STOPPED"; // STOPPED|STARTING|RUNNING|READY|STOPPING|FAILED|UNKNOWN|EXTERNAL
        public volatile long pid = -1;
        public volatile long startedAt = -1;
        public volatile long readyAt = -1;
        public volatile String launchConfig = "";
        public volatile int boundPort = 0;
        public volatile String lastError = "";
        public volatile int exitCode = Integer.MIN_VALUE;
        public volatile String externalDetail = "";
    }

    public static final class Op {
        public final long id;
        public final String projectId; // empty for workspace-wide ops
        public final String type; // build|cleanBuild|update|test|package|verify|install|run|stop|restart|launchAll|stopAll|restartAll|profileLaunch|profileStop|gitFetch|gitPull|gitPush|gitCommit|clone|unregister|deleteRepo|backup|import|export
        public final String mode; // for run: launch config id
        public final long startMs;
        public volatile long endMs = -1;
        public volatile String status = "RUNNING"; // QUEUED|RUNNING|SUCCEEDED|FAILED|CANCELLED|INTERRUPTED
        public volatile String phase = "Initializing";
        public volatile String task = "";
        public volatile String currentModule = "";
        public volatile int completedModules = 0;
        public volatile int totalModules = 0;
        public volatile boolean progressKnown = false;
        public volatile int testsRun = -1;
        public volatile int testsFailed = -1;
        public volatile int testsErrors = -1;
        public volatile int testsSkipped = -1;
        public volatile int finalTestsRun = -1;
        public volatile int finalTestsFailed = -1;
        public volatile int finalTestsErrors = -1;
        public volatile int finalTestsSkipped = -1;
        public volatile String suite = "";
        public volatile String error = "";
        public volatile String summary = "";
        public volatile boolean cancelRequested = false;
        public final StringBuilder live = new StringBuilder();

        Op(long id, String projectId, String type, String mode) {
            this.id = id;
            this.projectId = projectId == null ? "" : projectId;
            this.type = type;
            this.mode = mode == null ? "" : mode;
            this.startMs = System.currentTimeMillis();
        }

        public synchronized Map<String, Object> toJson(boolean includeLog) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("project", projectId);
            m.put("type", type);
            m.put("mode", mode);
            m.put("startMs", startMs);
            m.put("endMs", endMs);
            m.put("status", status);
            m.put("phase", phase);
            m.put("task", task);
            m.put("currentModule", currentModule);
            m.put("completedModules", completedModules);
            m.put("totalModules", totalModules);
            m.put("progressKnown", progressKnown);
            m.put("testsRun", testsRun);
            m.put("testsFailed", testsFailed);
            m.put("testsErrors", testsErrors);
            m.put("testsSkipped", testsSkipped);
            m.put("suite", suite);
            m.put("error", error);
            m.put("summary", summary);
            m.put("cancelRequested", cancelRequested);
            if (includeLog) {
                m.put("log", live.toString());
            }
            return m;
        }

        public synchronized String logTail(int maxChars) {
            String s = live.toString();
            if (s.length() <= maxChars) {
                return s;
            }
            return s.substring(s.length() - maxChars);
        }
    }

    public static final class Notification {
        public final long id;
        public final long timeMs;
        public final String level; // info|success|warning|error
        public final String project;
        public final String title;
        public final String detail;
        public final String nextStep;

        Notification(long id, long timeMs, String level, String project, String title,
                String detail, String nextStep) {
            this.id = id;
            this.timeMs = timeMs;
            this.level = level;
            this.project = project;
            this.title = title;
            this.detail = detail;
            this.nextStep = nextStep;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("timeMs", timeMs);
            m.put("level", level);
            m.put("project", project);
            m.put("title", title);
            m.put("detail", detail);
            m.put("nextStep", nextStep);
            return m;
        }
    }

    private final App app;
    private final AtomicLong opCounter = new AtomicLong(1);
    private final AtomicLong notifCounter = new AtomicLong(1);
    public final Map<String, ProjectState> projects = new ConcurrentHashMap<>();
    public final Map<Long, Op> ops = new LinkedHashMap<>();
    public final List<Op> history = new ArrayList<>();
    public final List<Notification> notifications = new ArrayList<>();

    private volatile long lastEventSeq = 0;

    public State(App app) {
        this.app = app;
    }

    public synchronized ProjectState stateFor(String projectId) {
        return projects.computeIfAbsent(projectId, k -> new ProjectState());
    }

    public synchronized Op newOp(String projectId, String type, String mode) {
        Op op = new Op(opCounter.getAndIncrement(), projectId, type, mode);
        ops.put(op.id, op);
        history.add(op);
        persistAsync();
        return op;
    }

    public synchronized void finishOp(Op op, String status, String summary, String error) {
        op.endMs = System.currentTimeMillis();
        op.status = status;
        if (summary != null) {
            op.summary = summary;
        }
        if (error != null) {
            op.error = error;
        }
        ops.remove(op.id);
        persistAsync();
        if (op.type.equals("run") && !op.projectId.isBlank()) {
            ProjectState ps = projects.get(op.projectId);
            if (ps != null && ps.runtimeState.equals("STARTING")) {
                ps.runtimeState = "FAILED";
                ps.lastError = error == null ? summary : error;
            }
        }
    }

    public synchronized void notify(String level, String project, String title, String detail, String nextStep) {
        Notification n = new Notification(notifCounter.getAndIncrement(), System.currentTimeMillis(),
                level, project, title, detail, nextStep);
        notifications.add(n);
        while (notifications.size() > 100) {
            notifications.remove(0);
        }
        persistAsync();
    }

    public synchronized long nextEventSeq() {
        return ++lastEventSeq;
    }

    public synchronized long lastEventSeq() {
        return lastEventSeq;
    }

    public synchronized void trimHistory(int max) {
        while (history.size() > max) {
            history.remove(0);
        }
    }

    public Path historyFile() {
        return app.dataDir.resolve("history.json");
    }

    // ------------------------------------------------------------------ persist

    private volatile boolean persisting = false;
    private volatile boolean dirty = false;

    private synchronized void persistAsync() {
        dirty = true;
        if (persisting) {
            return;
        }
        persisting = true;
        Thread t = new Thread(() -> {
            while (true) {
                boolean again;
                synchronized (this) {
                    again = dirty;
                    dirty = false;
                }
                if (again) {
                    persistNow();
                } else {
                    break;
                }
            }
            synchronized (this) {
                persisting = false;
            }
        }, "upm-state-persist");
        t.setDaemon(true);
        t.start();
    }

    public synchronized void persistNow() {
        try {
            Files.createDirectories(historyFile().getParent());
            Map<String, Object> root = new LinkedHashMap<>();
            List<Map<String, Object>> projList = new ArrayList<>();
            for (Map.Entry<String, ProjectState> e : projects.entrySet()) {
                ProjectState ps = e.getValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getKey());
                m.put("runtimeState", ps.runtimeState);
                m.put("pid", ps.pid);
                m.put("startedAt", ps.startedAt);
                m.put("readyAt", ps.readyAt);
                m.put("launchConfig", ps.launchConfig);
                m.put("boundPort", ps.boundPort);
                m.put("lastError", ps.lastError);
                m.put("exitCode", ps.exitCode);
                m.put("externalDetail", ps.externalDetail);
                projList.add(m);
            }
            root.put("projects", projList);
            List<Map<String, Object>> opsList = new ArrayList<>();
            synchronized (history) {
                trimHistory(app.historyRetention);
                for (Op op : history) {
                    opsList.add(op.toJson(false));
                }
            }
            root.put("history", opsList);
            List<Map<String, Object>> notifs = new ArrayList<>();
            for (Notification n : notifications) {
                notifs.add(n.toJson());
            }
            root.put("notifications", notifs);
            Files.writeString(historyFile(), Json.write(root));
        } catch (IOException e) {
            Main.log("WARN", "State persistence failed: " + e.getMessage());
        }
    }

    public void restore() {
        Path f = historyFile();
        if (!Files.exists(f)) {
            return;
        }
        try {
            Map<String, Object> root = Json.obj(Json.parse(Files.readString(f)));
            for (Map<String, Object> m : Json.objList(root, "projects")) {
                String id = Json.str(m, "id");
                ProjectState ps = new ProjectState();
                ps.runtimeState = Json.str(m, "runtimeState", "STOPPED");
                ps.pid = Json.longVal(m, "pid", -1);
                ps.startedAt = Json.longVal(m, "startedAt", -1);
                ps.readyAt = Json.longVal(m, "readyAt", -1);
                ps.launchConfig = Json.str(m, "launchConfig", "");
                ps.boundPort = (int) Json.longVal(m, "boundPort", 0);
                ps.lastError = Json.str(m, "lastError", "");
                ps.exitCode = (int) Json.longVal(m, "exitCode", Integer.MIN_VALUE);
                ps.externalDetail = Json.str(m, "externalDetail", "");
                projects.put(id, ps);
            }
            for (Map<String, Object> m : Json.objList(root, "history")) {
                Op op = new Op(Json.longVal(m, "id", opCounter.getAndIncrement()),
                        Json.str(m, "project", ""), Json.str(m, "type", "build"), Json.str(m, "mode", ""));
                op.endMs = Json.longVal(m, "endMs", -1);
                op.status = Json.str(m, "status", "INTERRUPTED");
                op.phase = Json.str(m, "phase", "");
                op.task = Json.str(m, "task", "");
                op.summary = Json.str(m, "summary", "");
                op.error = Json.str(m, "error", "");
                op.testsRun = (int) Json.longVal(m, "testsRun", -1);
                op.testsFailed = (int) Json.longVal(m, "testsFailed", -1);
                op.testsErrors = (int) Json.longVal(m, "testsErrors", -1);
                op.testsSkipped = (int) Json.longVal(m, "testsSkipped", -1);
                op.suite = Json.str(m, "suite", "");
                history.add(op);
            }
            for (Map<String, Object> m : Json.objList(root, "notifications")) {
                Notification n = new Notification(
                        Json.longVal(m, "id", notifCounter.getAndIncrement()),
                        Json.longVal(m, "timeMs", System.currentTimeMillis()),
                        Json.str(m, "level", "info"), Json.str(m, "project", ""),
                        Json.str(m, "title", ""), Json.str(m, "detail", ""),
                        Json.str(m, "nextStep", ""));
                notifications.add(n);
            }
        } catch (Exception e) {
            Main.log("WARN", "State restore failed: " + e.getMessage());
        }
    }
}
