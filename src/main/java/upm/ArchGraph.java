package upm;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Architecture graph model: nodes, typed relationships, evidence
 * classification. Perspectives are built from real project metadata, runtime
 * processes, endpoints and available instrumentation — inferred relationships
 * are always labeled as INFERRED, never as verified calls.
 */
public final class ArchGraph {

    public static final class Node {
        public final String id;
        public final String label;
        public final String type;
        public final String project;
        public volatile String status;
        public final String classification; // VERIFIED_STATIC | VERIFIED_RUNTIME | INFERRED | UNKNOWN
        public final List<String> evidence;
        public final Map<String, Object> props;

        Node(String id, String label, String type, String project, String status,
                String classification, List<String> evidence, Map<String, Object> props) {
            this.id = id;
            this.label = label;
            this.type = type;
            this.project = project;
            this.status = status;
            this.classification = classification;
            this.evidence = evidence;
            this.props = props == null ? new LinkedHashMap<>() : new LinkedHashMap<>(props);
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("label", label);
            m.put("type", type);
            m.put("project", project);
            m.put("status", status);
            m.put("classification", classification);
            m.put("evidence", evidence);
            m.put("props", props);
            return m;
        }
    }

    public static final class Edge {
        public final String from;
        public final String to;
        public final String type; // DEPENDS_ON | CALLS | CREATES | OWNS | USES | HOSTS | RUNS_ON | CONNECTS_TO | LOADS | INVOKES | PROVIDES | CONSUMES | SPAWNS
        public final String classification;
        public final List<String> evidence;
        public final long lastObservedMs;

        Edge(String from, String to, String type, String classification,
                List<String> evidence, long lastObservedMs) {
            this.from = from;
            this.to = to;
            this.type = type;
            this.classification = classification;
            this.evidence = evidence;
            this.lastObservedMs = lastObservedMs;
        }

        Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("from", from);
            m.put("to", to);
            m.put("type", type);
            m.put("classification", classification);
            m.put("evidence", evidence);
            m.put("lastObservedMs", lastObservedMs);
            return m;
        }
    }

    public static final class Graph {
        public final String perspective;
        public final String workspaceId;
        public final long generatedMs;
        public final List<Node> nodes;
        public final List<Edge> edges;

        Graph(String perspective, String workspaceId, long generatedMs,
                List<Node> nodes, List<Edge> edges) {
            this.perspective = perspective;
            this.workspaceId = workspaceId;
            this.generatedMs = generatedMs;
            this.nodes = nodes;
            this.edges = edges;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("perspective", perspective);
            m.put("workspaceId", workspaceId);
            m.put("generatedMs", generatedMs);
            List<Map<String, Object>> ns = new ArrayList<>();
            for (Node n : nodes) {
                ns.add(n.toJson());
            }
            m.put("nodes", ns);
            List<Map<String, Object>> es = new ArrayList<>();
            for (Edge e : edges) {
                es.add(e.toJson());
            }
            m.put("edges", es);
            return m;
        }
    }

    private final State state;
    private final Proc proc;
    private final Metrics metrics;

    public ArchGraph(State state, Proc proc, Metrics metrics) {
        this.state = state;
        this.proc = proc;
        this.metrics = metrics;
    }

    private final List<Node> nodes = new ArrayList<>();
    private final List<Edge> edges = new ArrayList<>();
    private final Map<String, Node> byId = new LinkedHashMap<>();

    private Node add(String id, String label, String type, String project, String status,
            String classification, List<String> evidence, Map<String, Object> props) {
        Node n = byId.get(id);
        if (n != null) {
            return n;
        }
        Node node = new Node(id, label, type, project, status, classification, evidence, props);
        nodes.add(node);
        byId.put(id, node);
        return node;
    }

    private void link(String from, String to, String type, String classification,
            List<String> evidence) {
        add(from, from, "placeholder", "", "", classification, evidence, Map.of());
        add(to, to, "placeholder", "", "", classification, evidence, Map.of());
        for (Edge e : edges) {
            if (e.from.equals(from) && e.to.equals(to) && e.type.equals(type)) {
                return;
            }
        }
        edges.add(new Edge(from, to, type, classification, evidence, System.currentTimeMillis()));
    }

    private String stateLabel(String runtimeState) {
        return runtimeState == null ? "unknown" : runtimeState.toLowerCase();
    }

    // --------------------------------------------------------- perspectives

    public Graph workspaceOverview() {
        nodes.clear();
        edges.clear();
        byId.clear();
        Config cfg = Main.currentConfig;
        String wsId = cfg.workspaceId;
        Node ws = add("ws:" + wsId, cfg.workspaceName, "workspace", "", "active",
                "VERIFIED_STATIC", List.of("workspace.jsonc"), Map.of("projects", cfg.projects.size()));
        for (Config.Project p : cfg.projects) {
            String pid = "project:" + p.id;
            State.ProjectState ps = state.stateFor(p.id);
            Node pn = add(pid, p.name, "project", p.id, stateLabel(ps.runtimeState),
                    "VERIFIED_STATIC", List.of("workspace.jsonc"),
                    Map.of("type", p.type, "repoDir", p.repoDir, "pid", ps.pid));
            link(ws.id, pid, "OWNS", "VERIFIED_STATIC", List.of("workspace.jsonc"));
            // Repository
            String repoId = "repo:" + p.id;
            add(repoId, p.repoDir, "repository", p.id, "unknown", "VERIFIED_STATIC",
                    List.of("workspace.jsonc"), Map.of("path", p.repoDir));
            link(pid, repoId, "OWNS", "VERIFIED_STATIC", List.of("workspace.jsonc"));
            if (GitOps.isRepo(Path.of(p.repoDir))) {
                Map<String, Object> gitSummary = GitOps.status(Path.of(p.repoDir));
                Node rn = byId.get(repoId);
                if (rn != null) {
                    rn.props.put("branch", gitSummary.get("branch"));
                    rn.props.put("dirty", gitSummary.get("dirty"));
                    rn.status = Boolean.TRUE.equals(gitSummary.get("dirty")) ? "dirty" : "clean";
                }
            }
            // Applications / services from launch configs.
            for (Config.LaunchConfig lc : p.launchConfigs) {
                String lcId = "launch:" + p.id + ":" + lc.id;
                String lcType = lc.kind.equals("eclipse-product") || lc.kind.equals("eclipse-application")
                        ? "application" : "service";
                Node ln = add(lcId, lc.name, lcType, p.id,
                        ps.launchConfig.equals(lc.id) ? stateLabel(ps.runtimeState) : "stopped",
                        "VERIFIED_STATIC", List.of("workspace.jsonc"),
                        Map.of("kind", lc.kind, "launcher", lc.launcher));
                link(pid, lcId, "HOSTS", "VERIFIED_STATIC", List.of("workspace.jsonc"));
                if (lc.readiness != null && "http".equals(lc.readiness.type)) {
                    int port = Probe.resolvePort(p, lc, lc.port);
                    String epId = "endpoint:" + p.id + ":" + lc.id;
                    add(epId, lc.readiness.host + ":" + port + lc.readiness.path, "http-endpoint",
                            p.id, "unknown", "VERIFIED_STATIC",
                            List.of("workspace.jsonc launchConfig.readiness"),
                            Map.of("host", lc.readiness.host, "port", port, "path", lc.readiness.path));
                    link(lcId, epId, "PROVIDES", "VERIFIED_STATIC",
                            List.of("launchConfig.readiness"));
                }
                // Dependency hints (user-confirmed configuration).
                for (String dep : p.startupAfter) {
                    link("project:" + p.id, "project:" + dep, "DEPENDS_ON", "USER_CONFIRMED",
                            List.of("workspace.jsonc startupAfter"));
                }
                // Static adapter evidence for provider clients (file presence).
                if (p.enrMonitor) {
                    link("project:" + p.id, "runtime:enr", "PROVIDES", "VERIFIED_STATIC",
                            List.of("workspace.jsonc enrMonitor"));
                }
            }
            // Running process node.
            if (ps.pid > 0 && (ps.runtimeState.equals("RUNNING") || ps.runtimeState.equals("READY"))) {
                String procId = "proc:" + ps.pid;
                Node pn2 = add(procId, p.name + " (pid " + ps.pid + ")", "process", p.id,
                        stateLabel(ps.runtimeState), "VERIFIED_RUNTIME",
                        List.of("/proc/" + ps.pid), Map.of("pid", ps.pid, "startedAt", ps.startedAt));
                link("launch:" + p.id + ":" + (ps.launchConfig.isBlank() ? p.defaultLaunch().id : ps.launchConfig),
                        procId, "SPAWNS", "VERIFIED_RUNTIME", List.of("pid file"));
            }
        }
        // Profiles group projects.
        for (Config.Profile pr : cfg.profiles) {
            String profId = "profile:" + pr.id;
            add(profId, pr.name, "profile", "", "active", "VERIFIED_STATIC",
                    List.of("workspace.jsonc profiles"), Map.of());
            link(ws.id, profId, "OWNS", "VERIFIED_STATIC", List.of("workspace.jsonc"));
            for (String pid : pr.projects) {
                link(profId, "project:" + pid, "CONSUMES",
                        "VERIFIED_STATIC", List.of("workspace.jsonc profiles"));
            }
        }
        return new Graph("workspace-overview", wsId, System.currentTimeMillis(), nodes, edges);
    }

    public Graph runtimeTopology() {
        nodes.clear();
        edges.clear();
        byId.clear();
        Config cfg = Main.currentConfig;
        long now = System.currentTimeMillis();
        List<Map<String, Object>> procs = Probe.javaProcesses();
        for (Config.Project p : cfg.projects) {
            State.ProjectState ps = state.stateFor(p.id);
            if (ps.pid <= 0) {
                continue;
            }
            String procId = "proc:" + ps.pid;
            String procState = stateLabel(ps.runtimeState);
            Node n = add(procId, p.name + " (" + ps.pid + ")", "process", p.id, procState,
                    "VERIFIED_RUNTIME", List.of("/proc/" + ps.pid),
                    Map.of("pid", ps.pid, "startedAt", ps.startedAt,
                            "launchConfig", ps.launchConfig));
            // Parent-child relationships from /proc.
            try {
                String stat = Files.readString(Path.of("/proc", String.valueOf(ps.pid), "stat"));
                int close = stat.lastIndexOf(')');
                String[] f = stat.substring(close + 2).split(" ");
                long ppid = f.length > 1 ? Long.parseLong(f[1]) : -1;
                if (ppid > 1 && ppid != Main.selfPid) {
                    boolean childOfManaged = false;
                    for (Config.Project q : cfg.projects) {
                        State.ProjectState qs = state.stateFor(q.id);
                        if (qs.pid == ppid) {
                            link("proc:" + ppid, procId, "SPAWNS", "VERIFIED_RUNTIME",
                                    List.of("/proc/" + ps.pid + "/stat ppid"));
                            childOfManaged = true;
                        }
                    }
                    if (!childOfManaged) {
                        add("proc:" + ppid, "pid " + ppid, "process", "", "unknown",
                                "VERIFIED_RUNTIME", List.of("/proc/" + ps.pid),
                                Map.of("pid", ppid));
                        link("proc:" + ppid, procId, "SPAWNS", "VERIFIED_RUNTIME",
                                List.of("/proc/" + ps.pid + "/stat ppid"));
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
            // Endpoints bound by the process (prefer the observed bound port).
            for (Config.LaunchConfig lc : p.launchConfigs) {
                if (lc.readiness == null) {
                    continue;
                }
                int port = ps.boundPort > 0 ? ps.boundPort : Probe.resolvePort(p, lc, lc.port);
                if (port <= 0) {
                    port = Probe.resolvePort(p, lc, lc.port);
                }
                if (port > 0 && Probe.tcpOpen(lc.readiness.host, port, 500)) {
                    String owner = Probe.ownerOf(port);
                    if (owner == null || owner.contains(String.valueOf(ps.pid))
                            || owner.contains(p.repoDir)) {
                        String epId = "ep:" + p.id + ":" + port;
                        add(epId, lc.readiness.host + ":" + port, "http-endpoint", p.id,
                                "listening", "VERIFIED_RUNTIME",
                                List.of("tcp probe"), Map.of("host", lc.readiness.host, "port", port));
                        link(procId, epId, "PROVIDES", "VERIFIED_RUNTIME",
                                List.of("listening socket"));
                    }
                }
            }
        }
        return new Graph("runtime-topology", cfg.workspaceId, now, nodes, edges);
    }

    public Graph executionFlow(String opId) {
        nodes.clear();
        edges.clear();
        byId.clear();
        Config cfg = Main.currentConfig;
        long now = System.currentTimeMillis();
        State.Op selected = null;
        if (opId != null) {
            synchronized (state.ops) {
                selected = state.ops.get(Long.parseLong(opId));
            }
            if (selected == null) {
                synchronized (state.history) {
                    for (State.Op o : state.history) {
                        if (String.valueOf(o.id).equals(opId)) {
                            selected = o;
                            break;
                        }
                    }
                }
            }
        }
        if (selected != null) {
            String user = "user";
            add(user, "User request", "actor", "", "active", "VERIFIED_RUNTIME",
                    List.of("operation " + selected.id), Map.of());
            String opNode = "op:" + selected.id;
            add(opNode, selected.type + (selected.mode.isEmpty() ? "" : "/" + selected.mode)
                    + " #" + selected.id, "operation", selected.projectId,
                    stateLabel(selected.status), "VERIFIED_RUNTIME",
                    List.of("operations registry"),
                    Map.of("startMs", selected.startMs, "endMs", selected.endMs,
                            "phase", selected.phase));
            link(user, opNode, "CREATES", "VERIFIED_RUNTIME", List.of("operation " + selected.id));
            if (!selected.projectId.isBlank() && cfg.project(selected.projectId) != null) {
                link(opNode, "project:" + selected.projectId, "INVOKES", "VERIFIED_RUNTIME",
                        List.of("operation.project"));
            }
            if (selected.type.equals("run") && !selected.projectId.isBlank()) {
                State.ProjectState ps = state.stateFor(selected.projectId);
                if (ps.pid > 0) {
                    add("proc:" + ps.pid, selected.projectId + " (" + ps.pid + ")", "process",
                            selected.projectId, stateLabel(ps.runtimeState), "VERIFIED_RUNTIME",
                            List.of("/proc/" + ps.pid), Map.of("pid", ps.pid));
                    link("launch:" + selected.projectId + ":" + (ps.launchConfig.isBlank()
                            ? "run" : ps.launchConfig), "proc:" + ps.pid, "SPAWNS",
                            "VERIFIED_RUNTIME", List.of("pid file"));
                    link(opNode, "proc:" + ps.pid, "CREATES", "VERIFIED_RUNTIME",
                            List.of("pid file"));
                }
            }
        } else {
            add("user", "Operations history", "actor", "", "active", "VERIFIED_RUNTIME",
                    List.of("operations registry"), Map.of());
            List<State.Op> recent;
            synchronized (state.history) {
                recent = new ArrayList<>(state.history.subList(0, Math.min(8, state.history.size())));
            }
            for (State.Op o : recent) {
                String opNode = "op:" + o.id;
                add(opNode, o.type + " #" + o.id + " (" + o.projectId + ")", "operation",
                        o.projectId, stateLabel(o.status), "VERIFIED_RUNTIME",
                        List.of("operations registry"), Map.of("startMs", o.startMs));
                link("user", opNode, "CREATES", "VERIFIED_RUNTIME", List.of("operations registry"));
                if (!o.projectId.isBlank() && cfg.project(o.projectId) != null) {
                    link(opNode, "project:" + o.projectId, "INVOKES", "VERIFIED_RUNTIME",
                            List.of("operation.project"));
                }
            }
        }
        return new Graph("execution-flow", cfg.workspaceId, now, nodes, edges);
    }

    public Graph aiTopology() {
        nodes.clear();
        edges.clear();
        byId.clear();
        Config cfg = Main.currentConfig;
        long now = System.currentTimeMillis();
        // ENR runtime (if configured as the monitor project).
        for (Config.Project p : cfg.projects) {
            if (!p.enrMonitor) {
                continue;
            }
            State.ProjectState ps = state.stateFor(p.id);
            String rtId = "runtime:" + p.id;
            add(rtId, p.name, "inference-runtime", p.id, stateLabel(ps.runtimeState),
                    "VERIFIED_RUNTIME", List.of("ENR /api/v1"), Map.of("pid", ps.pid));
            String body = Probe.httpGet(p.apiBase + "/status", 2500);
            if (body != null) {
                try {
                    Map<String, Object> status = Json.obj(Json.parse(body));
                    byId.get(rtId).props.put("version", status.get("version"));
                    byId.get(rtId).props.put("uptimeMs", status.get("uptime_ms"));
                    byId.get(rtId).props.put("modelsLoaded", status.get("models_loaded"));
                } catch (Exception ignored) {
                    // not JSON
                }
            }
            String models = Probe.httpGet(p.apiBase + "/models", 2500);
            if (models != null) {
                try {
                    Map<String, Object> mm = Json.obj(Json.parse(models));
                    for (Map<String, Object> model : Json.objList(mm, "models")) {
                        String mid = String.valueOf(model.get("id"));
                        String mId = "model:" + p.id + ":" + mid;
                        add(mId, mid, "model", p.id, String.valueOf(model.get("state")),
                                "VERIFIED_RUNTIME", List.of("ENR /api/v1/models"),
                                Map.of("backend", model.get("backend"),
                                        "parameters", model.get("parameters"),
                                        "weightBytes", model.get("weight_bytes"),
                                        "path", model.get("path"),
                                        "activeSessions", model.get("active_sessions")));
                        link(rtId, mId, "LOADS", "VERIFIED_RUNTIME", List.of("ENR /api/v1/models"));
                    }
                } catch (Exception ignored) {
                    // not JSON
                }
            }
            String sessions = Probe.httpGet(p.apiBase + "/sessions", 2500);
            if (sessions != null) {
                try {
                    Map<String, Object> sm = Json.obj(Json.parse(sessions));
                    for (Map<String, Object> s : Json.objList(sm, "sessions")) {
                        String sid = String.valueOf(s.get("id"));
                        String model = String.valueOf(s.get("model"));
                        String sId = "session:" + p.id + ":" + sid;
                        add(sId, "session " + sid, "session", p.id, "active",
                                "VERIFIED_RUNTIME", List.of("ENR /api/v1/sessions"),
                                Map.of("model", model, "contextLength", s.get("context_length"),
                                        "createdAt", s.get("created_at")));
                        link(rtId, sId, "CREATES", "VERIFIED_RUNTIME", List.of("ENR /api/v1/sessions"));
                        link(sId, "model:" + p.id + ":" + model, "USES", "VERIFIED_RUNTIME",
                                List.of("ENR /api/v1/sessions"));
                    }
                } catch (Exception ignored) {
                    // not JSON
                }
            }
            // Devices.
            String devices = Probe.httpGet(p.apiBase + "/devices", 2500);
            if (devices != null) {
                try {
                    Map<String, Object> dm = Json.obj(Json.parse(devices));
                    for (Map<String, Object> dev : Json.objList(dm, "devices")) {
                        String devId = "device:" + String.valueOf(dev.get("index")) + ":"
                                + String.valueOf(dev.get("type"));
                        add(devId, String.valueOf(dev.get("name")), "device", "",
                                "available", "VERIFIED_RUNTIME", List.of("ENR /api/v1/devices"),
                                Map.of("type", dev.get("type"),
                                        "totalMemoryBytes", dev.get("total_memory_bytes"),
                                        "freeMemoryBytes", dev.get("free_memory_bytes")));
                        link(rtId, devId, "RUNS_ON", "VERIFIED_RUNTIME", List.of("ENR /api/v1/devices"));
                    }
                } catch (Exception ignored) {
                    // not JSON
                }
            }
        }
        // Ollama provider (independent of ENR; detected only when reachable).
        String ollamaTags = Probe.httpGet("http://127.0.0.1:11434/api/tags", 1500);
        if (ollamaTags != null) {
            add("provider:ollama", "Ollama (127.0.0.1:11434)", "llm-provider", "", "reachable",
                    "VERIFIED_RUNTIME", List.of("GET /api/tags"), Map.of("endpoint", "http://127.0.0.1:11434"));
            try {
                Map<String, Object> om = Json.obj(Json.parse(ollamaTags));
                for (Map<String, Object> model : Json.objList(om, "models")) {
                    String name = Json.str(model, "name");
                    String mid = "model:ollama:" + name;
                    add(mid, name, "model", "", "available", "VERIFIED_RUNTIME",
                            List.of("GET /api/tags"), Map.of("provider", "ollama"));
                    link("provider:ollama", mid, "LOADS", "VERIFIED_RUNTIME", List.of("GET /api/tags"));
                }
            } catch (Exception ignored) {
                // not JSON
            }
        } else {
            add("provider:ollama", "Ollama (127.0.0.1:11434)", "llm-provider", "", "unreachable",
                    "UNKNOWN", List.of("probe failed"), Map.of("endpoint", "http://127.0.0.1:11434"));
        }
        // Static adapter evidence: repositories containing provider clients.
        for (Config.Project p : cfg.projects) {
            if (p.enrMonitor) {
                continue;
            }
            List<String> evidence = providerEvidence(p);
            for (String provider : evidence) {
                link("project:" + p.id, "provider:" + provider, "USES", "INFERRED",
                        List.of("source file presence in " + p.repoDir));
            }
        }
        return new Graph("ai-topology", cfg.workspaceId, now, nodes, edges);
    }

    private List<String> providerEvidence(Config.Project p) {
        List<String> out = new ArrayList<>();
        try {
            if (Adapters.findAny(Path.of(p.repoDir), 5, "EnrProvider*.java") != null) {
                out.add("enr");
            }
            if (Adapters.findAny(Path.of(p.repoDir), 5, "EnrClient*.java") != null) {
                out.add("enr");
            }
            if (Adapters.findAny(Path.of(p.repoDir), 5, "OllamaClient*.java") != null) {
                out.add("ollama");
            }
            if (Adapters.findAny(Path.of(p.repoDir), 5, "OllamaProvider*.java") != null) {
                out.add("ollama");
            }
        } catch (Exception ignored) {
            // best effort
        }
        return out;
    }

    public Graph dependencyGraph() {
        nodes.clear();
        edges.clear();
        byId.clear();
        Config cfg = Main.currentConfig;
        long now = System.currentTimeMillis();
        for (Config.Project p : cfg.projects) {
            String pid = "project:" + p.id;
            add(pid, p.name, "project", p.id, "unknown", "VERIFIED_STATIC",
                    List.of("workspace.jsonc"), Map.of("type", p.type));
            for (String dep : p.startupAfter) {
                link(pid, "project:" + dep, "DEPENDS_ON", "USER_CONFIRMED",
                        List.of("workspace.jsonc startupAfter"));
            }
            // Build modules (read-only descriptor scan, cached by detection).
            List<String> modules = Adapters.detectModules(Path.of(p.repoDir), p.type);
            for (String mod : modules) {
                String modId = "module:" + p.id + ":" + mod;
                add(modId, mod, "module", p.id, "unknown", "VERIFIED_STATIC",
                        List.of("build descriptor"), Map.of());
                link(pid, modId, "OWNS", "VERIFIED_STATIC", List.of("build descriptor"));
            }
        }
        return new Graph("dependency-graph", cfg.workspaceId, now, nodes, edges);
    }

    public Graph infrastructure() {
        nodes.clear();
        edges.clear();
        byId.clear();
        Config cfg = Main.currentConfig;
        long now = System.currentTimeMillis();
        Map<String, Object> cpu = metrics.cpu();
        Map<String, Object> mem = metrics.memory();
        Node cpuN = add("hw:cpu", "CPU", "hardware", "", "active", "VERIFIED_RUNTIME",
                List.of("/proc/stat"), Map.of("percent", cpu.get("percent"), "load1", cpu.get("load1")));
        Node memN = add("hw:mem", "RAM", "hardware", "", "active", "VERIFIED_RUNTIME",
                List.of("/proc/meminfo"), Map.of("percent", mem.get("percent")));
        for (Map<String, Object> g : metrics.gpu()) {
            String gid = "hw:gpu:" + g.get("index");
            add(gid, String.valueOf(g.get("name")), "hardware", "", "active", "VERIFIED_RUNTIME",
                    List.of("nvidia-smi"),
                    Map.of("utilPercent", g.get("utilPercent"), "memUsedMb", g.get("memUsedMb"),
                            "memTotalMb", g.get("memTotalMb"), "temperature", g.get("temperature"),
                            "powerW", g.get("powerW")));
        }
        for (Config.Project p : cfg.projects) {
            State.ProjectState ps = state.stateFor(p.id);
            if (ps.pid > 0 && (ps.runtimeState.equals("RUNNING") || ps.runtimeState.equals("READY"))) {
                String procId = "proc:" + ps.pid;
                Map<String, Object> pm = metrics.processMetrics(ps.pid);
                add(procId, p.name + " (" + ps.pid + ")", "process", p.id,
                        stateLabel(ps.runtimeState), "VERIFIED_RUNTIME",
                        List.of("/proc/" + ps.pid),
                        Map.of("pid", ps.pid, "cpuPercent", pm.getOrDefault("cpuPercent", "N/A"),
                                "rssMb", pm.getOrDefault("rssMb", "N/A")));
                link(procId, "hw:cpu", "RUNS_ON", "VERIFIED_RUNTIME", List.of("/proc/" + ps.pid));
                link(procId, "hw:mem", "RUNS_ON", "VERIFIED_RUNTIME", List.of("/proc/" + ps.pid));
            }
        }
        // GPU compute processes (verified via nvidia-smi).
        for (Map<String, Object> app : metrics.gpuProcesses()) {
            String pidStr = String.valueOf(app.get("pid"));
            String procId = "proc:" + pidStr;
            if (byId.get(procId) != null) {
                link(procId, "hw:gpu:0", "RUNS_ON", "VERIFIED_RUNTIME",
                        List.of("nvidia-smi compute-apps"));
            }
        }
        return new Graph("infrastructure", cfg.workspaceId, now, nodes, edges);
    }
}
