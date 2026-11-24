package upm;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * HTTP API + SSE + static web UI. The single gateway used by the CLI, the
 * terminal menu and the web/desktop GUI. Only fixed operation verbs are
 * accepted; no arbitrary shell commands can be issued through the API.
 */
public final class Server {

    private final App app;
    private final State state;
    private final Ops ops;
    private final Proc proc;
    private final Diag diag;
    private final EnrProxy enr;
    private final Dash dash;
    private final Metrics metrics;
    private final WsStore store;
    private final Scanner scanner;

    private HttpServer http;
    private final CopyOnWriteArrayList<SseClient> sseClients = new CopyOnWriteArrayList<>();
    private final Map<Long, Long> opLastBroadcast = new ConcurrentHashMap<>();
    private final Map<Long, Integer> opLogSent = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Object>> gitCache = new ConcurrentHashMap<>();
    private final Map<String, Long> gitCacheTime = new ConcurrentHashMap<>();
    private long lastNotifCount = -1;
    private long lastSysSent = 0;
    private long lastMetricsSent = 0;
    private volatile Map<String, Object> portsInventoryCache;
    private volatile long portsInventoryCacheTime;
    private volatile Map<String, Object> ftestLatestCache;
    private volatile long ftestLatestCacheTime;
    private volatile boolean running = true;

    /**
     * Control-center HTTP server lifecycle. The server only reports READY once
     * it is actually bound and answers a real HTTP health check; RUNNING is
     * never reported merely because a startup method returned.
     */
    public enum Lifecycle { DISABLED, STOPPED, STARTING, RUNNING, READY, STOPPING, FAILED }

    private volatile Lifecycle lifecycle = Lifecycle.STOPPED;
    private volatile ExecutorService executor;
    private volatile Thread broadcastThread;
    private volatile int configuredPort = 0;   // port from configuration
    private volatile int allocatedPort = 0;    // port selected by the allocation policy
    private volatile int boundPort = 0;        // actually bound port (authoritative)
    private volatile String portCause = "";    // why a fallback was used, if any
    private volatile long startedAtMs = 0;
    private volatile long readyAtMs = 0;
    private volatile String lastError = "";

    private static final class SseClient {
        final OutputStream out;

        SseClient(OutputStream out) {
            this.out = out;
        }
    }

    public Server(App app, State state, Ops ops, Proc proc, Diag diag,
            EnrProxy enr, Dash dash, Metrics metrics, WsStore store) {
        this.app = app;
        this.state = state;
        this.ops = ops;
        this.proc = proc;
        this.diag = diag;
        this.enr = enr;
        this.dash = dash;
        this.metrics = metrics;
        this.store = store;
        this.scanner = new Scanner(store, Main.currentConfig.workspaceId);
    }

    /** Starts the control-center HTTP server (idempotent). */
    public synchronized void start() throws IOException {
        if (!app.webServerEnabled) {
            lifecycle = Lifecycle.DISABLED;
            Main.log("INFO", "[UPM][HTTP][DISABLED] webServer.enabled=false");
            return;
        }
        if (lifecycle == Lifecycle.READY || lifecycle == Lifecycle.RUNNING
                || lifecycle == Lifecycle.STARTING) {
            Main.log("INFO", "[UPM][HTTP][SKIP] already " + lifecycle + " port=" + boundPort);
            return;
        }
        lifecycle = Lifecycle.STARTING;
        startedAtMs = System.currentTimeMillis();
        configuredPort = app.port;
        Main.log("INFO", "[UPM][HTTP][STARTING] host=" + app.host + " port=" + app.port);

        PortSelection sel;
        try {
            sel = bindAuthoritative();
        } catch (IOException e) {
            lifecycle = Lifecycle.FAILED;
            lastError = e.getMessage();
            Main.log("ERROR", "[UPM][HTTP][FAILED] reason=" + e.getMessage());
            throw e;
        }
        allocatedPort = sel.port;
        boundPort = sel.port;
        portCause = sel.cause;
        Main.currentPort = boundPort;

        http.createContext("/api", this::api);
        http.createContext("/", this::staticOrIndex);
        executor = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "upm-http");
            t.setDaemon(true);
            return t;
        });
        http.setExecutor(executor);
        http.start();
        if (sel.fallback) {
            Main.log("WARN", "[UPM][HTTP][RUNNING] configured port " + configuredPort
                    + " unavailable (" + portCause + "); bound port=" + boundPort
                    + " (fallback +" + (boundPort - configuredPort) + ")");
        } else {
            Main.log("INFO", "[UPM][HTTP][RUNNING] bound host=" + app.host
                    + " port=" + boundPort);
        }

        if (verifyReady()) {
            lifecycle = Lifecycle.READY;
            readyAtMs = System.currentTimeMillis();
            Main.log("INFO", "[UPM][HTTP][READY] address=" + app.host + " port=" + boundPort
                    + " startupMs=" + (readyAtMs - startedAtMs));
        } else {
            lifecycle = Lifecycle.RUNNING;
            Main.log("WARN", "[UPM][HTTP][RUNNING] bound but readiness check failed on port="
                    + boundPort);
        }
        persistServerState();

        Thread b = new Thread(this::broadcastLoop, "upm-sse-broadcast");
        b.setDaemon(true);
        broadcastThread = b;
        b.start();
    }

    /** Stops the control-center HTTP server (idempotent). */
    public synchronized void stop() {
        if (lifecycle == Lifecycle.STOPPED || lifecycle == Lifecycle.DISABLED) {
            return;
        }
        Main.log("INFO", "[UPM][HTTP][STOPPING] port=" + boundPort);
        lifecycle = Lifecycle.STOPPING;
        running = false;
        if (http != null) {
            // Close the listening socket and stop accepting new requests.
            http.stop(0);
            http = null;
        }
        for (SseClient c : sseClients) {
            try {
                c.out.close();
            } catch (IOException ignored) {
                // closing
            }
        }
        sseClients.clear();
        Thread bt = broadcastThread;
        broadcastThread = null;
        if (bt != null && bt != Thread.currentThread()) {
            bt.interrupt();
            try {
                bt.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                    executor.awaitTermination(2, TimeUnit.SECONDS);
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        Main.currentPort = 0;
        lifecycle = Lifecycle.STOPPED;
        clearServerState();
        Main.log("INFO", "[UPM][HTTP][STOPPED] port=" + boundPort);
    }

    /** Graceful shutdown: stop the server, flush state, then exit the JVM. */
    public void shutdownGracefully() {
        stop();
        if (state != null) {
            state.persistNow();
        }
        System.exit(0);
    }

    public Lifecycle lifecycle() {
        return lifecycle;
    }

    public int boundPort() {
        return boundPort;
    }

    // ------------------------------------------------------ port allocation

    /** Result of the authoritative control-center port allocation. */
    private static final class PortSelection {
        final int port;
        final boolean fallback;
        final String cause;

        PortSelection(int port, boolean fallback, String cause) {
            this.port = port;
            this.fallback = fallback;
            this.cause = cause;
        }
    }

    /**
     * Authoritative control-center port allocation. Binds the configured port;
     * on conflict, classifies the cause (managed UPM instance vs external
     * process) and follows the configured fallback range. The actual socket
     * bind is the source of truth — a preliminary availability check is never
     * trusted on its own.
     */
    private PortSelection bindAuthoritative() throws IOException {
        IOException last = null;
        for (int i = 0; i <= app.portFallbackRange; i++) {
            int candidate = app.port + i;
            try {
                http = HttpServer.create(new InetSocketAddress(app.host, candidate), 128);
                return new PortSelection(candidate, i > 0,
                        i > 0 ? classifyOccupiedPort(app.port) : "");
            } catch (IOException e) {
                last = e;
            }
        }
        String cause = classifyOccupiedPort(app.port);
        throw new IOException("No free UPM port in range " + app.port + ".."
                + (app.port + app.portFallbackRange)
                + (cause.isEmpty() ? "" : "; configured port: " + cause), last);
    }

    /**
     * Classifies why a port is occupied: another UPM instance (managed) or an
     * unrelated process (external). Returns a human-readable cause.
     */
    private String classifyOccupiedPort(int port) {
        if (isUpmInstanceAt(port)) {
            return "occupied by another UPM instance";
        }
        String owner = Probe.ownerOf(port);
        if (owner != null && !owner.isBlank()) {
            return "occupied by external process (" + owner + ")";
        }
        return "occupied by an unknown process";
    }

    /** True if an HTTP endpoint on the given port answers as a UPM instance. */
    private boolean isUpmInstanceAt(int port) {
        String body = Probe.httpGet("http://" + app.host + ":" + port + "/api/health", 700);
        if (body == null) {
            return false;
        }
        return body.contains("\"app\":\"upm\"")
                || body.contains("\"app\":\"universal-project-manager\"");
    }

    /** Real self health-check: the server is READY only when it answers HTTP 200. */
    private boolean verifyReady() {
        String body = Probe.httpGet("http://" + app.host + ":" + boundPort + "/api/health", 2500);
        return body != null && body.contains("\"status\":\"ok\"");
    }

    // --------------------------------------------------- runtime persistence

    private Path serverStateFile() {
        return app.runtimeDir.resolve("upm").resolve("server.json");
    }

    private void persistServerState() {
        try {
            Files.createDirectories(serverStateFile().getParent());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("pid", Main.selfPid);
            m.put("host", app.host);
            m.put("configuredPort", configuredPort);
            m.put("allocatedPort", allocatedPort);
            m.put("boundPort", boundPort);
            m.put("state", lifecycle.name());
            m.put("startedAtMs", startedAtMs);
            m.put("readyAtMs", readyAtMs);
            m.put("cause", portCause);
            m.put("version", app.version);
            Files.writeString(serverStateFile(), Json.write(m));
        } catch (IOException e) {
            Main.log("WARN", "[UPM][HTTP] could not persist server state: " + e.getMessage());
        }
    }

    private void clearServerState() {
        try {
            Files.deleteIfExists(serverStateFile());
        } catch (IOException ignored) {
            // best effort
        }
    }

    /** Full server lifecycle status (used by /api/server and diagnostics). */
    private Map<String, Object> serverStatusJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", lifecycle.name());
        m.put("pid", Main.selfPid);
        m.put("host", app.host);
        m.put("configuredPort", configuredPort);
        m.put("allocatedPort", allocatedPort);
        m.put("boundPort", boundPort);
        m.put("port", Main.currentPort);
        m.put("cause", portCause);
        m.put("startedAtMs", startedAtMs);
        m.put("readyAtMs", readyAtMs);
        m.put("startupMs", readyAtMs > 0 ? readyAtMs - startedAtMs : 0);
        m.put("version", app.version);
        m.put("webServerEnabled", app.webServerEnabled);
        m.put("webServerAutoStart", app.webServerAutoStart);
        m.put("lastError", lastError);
        return m;
    }

    // ------------------------------------------------------------- routing

    private void api(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        try {
            if ("/api/health".equals(path)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "ok");
                m.put("app", "upm");
                m.put("name", app.name);
                m.put("version", app.version);
                m.put("state", lifecycle.name());
                m.put("configuredPort", configuredPort);
                m.put("port", Main.currentPort);
                json(ex, 200, m);
            } else if ("/api/server".equals(path)) {
                json(ex, 200, serverStatusJson());
            } else if ("/api/events".equals(path)) {
                sse(ex);
            } else if ("/api/state".equals(path)) {
                json(ex, 200, snapshot());
            } else if ("/api/workspaces".equals(path)) {
                workspacesRoute(ex, method, "");
            } else if (path.startsWith("/api/workspaces/")) {
                workspacesRoute(ex, method, path.substring("/api/workspaces/".length()));
            } else if ("/api/import".equals(path)) {
                importRoute(ex, method);
            } else if ("/api/backups".equals(path)) {
                backupsRoute(ex, method, "");
            } else if (path.startsWith("/api/backups/")) {
                backupsRoute(ex, method, path.substring("/api/backups/".length()));
            } else if ("/api/projects".equals(path)) {
                projectsRoute(ex, method, "");
            } else if (path.startsWith("/api/projects/")) {
                projectsRoute(ex, method, path.substring("/api/projects/".length()));
            } else if ("/api/detect".equals(path)) {
                detectRoute(ex, method);
            } else if ("/api/profiles".equals(path)) {
                profilesRoute(ex, method, "");
            } else if (path.startsWith("/api/profiles/")) {
                profilesRoute(ex, method, path.substring("/api/profiles/".length()));
            } else if ("/api/diagnostics".equals(path)) {
                json(ex, 200, diag.workspace());
            } else if ("/api/ops".equals(path)) {
                opsRoute(ex, method, "");
            } else if (path.startsWith("/api/ops/")) {
                opsRoute(ex, method, path.substring("/api/ops/".length()));
            } else if ("/api/system".equals(path)) {
                json(ex, 200, systemJson());
            } else if (path.startsWith("/api/enr")) {
                enrRoute(ex, method, path.substring("/api/enr".length()));
            } else if (path.equals("/api/ftests") || path.startsWith("/api/ftests/")) {
                funcTestsRoute(ex, method, path.substring("/api/ftests".length()));
            } else if ("/api/dashboard".equals(path)) {
                dashboardRoute(ex, method);
            } else if ("/api/dashboard/reset".equals(path)) {
                dash.reset(Main.currentConfig.workspaceId);
                json(ex, 200, dash.load(Main.currentConfig.workspaceId, Main.currentConfig.dashboard));
            } else if ("/api/settings".equals(path)) {
                settingsRoute(ex, method);
            } else if ("/api/discover".equals(path)) {
                discoverRoute(ex, method);
            } else if (path.startsWith("/api/graph")) {
                graphRoute(ex, method, path.substring("/api/graph".length()));
            } else if ("/api/metrics/current".equals(path)) {
                json(ex, 200, metricsCurrent());
            } else if (path.startsWith("/api/metrics/history")) {
                metricsHistoryRoute(ex);
            } else if ("/api/metrics/config".equals(path)) {
                metricsConfigRoute(ex, method);
            } else if ("/api/llm".equals(path)) {
                json(ex, 200, llmJson());
            } else if ("/api/tools".equals(path)) {
                json(ex, 200, Adapters.detectTools());
            } else if ("/api/adapters".equals(path)) {
                json(ex, 200, Adapters.registryJson());
            } else if ("/api/ports/inventory".equals(path)) {
                portsInventoryRoute(ex, method);
            } else if ("/api/ports".equals(path)) {
                json(ex, 200, portsJson());
            } else if ("/api/report".equals(path)) {
                json(ex, 200, reportJson());
            } else if ("/api/shutdown".equals(path) && "POST".equals(method)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("status", "stopping");
                json(ex, 200, m);
                Thread t = new Thread(() -> {
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ignored) {
                        // go
                    }
                    shutdownGracefully();
                }, "upm-shutdown");
                t.setDaemon(true);
                t.start();
            } else {
                json(ex, 404, err("Unknown API path: " + path));
            }
        } catch (Exception e) {
            Main.log("ERROR", "API error on " + path + ": " + e.getMessage());
            e.printStackTrace();
            json(ex, 500, err("Internal error: " + e.getMessage()));
        }
    }

    // ---------------------------------------------------------- workspaces

    private void workspacesRoute(HttpExchange ex, String method, String rest) throws IOException {
        String id = rest.split("/")[0];
        String sub = rest.contains("/") ? rest.substring(id.length()) : "";
        if (id.isEmpty()) {
            if ("GET".equals(method)) {
                List<Map<String, Object>> list = store.list();
                for (Map<String, Object> m : list) {
                    m.put("current", Main.currentConfig != null
                            && Main.currentConfig.workspaceId.equals(m.get("id")));
                }
                json(ex, 200, list);
            } else if ("POST".equals(method)) {
                Map<String, Object> body = readBody(ex);
                String wid = Json.str(body, "id");
                if (wid == null || wid.isBlank()) {
                    json(ex, 400, err("Workspace id required"));
                    return;
                }
                try {
                    Config c = store.create(wid, Json.str(body, "name", wid),
                            Json.str(body, "description", ""));
                    c.dashboard = Dash.defaultLayoutMap();
                    c.save();
                    json(ex, 200, Map.of("ok", true, "id", wid));
                } catch (Exception e) {
                    json(ex, 409, err(e.getMessage()));
                }
            } else {
                json(ex, 405, err("Method not allowed"));
            }
            return;
        }
        switch (sub) {
            case "/switch" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                try {
                    Main.switchWorkspace(id);
                    json(ex, 200, Map.of("ok", true, "workspace", Main.currentConfig.workspaceId));
                } catch (Exception e) {
                    json(ex, 400, err(e.getMessage()));
                }
            }
            case "/rename" -> {
                if (!"PUT".equals(method)) {
                    json(ex, 405, err("PUT required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                try {
                    Config c = store.load(id);
                    c.workspaceName = Json.str(body, "name", c.workspaceName);
                    c.workspaceDescription = Json.str(body, "description", c.workspaceDescription);
                    c.save();
                    json(ex, 200, Map.of("ok", true));
                } catch (Exception e) {
                    json(ex, 400, err(e.getMessage()));
                }
            }
            case "/duplicate" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                String target = Json.str(body, "targetId");
                try {
                    byte[] zip = store.export(id);
                    String newId = store.applyImport(zip, target, Map.of());
                    json(ex, 200, Map.of("ok", true, "id", newId));
                } catch (Exception e) {
                    json(ex, 400, err(e.getMessage()));
                }
            }
            case "/export" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                try {
                    byte[] zip = store.export(id);
                    ex.getResponseHeaders().set("Content-Type", "application/zip");
                    ex.getResponseHeaders().set("Content-Disposition",
                            "attachment; filename=workspace-" + id + ".zip");
                    ex.sendResponseHeaders(200, zip.length);
                    try (OutputStream os = ex.getResponseBody()) {
                        os.write(zip);
                    }
                } catch (Exception e) {
                    json(ex, 400, err(e.getMessage()));
                }
            }
            case "/delete" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                boolean archive = Json.bool(body, "archive", true);
                if (Main.currentConfig != null && Main.currentConfig.workspaceId.equals(id)) {
                    json(ex, 409, err("Cannot delete the active workspace. Switch first."));
                    return;
                }
                try {
                    store.deleteConfig(id, archive);
                    json(ex, 200, Map.of("ok", true));
                } catch (Exception e) {
                    json(ex, 400, err(e.getMessage()));
                }
            }
            case "/backup" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                try {
                    String name = store.backup(id);
                    json(ex, 200, Map.of("ok", true, "backup", name));
                } catch (Exception e) {
                    json(ex, 400, err(e.getMessage()));
                }
            }
            default -> json(ex, 404, err("Unknown workspace path: " + sub));
        }
    }

    private void importRoute(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            json(ex, 405, err("POST required"));
            return;
        }
        Map<String, Object> body = readBody(ex);
        String zipB64 = Json.str(body, "zipBase64");
        if (zipB64 == null || zipB64.isBlank()) {
            json(ex, 400, err("zipBase64 required (exported workspace package)"));
            return;
        }
        byte[] zip = Base64.getDecoder().decode(zipB64);
        boolean previewOnly = Json.bool(body, "preview", false);
        if (previewOnly) {
            Map<String, Object> preview = store.previewImport(zip);
            preview.put("existingWorkspaceIds",
                    store.list().stream().map(m -> m.get("id")).toList());
            json(ex, 200, preview);
            return;
        }
        try {
            String newId = store.applyImport(zip, Json.str(body, "workspaceId"),
                    strMapField(body, "pathRemaps"));
            json(ex, 200, Map.of("ok", true, "workspaceId", newId));
        } catch (Exception e) {
            json(ex, 400, err(e.getMessage()));
        }
    }

    private void backupsRoute(HttpExchange ex, String method, String rest) throws IOException {
        if ("GET".equals(method)) {
            json(ex, 200, store.listBackups());
        } else if ("POST".equals(method) && rest.isEmpty()) {
            Map<String, Object> body = readBody(ex);
            try {
                String wsId = Json.str(body, "workspaceId", Main.currentConfig.workspaceId);
                String name = store.backup(wsId);
                json(ex, 200, Map.of("ok", true, "backup", name));
            } catch (Exception e) {
                json(ex, 400, err(e.getMessage()));
            }
        } else if ("POST".equals(method) && rest.endsWith("/restore")) {
            String backupName = rest.substring(0, rest.length() - "/restore".length());
            Map<String, Object> body = readBody(ex);
            String wsId = Json.str(body, "workspaceId", Main.currentConfig.workspaceId);
            try {
                store.restore(backupName, wsId);
                Main.switchWorkspace(wsId);
                json(ex, 200, Map.of("ok", true));
            } catch (Exception e) {
                json(ex, 400, err(e.getMessage()));
            }
        } else {
            json(ex, 405, err("Method not allowed"));
        }
    }

    private static Map<String, String> strMapField(Map<String, Object> body, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> m = Json.objField(body, key);
        if (m != null) {
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (e.getValue() instanceof String s) {
                    out.put(e.getKey(), s);
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ projects

    private void projectsRoute(HttpExchange ex, String method, String rest) throws IOException {
        if (rest.isEmpty()) {
            if ("GET".equals(method)) {
                json(ex, 200, projectsJson(true));
            } else if ("POST".equals(method)) {
                addProject(ex);
            } else {
                json(ex, 405, err("Method not allowed"));
            }
            return;
        }
        if (rest.equals("clone")) {
            cloneProject(ex, method);
            return;
        }
        String[] parts = rest.split("/", 2);
        String id = parts[0];
        Config.Project p = Main.currentConfig.project(id);
        if (p == null) {
            json(ex, 404, err("Unknown project: " + id));
            return;
        }
        String sub = parts.length > 1 ? "/" + parts[1] : "";
        switch (sub) {
            case "" -> {
                if ("GET".equals(method)) {
                    json(ex, 200, projectJson(p, true));
                } else if ("PUT".equals(method)) {
                    editProject(ex, p);
                } else {
                    json(ex, 405, err("Method not allowed"));
                }
            }
            case "/unregister" -> {
                if ("POST".equals(method)) {
                    unregister(p);
                    json(ex, 200, Map.of("ok", true));
                } else {
                    json(ex, 405, err("Method not allowed"));
                }
            }
            case "/delete-repo" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                boolean confirm = Json.bool(body, "confirm", false);
                json(ex, 200, deleteRepo(p, confirm));
            }
            case "/duplicate" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                json(ex, 200, duplicateProject(p, body));
            }
            case "/diagnostics" -> json(ex, 200, diag.project(id));
            case "/logs" -> json(ex, 200, runtimeLogJson(p));
            case "/structure" -> {
                if (!"GET".equals(method)) {
                    json(ex, 405, err("GET required"));
                    return;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("project", id);
                m.put("live", Adapters.detectStructure(Path.of(p.repoDir), p.type));
                Map<String, Object> snapshot = new LinkedHashMap<>();
                snapshot.put("parentPom", p.parentPom);
                snapshot.put("modules", p.modules);
                snapshot.put("features", p.features);
                snapshot.put("products", p.products);
                snapshot.put("targetPlatforms", p.targetPlatforms);
                snapshot.put("eclipseLaunchConfigs", p.eclipseLaunchConfigs);
                snapshot.put("requiresJava", p.requiresJava);
                snapshot.put("structureAt", p.structureAt);
                m.put("persisted", snapshot);
                m.put("adapterType", p.type);
                m.put("operations", new ArrayList<>(p.operations.keySet()));
                List<String> lcIds = new ArrayList<>();
                for (Config.LaunchConfig lc : p.launchConfigs) {
                    lcIds.add(lc.id);
                }
                m.put("launchConfigs", lcIds);
                json(ex, 200, m);
            }
            case "/refresh" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Adapters.DetectionResult d = Adapters.detect(Path.of(p.repoDir));
                Map<String, Object> structure = Adapters.detectStructure(Path.of(p.repoDir), d.typeId);
                List<String> modules = new ArrayList<>();
                for (Map<String, Object> mi : Json.objList(structure, "modules")) {
                    modules.add(Json.str(mi, "name"));
                }
                // Merge in newly proposed standard operations without touching
                // any user-customized operation commands; replace operations
                // whose embedded paths clearly point at a previous repository
                // location.
                Map<String, Config.CommandBlock> mergedOps = new LinkedHashMap<>(p.operations);
                Map<String, String> propEnv = new LinkedHashMap<>();
                String jdk = String.valueOf(Adapters.detectTools().getOrDefault("java21", ""));
                if (!jdk.isEmpty()) {
                    propEnv.put("JAVA_HOME", jdk);
                }
                String currentRepo = Path.of(p.repoDir).toAbsolutePath().toString();
                String parentRepo = Path.of(p.repoDir).toAbsolutePath().getParent() == null
                        ? "" : Path.of(p.repoDir).toAbsolutePath().getParent().toString();
                for (Map.Entry<String, Config.CommandBlock> e : Adapters.get(d.typeId)
                        .proposeOperations(Path.of(p.repoDir), propEnv).entrySet()) {
                    Config.CommandBlock old = mergedOps.get(e.getKey());
                    boolean stalePath = old != null && !parentRepo.isEmpty()
                            && String.join(" ", old.cmd).contains(parentRepo)
                            && !String.join(" ", old.cmd).contains(currentRepo);
                    boolean staleMvnw = old != null
                            && String.join(" ", old.cmd).contains("./mvnw")
                            && !Files.exists(Path.of(p.repoDir, "mvnw"));
                    if (old == null || stalePath || staleMvnw) {
                        mergedOps.put(e.getKey(), e.getValue());
                    }
                }
                Config.Project updated = new Config.Project(
                        p.id, p.name, p.description, p.repoDir, p.repoUrl, d.typeId,
                        d.confidence, "refresh", d.evidence, p.git, p.startupAfter,
                        p.logLocations, p.configLocations, p.modelDirectories, p.apiBase,
                        p.enrMonitor, p.warnings, mergedOps, p.testSuites,
                        p.testContinueOnFailure, p.launchConfigs,
                        Json.str(structure, "parentPom", ""),
                        modules,
                        Json.strList(structure, "features"),
                        Json.strList(structure, "products"),
                        Json.strList(structure, "targetPlatforms"),
                        Json.strList(structure, "eclipseLaunchConfigs"),
                        Json.str(structure, "requiresJava", ""),
                        System.currentTimeMillis());
                synchronized (Main.currentConfig) {
                    for (int i = 0; i < Main.currentConfig.projects.size(); i++) {
                        if (Main.currentConfig.projects.get(i).id.equals(p.id)) {
                            Main.currentConfig.projects.set(i, updated);
                            break;
                        }
                    }
                    try {
                        Main.currentConfig.save();
                    } catch (IOException e) {
                        json(ex, 500, err("Could not save workspace: " + e.getMessage()));
                        return;
                    }
                }
                json(ex, 200, Map.of("ok", true, "type", d.typeId,
                        "confidence", d.confidence, "structure", structure));
            }
            case "/stop" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                boolean external = Json.bool(body, "external", false);
                State.ProjectState ps = state.stateFor(id);
                String err = external || ps.runtimeState.equals("EXTERNAL")
                        ? proc.stopExternal(id) : proc.stop(id, null);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", err == null);
                if (err != null) {
                    m.put("error", err);
                }
                json(ex, 200, m);
            }
            case "/restart" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                json(ex, 200, Json.obj(Json.parse(ops.submit(id, "restart",
                        Json.str(body, "launchConfig"), null, Map.of()))));
            }
            case "/git" -> gitRoute(ex, method, p);
            case "/git-switch" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                String branch = Json.str(body, "branch");
                if (branch == null) {
                    json(ex, 400, err("branch required"));
                    return;
                }
                Map<String, Object> g = GitOps.status(Path.of(p.repoDir));
                if (Boolean.TRUE.equals(g.get("dirty"))) {
                    json(ex, 409, err("Uncommitted changes present; switching refused."));
                    return;
                }
                GitOps.Result r = GitOps.switchBranch(Path.of(p.repoDir), branch);
                if (!r.ok()) {
                    json(ex, 400, err(r.error() + "\n" + r.output()));
                    return;
                }
                state.notify("info", p.id, "Branch switched", "now on " + branch, "");
                json(ex, 200, Map.of("ok", true, "branch", branch));
            }
            case "/launchconfigs" -> launchConfigsRoute(ex, method, p, "");
            default -> {
                if (sub.startsWith("/launchconfigs/")) {
                    launchConfigsRoute(ex, method, p,
                            sub.substring("/launchconfigs/".length()));
                } else {
                    json(ex, 404, err("Unknown project path: " + sub));
                }
            }
        }
    }

    /** Clones a repository into the selected destination (no overwrites). */
    private void cloneProject(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            json(ex, 405, err("POST required"));
            return;
        }
        Map<String, Object> body = readBody(ex);
        String url = Json.str(body, "url");
        String destDir = App.expandHome(Json.str(body, "destDir"));
        if (url == null || !GitOps.validUrl(url)) {
            json(ex, 400, err("Invalid git URL"));
            return;
        }
        if (destDir == null || destDir.isBlank()) {
            json(ex, 400, err("Destination directory required"));
            return;
        }
        Path dest = Path.of(destDir);
        if (Files.exists(dest)) {
            json(ex, 409, err("Destination already exists: " + dest
                    + " (never overwriting an existing directory)"));
            return;
        }
        // Clone into a temporary sibling, then move into place (atomic, no
        // partial clones left behind at the destination).
        Path parent = dest.toAbsolutePath().getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            json(ex, 400, err("Destination parent does not exist: " + parent));
            return;
        }
        Path tmp = parent.resolve(dest.getFileName().toString() + ".upm-clone-tmp");
        if (Files.exists(tmp)) {
            json(ex, 409, err("Temporary clone directory already exists: " + tmp));
            return;
        }
        GitOps.Result r = GitOps.clone(url, tmp);
        if (!r.ok()) {
            json(ex, 400, err("Clone failed: " + r.error() + "\n" + r.output()));
            return;
        }
        try {
            Files.move(tmp, dest);
        } catch (IOException e) {
            json(ex, 500, err("Could not move clone into place: " + e.getMessage()));
            return;
        }
        Adapters.DetectionResult detection = Adapters.detect(dest);
        Map<String, Object> tools = Adapters.detectTools();
        Map<String, String> env = new LinkedHashMap<>();
        String java21 = String.valueOf(tools.getOrDefault("java21", ""));
        if (!java21.isEmpty()) {
            env.put("JAVA_HOME", java21);
        }
        Map<String, Config.CommandBlock> ops = Adapters.get(detection.typeId)
                .proposeOperations(dest, env);
        Map<String, Object> opsJson = new LinkedHashMap<>();
        for (Map.Entry<String, Config.CommandBlock> e : ops.entrySet()) {
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("cmd", e.getValue().cmd);
            b.put("env", e.getValue().env);
            b.put("timeoutMinutes", e.getValue().timeoutMs / 60000);
            opsJson.put(e.getKey(), b);
        }
        state.notify("success", "workspace", "Repository cloned", dest.toString(), "");
        json(ex, 200, Map.of("ok", true, "destDir", dest.toString(),
                "detection", detection.toJson(), "proposedOperations", opsJson));
    }

    private void addProject(HttpExchange ex) throws IOException {
        Map<String, Object> body = readBody(ex);
        Config cfg = Main.currentConfig;
        String id = Json.str(body, "id");
        if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            json(ex, 400, err("Valid project id required"));
            return;
        }
        if (cfg.project(id) != null) {
            json(ex, 409, err("Project id already registered: " + id));
            return;
        }
        String repoDir = App.expandHome(Json.str(body, "repoDir"));
        if (repoDir == null || !Files.isDirectory(Path.of(repoDir))) {
            json(ex, 400, err("Repository directory does not exist: " + repoDir));
            return;
        }
        // Re-detect to verify the proposal against reality.
        Adapters.DetectionResult detection = Adapters.detect(Path.of(repoDir));
        String type = Json.str(body, "type");
        if (type == null) {
            type = detection.typeId;
        }
        if (!Adapters.exists(type)) {
            json(ex, 400, err("Unknown project type: " + type));
            return;
        }
        String confidence = Json.str(body, "detectionConfidence", "USER_CONFIRMED");
        Map<String, Config.CommandBlock> operations = operationsFrom(body);
        if (operations.isEmpty()) {
            String jdk = String.valueOf(Adapters.detectTools().getOrDefault("java21", ""));
            Map<String, String> env = new LinkedHashMap<>();
            if (!jdk.isEmpty()) {
                env.put("JAVA_HOME", jdk);
            }
            operations = Adapters.get(type).proposeOperations(Path.of(repoDir), env);
        }
        List<Config.TestSuite> suites = testSuitesFrom(body);
        boolean continueOnFailure = Json.bool(Json.objField(body, "test"), "continueOnFailure", false);
        List<Config.LaunchConfig> lcs = launchConfigsFrom(body);
        if (lcs.isEmpty()) {
            lcs = detection.proposedLaunchConfigs;
        }
        Map<String, Object> structure = Adapters.detectStructure(Path.of(repoDir), type);
        List<String> moduleNames = new ArrayList<>();
        for (Map<String, Object> mi : Json.objList(structure, "modules")) {
            moduleNames.add(Json.str(mi, "name"));
        }
        Config.Project project = new Config.Project(
                id,
                Json.str(body, "name", id),
                Json.str(body, "description", ""),
                repoDir,
                Json.str(body, "repoUrl", ""),
                type,
                confidence,
                "add-project-wizard",
                detection.evidence,
                new LinkedHashMap<>(),
                Json.strList(body, "startupAfter"),
                List.of(), List.of(), List.of(),
                Json.str(body, "apiBase", ""),
                Json.bool(body, "enrMonitor", false),
                Json.strList(body, "warnings"),
                operations,
                suites,
                continueOnFailure,
                lcs,
                Json.str(structure, "parentPom", ""),
                moduleNames,
                Json.strList(structure, "features"),
                Json.strList(structure, "products"),
                Json.strList(structure, "targetPlatforms"),
                Json.strList(structure, "eclipseLaunchConfigs"),
                Json.str(structure, "requiresJava", ""),
                System.currentTimeMillis());
        List<String> errors = new ArrayList<>();
        for (Config.LaunchConfig lc : project.launchConfigs) {
            if (lc.launcher == null || lc.launcher.isBlank()) {
                errors.add("Launch config " + lc.id + " has no launcher");
            }
        }
        if (!errors.isEmpty()) {
            json(ex, 400, err(String.join("; ", errors)));
            return;
        }
        synchronized (cfg) {
            cfg.projects.add(project);
            try {
                cfg.save();
            } catch (IOException e) {
                cfg.projects.remove(project);
                json(ex, 500, err("Could not save workspace: " + e.getMessage()));
                return;
            }
        }
        state.stateFor(id);
        state.notify("success", id, "Project registered",
                "type " + type + ", confidence " + confidence, "");
        json(ex, 200, Map.of("ok", true, "id", id, "detection", detection.toJson()));
    }

    private void editProject(HttpExchange ex, Config.Project p) throws IOException {
        Map<String, Object> body = readBody(ex);
        Config cfg = Main.currentConfig;
        synchronized (cfg) {
            int idx = -1;
            for (int i = 0; i < cfg.projects.size(); i++) {
                if (cfg.projects.get(i).id.equals(p.id)) {
                    idx = i;
                    break;
                }
            }
            Config.Project updated = projectFromEdit(p, body);
            List<String> errors = validateProjectUpdate(cfg, updated);
            if (!errors.isEmpty()) {
                json(ex, 400, err(String.join("; ", errors)));
                return;
            }
            cfg.projects.set(idx, updated);
            try {
                cfg.save();
            } catch (IOException e) {
                json(ex, 500, err("Could not save workspace: " + e.getMessage()));
                return;
            }
        }
        json(ex, 200, Map.of("ok", true));
    }

    private Config.Project projectFromEdit(Config.Project p, Map<String, Object> body) {
        String repoDir = body.containsKey("repoDir")
                ? App.expandHome(Json.str(body, "repoDir")) : p.repoDir;
        String type = body.containsKey("type") ? Json.str(body, "type") : p.type;
        Map<String, Config.CommandBlock> operations = body.containsKey("operations")
                ? operationsFrom(body) : p.operations;
        List<Config.TestSuite> suites = body.containsKey("test")
                ? testSuitesFrom(body) : p.testSuites;
        boolean continueOnFailure = body.containsKey("test")
                ? Json.bool(Json.objField(body, "test"), "continueOnFailure", p.testContinueOnFailure)
                : p.testContinueOnFailure;
        List<Config.LaunchConfig> lcs = body.containsKey("launchConfigs")
                ? launchConfigsFrom(body) : p.launchConfigs;
        if (lcs.isEmpty()) {
            lcs = p.launchConfigs;
        }
        return new Config.Project(
                p.id,
                Json.str(body, "name", p.name),
                Json.str(body, "description", p.description),
                repoDir,
                Json.str(body, "repoUrl", p.repoUrl),
                type,
                Json.str(body, "detectionConfidence", p.detectionConfidence),
                Json.str(body, "detectionSource", p.detectionSource),
                p.detectionEvidence,
                p.git,
                body.containsKey("startupAfter") ? Json.strList(body, "startupAfter") : p.startupAfter,
                p.logLocations,
                body.containsKey("configLocations") ? Json.strList(body, "configLocations") : p.configLocations,
                p.modelDirectories,
                body.containsKey("apiBase") ? Json.str(body, "apiBase", p.apiBase) : p.apiBase,
                body.containsKey("enrMonitor") ? Json.bool(body, "enrMonitor", p.enrMonitor) : p.enrMonitor,
                body.containsKey("warnings") ? Json.strList(body, "warnings") : p.warnings,
                operations,
                suites,
                continueOnFailure,
                lcs,
                p.parentPom,
                p.modules,
                p.features,
                p.products,
                p.targetPlatforms,
                p.eclipseLaunchConfigs,
                p.requiresJava,
                p.structureAt);
    }

    private List<String> validateProjectUpdate(Config cfg, Config.Project updated) {
        List<String> errors = new ArrayList<>();
        if (updated.repoDir == null || !Files.isDirectory(Path.of(updated.repoDir))) {
            errors.add("Repository directory does not exist: " + updated.repoDir);
        }
        if (!Adapters.exists(updated.type)) {
            errors.add("Unknown project type: " + updated.type);
        }
        List<String> otherIds = new ArrayList<>();
        for (Config.Project p : cfg.projects) {
            if (!p.id.equals(updated.id)) {
                otherIds.add(p.id);
            }
        }
        for (String dep : updated.startupAfter) {
            if (!otherIds.contains(dep) && !dep.equals(updated.id)) {
                errors.add("Dependency references unknown project: " + dep);
            }
        }
        for (Config.LaunchConfig lc : updated.launchConfigs) {
            if (lc.launcher == null || lc.launcher.isBlank()) {
                errors.add("Launch config " + lc.id + " has no launcher");
            }
        }
        return errors;
    }

    private void unregister(Config.Project p) {
        Config cfg = Main.currentConfig;
        synchronized (cfg) {
            cfg.projects.removeIf(x -> x.id.equals(p.id));
            try {
                cfg.save();
            } catch (IOException e) {
                Main.log("ERROR", "unregister save failed: " + e.getMessage());
            }
        }
        state.notify("info", p.id, "Project unregistered",
                "The repository at " + p.repoDir + " was NOT deleted.", "");
    }

    /** Deletes the local repository directory after explicit confirmation. */
    private Map<String, Object> deleteRepo(Config.Project p, boolean confirm) {
        if (!confirm) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("confirmationRequired", true);
            m.put("path", p.repoDir);
            return m;
        }
        Path dir = Path.of(p.repoDir);
        if (!Files.isDirectory(dir)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "Directory not found: " + dir);
            return m;
        }
        // Safety: refuse to delete a directory that does not look like a
        // standalone repository root (protects against misconfiguration).
        String abs = dir.toAbsolutePath().toString();
        if (abs.equals("/") || abs.equals(System.getProperty("user.home"))
                || abs.equals(Path.of(System.getProperty("user.home"), "git").toString())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "Refusing to delete a protected path: " + abs);
            return m;
        }
        if (!Files.isDirectory(dir.resolve(".git")) && !Files.isRegularFile(dir.resolve("pom.xml"))
                && !Files.isRegularFile(dir.resolve("build.gradle"))
                && !Files.isRegularFile(dir.resolve("CMakeLists.txt"))
                && !Files.isRegularFile(dir.resolve("package.json"))
                && !Files.isRegularFile(dir.resolve("README.md"))) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "Refusing to delete '" + abs
                    + "': it does not look like a project repository.");
            return m;
        }
        // Stop any runtime first.
        State.ProjectState ps = state.projects.get(p.id);
        if (ps != null && !ps.runtimeState.equals("STOPPED")) {
            proc.stop(p.id, null);
        }
        unregister(p);
        try {
            WsStore.deleteTree(dir);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", true);
            m.put("deleted", abs);
            return m;
        } catch (IOException e) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "Deletion failed: " + e.getMessage());
            return m;
        }
    }

    private Map<String, Object> duplicateProject(Config.Project p, Map<String, Object> body) {
        Config cfg = Main.currentConfig;
        String newId = Json.str(body, "newId");
        if (newId == null) {
            newId = p.id + "-copy";
        }
        if (cfg.project(newId) != null) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", false);
            m.put("error", "Project id already exists: " + newId);
            return m;
        }
        String newRepo = Json.str(body, "repoDir");
        List<Config.LaunchConfig> lcs = new ArrayList<>();
        for (Config.LaunchConfig lc : p.launchConfigs) {
            lcs.add(lc);
        }
        Config.Project copy = new Config.Project(
                newId,
                Json.str(body, "name", p.name + " (copy)"),
                p.description,
                newRepo != null ? App.expandHome(newRepo) : p.repoDir,
                p.repoUrl,
                p.type,
                "USER_CONFIRMED",
                "duplicate-of-" + p.id,
                p.detectionEvidence,
                new LinkedHashMap<>(),
                new ArrayList<>(p.startupAfter),
                new ArrayList<>(p.logLocations),
                new ArrayList<>(p.configLocations),
                new ArrayList<>(p.modelDirectories),
                p.apiBase,
                p.enrMonitor,
                new ArrayList<>(p.warnings),
                new LinkedHashMap<>(p.operations),
                new ArrayList<>(p.testSuites),
                p.testContinueOnFailure,
                lcs);
        synchronized (cfg) {
            cfg.projects.add(copy);
            try {
                cfg.save();
            } catch (IOException e) {
                cfg.projects.remove(copy);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("ok", false);
                m.put("error", "Could not save workspace: " + e.getMessage());
                return m;
            }
        }
        state.stateFor(newId);
        return Map.of("ok", true, "id", newId);
    }

    private void launchConfigsRoute(HttpExchange ex, String method, Config.Project p, String rest)
            throws IOException {
        Config cfg = Main.currentConfig;
        if (rest.isEmpty()) {
            if ("POST".equals(method)) {
                Map<String, Object> body = readBody(ex);
                List<Config.LaunchConfig> list = new ArrayList<>(p.launchConfigs);
                Config.LaunchConfig lc = launchConfigFromMap(body, list.isEmpty());
                list.add(lc);
                replaceLaunchConfigs(cfg, p, list);
                json(ex, 200, Map.of("ok", true));
            } else {
                json(ex, 405, err("Method not allowed"));
            }
            return;
        }
        String lcId = rest.split("/")[0];
        String action = rest.contains("/") ? rest.substring(lcId.length()) : "";
        switch (action) {
            case "/delete" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                List<Config.LaunchConfig> list = new ArrayList<>();
                for (Config.LaunchConfig lc : p.launchConfigs) {
                    if (!lc.id.equals(lcId)) {
                        list.add(lc);
                    }
                }
                replaceLaunchConfigs(cfg, p, list);
                json(ex, 200, Map.of("ok", true));
            }
            case "/duplicate" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                Config.LaunchConfig src = p.launch(lcId);
                if (src == null) {
                    json(ex, 404, err("Launch config not found"));
                    return;
                }
                List<Config.LaunchConfig> list = new ArrayList<>(p.launchConfigs);
                String newId = lcId + "-copy";
                int n = 2;
                while (p.launch(newId) != null) {
                    newId = lcId + "-copy-" + n++;
                }
                list.add(new Config.LaunchConfig(newId, src.name + " (copy)", src.kind,
                        src.launcher, new ArrayList<>(src.args), new LinkedHashMap<>(src.env),
                        src.workDir, src.port, src.portFixed, src.requiresDisplay, src.readiness,
                        new ArrayList<>(src.readyMarkers), new ArrayList<>(src.shutdownMarkers),
                        src.stopGraceSeconds, src.startupTimeoutMs, false));
                replaceLaunchConfigs(cfg, p, list);
                json(ex, 200, Map.of("ok", true));
            }
            case "/default" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                List<Config.LaunchConfig> list = new ArrayList<>();
                for (Config.LaunchConfig lc : p.launchConfigs) {
                    list.add(new Config.LaunchConfig(lc.id, lc.name, lc.kind, lc.launcher,
                            new ArrayList<>(lc.args), new LinkedHashMap<>(lc.env), lc.workDir,
                            lc.port, lc.portFixed, lc.requiresDisplay, lc.readiness,
                            new ArrayList<>(lc.readyMarkers), new ArrayList<>(lc.shutdownMarkers),
                            lc.stopGraceSeconds, lc.startupTimeoutMs, lc.id.equals(lcId)));
                }
                replaceLaunchConfigs(cfg, p, list);
                json(ex, 200, Map.of("ok", true));
            }
            default -> json(ex, 404, err("Unknown launch config path: " + action));
        }
    }

    private void replaceLaunchConfigs(Config cfg, Config.Project p, List<Config.LaunchConfig> list) {
        synchronized (cfg) {
            int idx = -1;
            for (int i = 0; i < cfg.projects.size(); i++) {
                if (cfg.projects.get(i).id.equals(p.id)) {
                    idx = i;
                    break;
                }
            }
            Config.Project old = cfg.projects.get(idx);
            cfg.projects.set(idx, new Config.Project(old.id, old.name, old.description,
                    old.repoDir, old.repoUrl, old.type, old.detectionConfidence,
                    old.detectionSource, old.detectionEvidence, old.git, old.startupAfter,
                    old.logLocations, old.configLocations, old.modelDirectories, old.apiBase,
                    old.enrMonitor, old.warnings, old.operations, old.testSuites,
                    old.testContinueOnFailure, list));
            try {
                cfg.save();
            } catch (IOException e) {
                Main.log("ERROR", "launch config save failed: " + e.getMessage());
            }
        }
    }

    private Config.LaunchConfig launchConfigFromMap(Map<String, Object> l, boolean isDefault) {
        Map<String, Object> r = Json.objField(l, "readiness");
        Config.Readiness readiness = null;
        if (r != null) {
            readiness = new Config.Readiness(
                    Json.str(r, "type", "http"),
                    Json.str(r, "host", "127.0.0.1"),
                    Json.str(r, "resolve", "fixed"),
                    Json.integer(r, "port", 0),
                    Json.str(r, "sourceFile", ""),
                    Json.str(r, "sourceKey", ""),
                    Json.str(r, "path", "/"),
                    Json.str(r, "expect", "200"));
        }
        return new Config.LaunchConfig(
                Json.str(l, "id", "run-" + System.currentTimeMillis()),
                Json.str(l, "name", Json.str(l, "id", "Run")),
                Json.str(l, "kind", "custom-command"),
                Json.str(l, "launcher", ""),
                Json.strList(l, "args"),
                strMapField(l, "env"),
                Json.str(l, "workDir", "repo"),
                Json.integer(l, "port", 0),
                Json.bool(l, "portFixed", true),
                Json.bool(l, "requiresDisplay", false),
                readiness,
                Json.strList(l, "readyMarkers"),
                Json.strList(l, "shutdownMarkers"),
                Json.integer(l, "stopGraceSeconds", 20),
                Json.integer(l, "startupTimeoutSeconds", 180) * 1000L,
                Json.bool(l, "default", isDefault));
    }

    private void detectRoute(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            json(ex, 405, err("POST required"));
            return;
        }
        Map<String, Object> body = readBody(ex);
        String source = Json.str(body, "source", "local");
        Map<String, Object> result = new LinkedHashMap<>();
        if ("local".equals(source)) {
            String path = App.expandHome(Json.str(body, "path"));
            if (path == null || !Files.isDirectory(Path.of(path))) {
                json(ex, 400, err("Directory does not exist: " + path));
                return;
            }
            Adapters.DetectionResult d = Adapters.detect(Path.of(path));
            result.put("detection", d.toJson());
            result.put("existing", Main.currentConfig != null
                    && isRegisteredAt(Path.of(path)));
            Map<String, Object> tools = Adapters.detectTools();
            Map<String, String> env = new LinkedHashMap<>();
            String java21 = String.valueOf(tools.getOrDefault("java21", ""));
            if (!java21.isEmpty()) {
                env.put("JAVA_HOME", java21);
            }
            Map<String, Config.CommandBlock> ops = Adapters.get(d.typeId)
                    .proposeOperations(Path.of(path), env);
            Map<String, Object> opsJson = new LinkedHashMap<>();
            for (Map.Entry<String, Config.CommandBlock> e : ops.entrySet()) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("cmd", e.getValue().cmd);
                b.put("env", e.getValue().env);
                b.put("timeoutMinutes", e.getValue().timeoutMs / 60000);
                opsJson.put(e.getKey(), b);
            }
            result.put("proposedOperations", opsJson);
            json(ex, 200, result);
        } else if ("git".equals(source)) {
            String url = Json.str(body, "url");
            if (!GitOps.validUrl(url)) {
                json(ex, 400, err("Not a valid Git URL: " + url));
                return;
            }
            if (!GitOps.installed()) {
                json(ex, 400, err("Git CLI is not installed."));
                return;
            }
            String destDir = App.expandHome(Json.str(body, "destDir"));
            if (destDir == null || destDir.isBlank()) {
                json(ex, 400, err("Destination directory required."));
                return;
            }
            Path dest = Path.of(destDir);
            if (Files.exists(dest)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("exists", true);
                m.put("path", dest.toString());
                if (GitOps.isRepo(dest)) {
                    m.put("isRepo", true);
                    Adapters.DetectionResult d = Adapters.detect(dest);
                    m.put("detection", d.toJson());
                }
                json(ex, 200, m);
                return;
            }
            GitOps.Result ls = GitOps.lsRemote(url);
            if (!ls.ok()) {
                json(ex, 400, err("Repository not reachable: " + firstLineOf(ls.output())));
                return;
            }
            String defaultBranch = GitOps.defaultBranchFrom(ls.output());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("exists", false);
            m.put("reachable", true);
            m.put("defaultBranch", defaultBranch);
            m.put("url", url);
            m.put("destDir", dest.toString());
            json(ex, 200, m);
        } else {
            json(ex, 400, err("Unknown source: " + source
                    + " (supported: local, git)"));
        }
    }

    private boolean isRegisteredAt(Path path) {
        for (Config.Project p : Main.currentConfig.projects) {
            if (Path.of(p.repoDir).toAbsolutePath().normalize()
                    .equals(path.toAbsolutePath().normalize())) {
                return true;
            }
        }
        return false;
    }

    private String firstLineOf(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    // ---------------------------------------------------------------- git

    private void gitRoute(HttpExchange ex, String method, Config.Project p) throws IOException {
        if (!"GET".equals(method)) {
            json(ex, 405, err("GET required"));
            return;
        }
        Map<String, Object> g = gitStatus(p);
        gitCache.put(p.id, g);
        gitCacheTime.put(p.id, System.currentTimeMillis());
        json(ex, 200, g);
    }

    private Map<String, Object> gitStatus(Config.Project p) {
        long now = System.currentTimeMillis();
        Long cachedAt = gitCacheTime.get(p.id);
        Map<String, Object> cached = gitCache.get(p.id);
        if (cached != null && cachedAt != null && now - cachedAt < 8000) {
            return cached;
        }
        Map<String, Object> g = GitOps.status(Path.of(p.repoDir));
        if (g.get("isRepo") == Boolean.TRUE) {
            GitOps.Result log = GitOps.log(Path.of(p.repoDir), 5);
            List<String> commits = new ArrayList<>();
            if (log.ok()) {
                for (String line : log.output().split("\n")) {
                    if (!line.isBlank()) {
                        commits.add(line.strip());
                    }
                }
            }
            g.put("recentCommits", commits);
            GitOps.Result branches = GitOps.branches(Path.of(p.repoDir));
            if (branches.ok()) {
                List<Map<String, Object>> bl = new ArrayList<>();
                for (String line : branches.output().split("\n")) {
                    String b = line.strip();
                    if (!b.isBlank()) {
                        Map<String, Object> bm = new LinkedHashMap<>();
                        bm.put("name", b.replaceFirst("^\\* ", ""));
                        bm.put("current", b.startsWith("*"));
                        bl.add(bm);
                    }
                }
                g.put("branches", bl);
            }
        }
        return g;
    }

    // ------------------------------------------------------------- profiles

    private void profilesRoute(HttpExchange ex, String method, String rest) throws IOException {
        Config cfg = Main.currentConfig;
        if (rest.isEmpty()) {
            if ("GET".equals(method)) {
                List<Map<String, Object>> list = new ArrayList<>();
                for (Config.Profile pr : cfg.profiles) {
                    list.add(pr.toJson());
                }
                json(ex, 200, list);
            } else if ("POST".equals(method)) {
                Map<String, Object> body = readBody(ex);
                String id = Json.str(body, "id");
                if (id == null || !id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                    json(ex, 400, err("Valid profile id required"));
                    return;
                }
                if (cfg.profile(id) != null) {
                    json(ex, 409, err("Profile already exists: " + id));
                    return;
                }
                Config.Profile pr = new Config.Profile(id, Json.str(body, "name", id),
                        Json.strList(body, "projects"), Json.strList(body, "startupOrder"),
                        Json.strList(body, "stopOrder"), strMapField(body, "environment"));
                synchronized (cfg) {
                    cfg.profiles.add(pr);
                    try {
                        cfg.save();
                    } catch (IOException e) {
                        json(ex, 500, err("Could not save workspace: " + e.getMessage()));
                        return;
                    }
                }
                json(ex, 200, Map.of("ok", true));
            } else {
                json(ex, 405, err("Method not allowed"));
            }
            return;
        }
        String id = rest.split("/")[0];
        String sub = rest.contains("/") ? rest.substring(id.length()) : "";
        Config.Profile pr = cfg.profile(id);
        if (pr == null) {
            json(ex, 404, err("Unknown profile: " + id));
            return;
        }
        switch (sub) {
            case "/delete" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                synchronized (cfg) {
                    cfg.profiles.removeIf(x -> x.id.equals(id));
                    try {
                        cfg.save();
                    } catch (IOException e) {
                        json(ex, 500, err("Could not save workspace: " + e.getMessage()));
                        return;
                    }
                }
                json(ex, 200, Map.of("ok", true));
            }
            case "" -> {
                if (!"PUT".equals(method)) {
                    json(ex, 405, err("PUT required"));
                    return;
                }
                Map<String, Object> body = readBody(ex);
                Config.Profile updated = new Config.Profile(id,
                        Json.str(body, "name", pr.name),
                        Json.strList(body, "projects"),
                        Json.strList(body, "startupOrder"),
                        Json.strList(body, "stopOrder"),
                        strMapField(body, "environment"));
                synchronized (cfg) {
                    for (int i = 0; i < cfg.profiles.size(); i++) {
                        if (cfg.profiles.get(i).id.equals(id)) {
                            cfg.profiles.set(i, updated);
                            break;
                        }
                    }
                    try {
                        cfg.save();
                    } catch (IOException e) {
                        json(ex, 500, err("Could not save workspace: " + e.getMessage()));
                        return;
                    }
                }
                json(ex, 200, Map.of("ok", true));
            }
            case "/launch" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                json(ex, 200, Json.obj(Json.parse(ops.submit("", "profileLaunch", id, null, Map.of()))));
            }
            case "/stop" -> {
                if (!"POST".equals(method)) {
                    json(ex, 405, err("POST required"));
                    return;
                }
                json(ex, 200, Json.obj(Json.parse(ops.submit("", "profileStop", id, null, Map.of()))));
            }
            default -> json(ex, 404, err("Unknown profile path: " + sub));
        }
    }

    // ------------------------------------------------------------- settings

    private void settingsRoute(HttpExchange ex, String method) throws IOException {
        if ("GET".equals(method)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", app.name);
            m.put("version", app.version);
            m.put("host", app.host);
            m.put("port", app.port);
            m.put("portFallbackRange", app.portFallbackRange);
            m.put("openBrowser", app.openBrowser);
            m.put("theme", app.theme);
            m.put("language", app.language);
            m.put("defaultWorkspace", app.defaultWorkspace);
            m.put("defaultRepositoryDir", app.defaultRepositoryDir);
            m.put("monitorRefreshMs", app.monitorRefreshMs);
            m.put("logRetentionDays", app.logRetentionDays);
            m.put("historyRetention", app.historyRetention);
            m.put("confirmDestructive", app.confirmDestructive);
            m.put("autoSaveWorkspace", app.autoSaveWorkspace);
            m.put("browserCommand", app.browserCommand);
            m.put("webServerEnabled", app.webServerEnabled);
            m.put("webServerAutoStart", app.webServerAutoStart);
            m.put("tools", Adapters.detectTools());
            m.put("appDir", app.appDir.toString());
            json(ex, 200, m);
        } else if ("PUT".equals(method)) {
            Map<String, Object> body = readBody(ex);
            List<String> errors = App.validateSettings(body);
            if (!errors.isEmpty()) {
                json(ex, 400, err(String.join(" ", errors)));
                return;
            }
            App.applySettingsMap(app, body);
            app.save();
            json(ex, 200, Map.of("ok", true));
        } else {
            json(ex, 405, err("Method not allowed"));
        }
    }

    // ---------------------------------------------------------------- ops

    private void opsRoute(HttpExchange ex, String method, String rest) throws IOException {
        if (rest.isEmpty()) {
            if ("GET".equals(method)) {
                json(ex, 200, opsJson());
            } else if ("POST".equals(method)) {
                Map<String, Object> body = readBody(ex);
                String project = Json.str(body, "project");
                String type = Json.str(body, "type");
                if (type == null) {
                    json(ex, 400, err("Missing 'type'"));
                    return;
                }
                boolean validType = List.of("build", "cleanBuild", "update", "test", "package",
                        "verify", "install", "validate", "buildModule", "run", "stop", "restart",
                        "buildProduct", "validateProduct", "buildFeatures", "buildPlugins",
                        "exportProduct", "exportLinux", "exportWindows", "exportMacosx",
                        "exportAll", "buildP2Repository", "publishUpdateSite",
                        "validateUpdateRepository", "inspectVersions", "ftest")
                        .contains(type);
                boolean validGlobal = List.of("launchAll", "stopAll", "restartAll").contains(type);
                boolean validGit = List.of("gitFetch", "gitPull", "gitPush", "gitCommit",
                        "checkUpdates", "updateAll").contains(type);
                boolean validProfile = List.of("profileLaunch", "profileStop").contains(type);
                if (!validType && !validGlobal && !validGit && !validProfile) {
                    json(ex, 400, err("Invalid operation type: " + type));
                    return;
                }
                if (validType && project == null) {
                    json(ex, 400, err("Missing 'project'"));
                    return;
                }
                if ((validType || validGit) && Main.currentConfig.project(project) == null) {
                    json(ex, 404, err("Unknown project: " + project));
                    return;
                }
                if (validProfile) {
                    project = "";
                }
                String mode = Json.str(body, "launchConfig", Json.str(body, "mode"));
                Integer port = null;
                if (body.containsKey("port") && body.get("port") instanceof Number n) {
                    port = n.intValue();
                }
                String result = ops.submit(project, type, mode, port, body);
                Object parsed = Json.parse(result);
                if (result.contains("\"error\"")) {
                    json(ex, 409, parsed);
                } else {
                    json(ex, 200, parsed);
                }
            } else {
                json(ex, 405, err("Method not allowed"));
            }
            return;
        }
        String[] seg = rest.split("/");
        long opId;
        try {
            opId = Long.parseLong(seg[0]);
        } catch (NumberFormatException e) {
            json(ex, 404, err("Invalid op id"));
            return;
        }
        State.Op op = findOp(opId);
        if (op == null) {
            json(ex, 404, err("Unknown operation: " + opId));
            return;
        }
        if (seg.length == 1) {
            if ("GET".equals(method)) {
                json(ex, 200, op.toJson(true));
            } else {
                json(ex, 405, err("Method not allowed"));
            }
        } else if ("cancel".equals(seg[1]) && "POST".equals(method)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ok", ops.cancel(opId));
            json(ex, 200, m);
        } else if ("log".equals(seg[1]) && "GET".equals(method)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", opId);
            String log = null;
            Path opLogFile = app.opsDir.resolve(opId + ".log");
            try {
                if (Files.exists(opLogFile)) {
                    log = Files.readString(opLogFile);
                }
            } catch (IOException ignored) {
                // fall back to buffer
            }
            if (log == null) {
                log = op.logTail(1_000_000);
            }
            m.put("log", log);
            json(ex, 200, m);
        } else {
            json(ex, 404, err("Unknown op path"));
        }
    }

    // ------------------------------------------------------------------ enr

    private void enrRoute(HttpExchange ex, String method, String sub) throws IOException {
        if ("GET".equals(method)) {
            switch (sub) {
                case "/runtime" -> json(ex, 200, enr.runtime());
                case "/models" -> json(ex, 200, enr.models());
                case "/inference" -> json(ex, 200, enr.inference());
                case "" -> json(ex, 200, enr.runtime());
                default -> json(ex, 404, err("Unknown ENR route"));
            }
        } else if ("POST".equals(method) && "/action".equals(sub)) {
            Map<String, Object> body = readBody(ex);
            String action = Json.str(body, "action");
            Map<String, Object> resp;
            if ("load".equals(action)) {
                String modelPath = Json.str(body, "path");
                if (modelPath == null) {
                    json(ex, 400, err("Missing model path"));
                    return;
                }
                String backend = Json.str(body, "backend", "");
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("path", modelPath);
                if (!backend.isBlank()) {
                    req.put("backend", backend);
                }
                resp = enr.post("/models/load", Json.write(req));
            } else if ("unload".equals(action)) {
                String id = Json.str(body, "id");
                if (id == null) {
                    json(ex, 400, err("Missing model id"));
                    return;
                }
                Map<String, Object> req = new LinkedHashMap<>();
                req.put("id", id);
                req.put("force", Json.bool(body, "force", false));
                resp = enr.post("/models/unload", Json.write(req));
            } else {
                json(ex, 400, err("Unknown ENR action: " + action + " (supported: load, unload)"));
                return;
            }
            state.notify("info", "ENR", "ENR " + action,
                    resp.containsKey("error") ? String.valueOf(resp.get("error")) : (action + " accepted"), "");
            json(ex, 200, resp);
        } else {
            json(ex, 405, err("Method not allowed"));
        }
    }

    // ---------------------------------------------------------- func tests

    private void funcTestsRoute(HttpExchange ex, String method, String rest) throws IOException {
        if (rest.isEmpty()) {
            if ("GET".equals(method)) {
                Map<String, Object> m = new LinkedHashMap<>();
                Map<String, Object> latest = FuncTest.latest(app);
                List<Map<String, Object>> instances = new ArrayList<>();
                for (FuncTest.Instance in : FuncTest.instances(Main.currentConfig)) {
                    Object last = latest.get(in.key());
                    instances.add(in.toJson(last instanceof Map<?, ?> lm
                            ? castObj(lm) : null));
                }
                m.put("instances", instances);
                m.put("runs", FuncTest.allRuns(app));
                json(ex, 200, m);
            } else if ("POST".equals(method)) {
                Map<String, Object> body = readBody(ex);
                String project = Json.str(body, "project");
                String launchConfig = Json.str(body, "launchConfig");
                String modeStr = Json.str(body, "mode", "web");
                if (project == null || Main.currentConfig.project(project) == null) {
                    json(ex, 404, err("Unknown project: " + project));
                    return;
                }
                Config.Project p = Main.currentConfig.project(project);
                Config.LaunchConfig lc = p.launch(launchConfig);
                if (lc == null) {
                    json(ex, 404, err("Unknown launch configuration: " + launchConfig));
                    return;
                }
                FuncTest.Mode mode = FuncTest.Mode.from(modeStr);
                if (!FuncTest.deriveModes(lc).contains(mode)) {
                    json(ex, 400, err("Execution mode " + mode.label
                            + " is not applicable to launch configuration '" + launchConfig + "'."));
                    return;
                }
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("mode", mode.name());
                String result = ops.submit(project, "ftest", launchConfig, null, extra);
                Object parsed = Json.parse(result);
                if (result.contains("\"error\"")) {
                    json(ex, 409, parsed);
                } else {
                    json(ex, 200, parsed);
                }
            } else {
                json(ex, 405, err("Method not allowed"));
            }
            return;
        }
        if (rest.equals("/export")) {
            exportFuncTests(ex, method);
            return;
        }
        String[] seg = rest.substring(1).split("/");
        long id;
        try {
            id = Long.parseLong(seg[0]);
        } catch (NumberFormatException e) {
            json(ex, 404, err("Invalid run id"));
            return;
        }
        if (seg.length == 1) {
            if ("GET".equals(method)) {
                FuncTest.Run run = FuncTest.load(app, id);
                if (run == null) {
                    json(ex, 404, err("Unknown test run: " + id));
                } else {
                    json(ex, 200, run.toJson());
                }
            } else {
                json(ex, 405, err("Method not allowed"));
            }
        } else if ("log".equals(seg[1]) && "GET".equals(method)) {
            FuncTest.Run run = FuncTest.load(app, id);
            if (run == null) {
                json(ex, 404, err("Unknown test run: " + id));
                return;
            }
            StringBuilder sb = new StringBuilder();
            for (FuncTest.Step s : run.steps) {
                sb.append("=== step ").append(s.seq).append(": ").append(s.name)
                        .append(" (").append(s.status).append(") ===\n").append(s.log);
                if (s.error != null && !s.error.isBlank()) {
                    sb.append("--- error ---\n").append(s.error).append('\n');
                }
            }
            json(ex, 200, Map.of("id", id, "log", sb.toString()));
        } else if ("cancel".equals(seg[1]) && "POST".equals(method)) {
            json(ex, 200, Map.of("ok", ops.cancel(id)));
        } else {
            json(ex, 404, err("Unknown ftest path"));
        }
    }

    private void exportFuncTests(HttpExchange ex, String method) throws IOException {
        if (!"GET".equals(method)) {
            json(ex, 405, err("GET required"));
            return;
        }
        String query = ex.getRequestURI().getQuery();
        String format = "html";
        String scope = "all";
        String project = "";
        String mode = "";
        long runId = -1;
        if (query != null) {
            for (String kv : query.split("&")) {
                String[] kvp = kv.split("=", 2);
                String k = kvp[0];
                String v = kvp.length > 1 ? java.net.URLDecoder.decode(kvp[1], StandardCharsets.UTF_8) : "";
                switch (k) {
                    case "format" -> format = v;
                    case "scope" -> scope = v;
                    case "project" -> project = v;
                    case "mode" -> mode = v;
                    case "runId" -> {
                        try {
                            runId = Long.parseLong(v);
                        } catch (NumberFormatException ignored) {
                            runId = -1;
                        }
                    }
                    default -> { }
                }
            }
        }
        List<FuncTest.Run> runs = collectRuns(scope, project, mode, runId);
        String title = exportTitle(scope, project, mode, runId);
        String fmt = format.toLowerCase(java.util.Locale.ROOT);
        switch (fmt) {
            case "json" -> {
                byte[] b = Report.json(runs).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=upm-ftests.json");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            case "md", "markdown" -> {
                byte[] b = Report.markdown(runs, title).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/markdown; charset=utf-8");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=upm-ftests.md");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            case "zip" -> {
                byte[] b = Report.zip(runs, title);
                ex.getResponseHeaders().set("Content-Type", "application/zip");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=upm-ftests.zip");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
            default -> {
                byte[] b = Report.html(runs, title).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                ex.getResponseHeaders().set("Content-Disposition",
                        "attachment; filename=upm-ftests.html");
                ex.sendResponseHeaders(200, b.length);
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(b);
                }
            }
        }
    }

    private List<FuncTest.Run> collectRuns(String scope, String project, String mode, long runId) {
        List<FuncTest.Run> out = new ArrayList<>();
        List<Map<String, Object>> summaries = FuncTest.allRuns(app);
        for (Map<String, Object> s : summaries) {
            long id = Json.longVal(s, "id", -1);
            String p = Json.str(s, "project", "");
            String m = Json.str(s, "mode", "");
            boolean include = switch (scope == null ? "all" : scope) {
                case "all" -> true;
                case "project" -> p.equals(project);
                case "mode" -> m.equalsIgnoreCase(mode);
                case "run", "runId", "id" -> id == runId;
                default -> false;
            };
            if (!include) {
                continue;
            }
            FuncTest.Run r = FuncTest.load(app, id);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    private String exportTitle(String scope, String project, String mode, long runId) {
        return switch (scope == null ? "all" : scope) {
            case "project" -> "UPM Functional Test Report — " + project;
            case "mode" -> "UPM Functional Test Report — " + mode;
            case "run", "runId", "id" -> "UPM Functional Test Report — run #" + runId;
            default -> "UPM Functional Test Report — complete ecosystem";
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castObj(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private void dashboardRoute(HttpExchange ex, String method) throws IOException {
        if ("GET".equals(method)) {
            json(ex, 200, dash.load(Main.currentConfig.workspaceId, Main.currentConfig.dashboard));
        } else if ("PUT".equals(method)) {
            Map<String, Object> body = readBody(ex);
            dash.save(Main.currentConfig.workspaceId, body);
            json(ex, 200, dash.load(Main.currentConfig.workspaceId, Main.currentConfig.dashboard));
        } else {
            json(ex, 405, err("Method not allowed"));
        }
    }

    // ------------------------------------------------------------ discovery

    private void discoverRoute(HttpExchange ex, String method) throws IOException {
        if (!"POST".equals(method)) {
            json(ex, 405, err("POST required"));
            return;
        }
        Map<String, Object> body = readBody(ex);
        String action = Json.str(body, "action", "scan");
        Config cfg = Main.currentConfig;
        switch (action) {
            case "scan" -> {
                String root = App.expandHome(Json.str(body, "path"));
                if (root == null || !Files.isDirectory(Path.of(root))) {
                    json(ex, 400, err("Directory does not exist: " + root));
                    return;
                }
                int depth = Json.integer(body, "depth", 4);
                List<String> exclusions = Json.strList(body, "exclusions");
                json(ex, 200, scanner.scanRoot(root, depth, exclusions, cfg.workspaceId));
            }
            case "remote" -> json(ex, 200, scanner.inspectRemote(Json.str(body, "url", "")));
            case "state" -> json(ex, 200, scanner.loadState());
            case "ignore" -> json(ex, 200, scanner.setIgnored(Json.str(body, "path"), Json.bool(body, "ignore", true)));
            case "moved" -> json(ex, 200, scanner.findMovedProjects(cfg));
            case "remap" -> {
                String err = scanner.remapProject(cfg, Json.str(body, "projectId"),
                        Json.str(body, "newPath"));
                if (err != null) {
                    json(ex, 400, err(err));
                } else {
                    json(ex, 200, Map.of("ok", true));
                }
            }
            case "register" -> addProject(ex);
            default -> json(ex, 400, err("Unknown discovery action: " + action));
        }
    }

    // ---------------------------------------------------------------- graph

    private void graphRoute(HttpExchange ex, String method, String sub) throws IOException {
        if ("/layout".equals(sub)) {
            graphLayoutRoute(ex, method);
            return;
        }
        if (!"GET".equals(method)) {
            json(ex, 405, err("GET required"));
            return;
        }
        String perspective = "workspace-overview";
        String query = ex.getRequestURI().getQuery();
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("perspective=")) {
                    perspective = java.net.URLDecoder.decode(kv.substring(12), StandardCharsets.UTF_8);
                }
            }
        }
        String opId = null;
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("opId=")) {
                    opId = kv.substring(5);
                }
            }
        }
        ArchGraph arch = new ArchGraph(state, proc, metrics);
        ArchGraph.Graph g = switch (perspective) {
            case "runtime-topology" -> arch.runtimeTopology();
            case "execution-flow" -> arch.executionFlow(opId);
            case "ai-topology" -> arch.aiTopology();
            case "dependency-graph" -> arch.dependencyGraph();
            case "infrastructure" -> arch.infrastructure();
            default -> arch.workspaceOverview();
        };
        json(ex, 200, g.toJson());
    }

    private void graphLayoutRoute(HttpExchange ex, String method) throws IOException {
        Path layoutFile = store.workspaceDir(Main.currentConfig.workspaceId)
                .resolve("diagram-layouts.jsonc");
        if ("GET".equals(method)) {
            try {
                if (Files.exists(layoutFile)) {
                    json(ex, 200, Json.obj(Json.parse(Json.stripComments(Files.readString(layoutFile)))));
                    return;
                }
            } catch (Exception ignored) {
                // fall through to empty
            }
            json(ex, 200, new LinkedHashMap<>());
        } else if ("PUT".equals(method)) {
            Map<String, Object> body = readBody(ex);
            try {
                Files.createDirectories(layoutFile.getParent());
                Files.writeString(layoutFile, Json.write(body));
                json(ex, 200, Map.of("ok", true));
            } catch (IOException e) {
                json(ex, 500, err(e.getMessage()));
            }
        } else {
            json(ex, 405, err("Method not allowed"));
        }
    }

    // -------------------------------------------------------------- metrics

    private Map<String, Object> metricsCurrent() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu", metrics.cpu());
        m.put("memory", metrics.memory());
        m.put("disk", metrics.disk());
        m.put("gpu", metrics.gpu());
        m.put("gpuProcesses", metrics.gpuProcesses());
        m.put("network", Map.of("rxKbps", metrics.network()[0], "txKbps", metrics.network()[1]));
        m.put("processes", metrics.managedProcesses());
        m.put("storageSizes", metrics.storageSizes());
        m.put("historyMetrics", metrics.historyMetrics());
        m.put("timeMs", System.currentTimeMillis());
        return m;
    }

    private void metricsHistoryRoute(HttpExchange ex) throws IOException {
        String query = ex.getRequestURI().getQuery();
        String metric = "cpu";
        long since = 0;
        int max = 600;
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("metric=")) {
                    metric = java.net.URLDecoder.decode(kv.substring(7), StandardCharsets.UTF_8);
                } else if (kv.startsWith("since=")) {
                    try {
                        since = Long.parseLong(kv.substring(6));
                    } catch (NumberFormatException ignored) {
                        // keep default
                    }
                } else if (kv.startsWith("max=")) {
                    try {
                        max = Integer.parseInt(kv.substring(4));
                    } catch (NumberFormatException ignored) {
                        // keep default
                    }
                }
            }
        }
        json(ex, 200, metrics.historySince(metric, since, Math.max(10, Math.min(max, 2000))));
    }

    private void metricsConfigRoute(HttpExchange ex, String method) throws IOException {
        if ("GET".equals(method)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intervalMs", metrics.intervalMs());
            m.put("historyPoints", metrics.historyPoints());
            json(ex, 200, m);
        } else if ("PUT".equals(method)) {
            Map<String, Object> body = readBody(ex);
            metrics.configure(Json.integer(body, "intervalMs", metrics.intervalMs()),
                    Json.integer(body, "historyPoints", metrics.historyPoints()));
            json(ex, 200, Map.of("ok", true, "intervalMs", metrics.intervalMs(),
                    "historyPoints", metrics.historyPoints()));
        } else {
            json(ex, 405, err("Method not allowed"));
        }
    }

    // ------------------------------------------------------------------ llm

    private Map<String, Object> llmJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> providers = new ArrayList<>();
        if (Main.currentConfig != null) {
            for (Config.Project p : Main.currentConfig.projects) {
                if (!p.enrMonitor || p.apiBase == null || p.apiBase.isBlank()) {
                    continue;
                }
                State.ProjectState ps = state.stateFor(p.id);
                Map<String, Object> pr = new LinkedHashMap<>();
                pr.put("id", p.id);
                pr.put("name", p.name);
                pr.put("kind", "enr");
                pr.put("endpoint", p.apiBase);
                pr.put("runtimeState", ps.runtimeState);
                pr.put("pid", ps.pid);
                String health = Probe.httpGet(p.apiBase + "/health", 2000);
                pr.put("health", health != null && health.contains("healthy") ? "healthy" : "unreachable");
                providers.add(pr);
            }
        }
        // Ollama (detected independently).
        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("id", "ollama");
        ollama.put("name", "Ollama");
        ollama.put("kind", "ollama");
        ollama.put("endpoint", "http://127.0.0.1:11434");
        String tags = Probe.httpGet("http://127.0.0.1:11434/api/tags", 1500);
        ollama.put("health", tags != null ? "reachable" : "unreachable");
        List<Map<String, Object>> ollamaModels = new ArrayList<>();
        if (tags != null) {
            try {
                Map<String, Object> om = Json.obj(Json.parse(tags));
                for (Map<String, Object> model : Json.objList(om, "models")) {
                    Map<String, Object> mm = new LinkedHashMap<>();
                    mm.put("id", Json.str(model, "name"));
                    mm.put("provider", "ollama");
                    mm.put("state", "available");
                    mm.put("sizeBytes", model.get("size"));
                    ollamaModels.add(mm);
                }
            } catch (Exception ignored) {
                // not JSON
            }
        }
        ollama.put("models", ollamaModels);
        providers.add(ollama);
        m.put("providers", providers);
        m.put("enr", enr.runtime());
        m.put("enrModels", enr.models());
        m.put("enrInference", enr.inference());
        m.put("gpu", metrics.gpu());
        m.put("gpuProcesses", metrics.gpuProcesses());
        m.put("timeMs", System.currentTimeMillis());
        return m;
    }

    // ------------------------------------------------------------ snapshots

    private Map<String, Object> snapshot() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("seq", state.lastEventSeq());
        m.put("app", appJson());
        m.put("workspace", workspaceJson());
        m.put("projects", projectsJson(true));
        m.put("profiles", profilesJson());
        m.put("workspaces", store.list());
        m.put("ops", opsJson());
        m.put("system", systemJson());
        m.put("notifications", notificationsJson());
        m.put("enr", enr.runtime());
        m.put("dashboard", dash.load(Main.currentConfig.workspaceId, Main.currentConfig.dashboard));
        m.put("settings", settingsJson());
        return m;
    }

    private Map<String, Object> appJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", app.name);
        m.put("version", app.version);
        m.put("host", app.host);
        m.put("port", Main.currentPort);
        m.put("theme", app.theme);
        m.put("language", app.language);
        m.put("defaultWorkspace", app.defaultWorkspace);
        m.put("openBrowser", app.openBrowser);
        m.put("webServerEnabled", app.webServerEnabled);
        m.put("webServerAutoStart", app.webServerAutoStart);
        return m;
    }

    private Map<String, Object> settingsJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("defaultRepositoryDir", app.defaultRepositoryDir);
        m.put("monitorRefreshMs", app.monitorRefreshMs);
        m.put("logRetentionDays", app.logRetentionDays);
        m.put("historyRetention", app.historyRetention);
        m.put("confirmDestructive", app.confirmDestructive);
        m.put("appDir", app.appDir.toString());
        return m;
    }

    private Map<String, Object> workspaceJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", Main.currentConfig.workspaceId);
        m.put("name", Main.currentConfig.workspaceName);
        m.put("description", Main.currentConfig.workspaceDescription);
        m.put("projectCount", Main.currentConfig.projects.size());
        m.put("profileCount", Main.currentConfig.profiles.size());
        return m;
    }

    private List<Map<String, Object>> profilesJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Config.Profile pr : Main.currentConfig.profiles) {
            Map<String, Object> m = pr.toJson();
            m.put("projectStates", pr.projects.stream()
                    .map(id -> Map.of("id", id,
                            "state", state.stateFor(id).runtimeState))
                    .toList());
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> projectsJson(boolean withDetail) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Config.Project p : Main.currentConfig.projects) {
            out.add(projectJson(p, withDetail));
        }
        return out;
    }

    private Map<String, Object> projectJson(Config.Project p, boolean withDetail) {
        State.ProjectState ps = state.stateFor(p.id);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id);
        m.put("name", p.name);
        m.put("description", p.description);
        m.put("repoDir", p.repoDir);
        m.put("repoUrl", p.repoUrl);
        m.put("type", p.type);
        m.put("detectionConfidence", p.detectionConfidence);
        m.put("detectionEvidence", p.detectionEvidence);
        m.put("startupAfter", p.startupAfter);
        m.put("warnings", p.warnings);
        m.put("enrMonitor", p.enrMonitor);
        m.put("apiBase", p.apiBase);
        m.put("runtimeState", ps.runtimeState);
        m.put("pid", ps.pid);
        m.put("startedAt", ps.startedAt);
        m.put("readyAt", ps.readyAt);
        m.put("launchConfig", ps.launchConfig);
        m.put("boundPort", ps.boundPort);
        m.put("lastError", ps.lastError);
        m.put("externalDetail", ps.externalDetail);
        Map<String, Object> opDetails = new LinkedHashMap<>();
        for (Map.Entry<String, Config.CommandBlock> e : p.operations.entrySet()) {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("cmd", e.getValue().cmd);
            d.put("timeoutMinutes", e.getValue().timeoutMs / 60000);
            opDetails.put(e.getKey(), d);
        }
        m.put("_opDetails", opDetails);
        m.put("uptimeMs", ps.startedAt > 0
                && !ps.runtimeState.equals("STOPPED") && !ps.runtimeState.equals("FAILED")
                ? System.currentTimeMillis() - ps.startedAt : 0);
        List<String> ops = new ArrayList<>(p.operations.keySet());
        if ("eclipse-tycho".equals(p.type)) {
            for (String special : List.of("validateProduct", "validateUpdateRepository",
                    "inspectVersions")) {
                if (!ops.contains(special)) {
                    ops.add(special);
                }
            }
        }
        m.put("operations", ops);
        List<Map<String, Object>> lcs = new ArrayList<>();
        // Listener-based availability (no TCP connections to live services).
        Set<Integer> listening = Ports.listeningPorts();
        for (Config.LaunchConfig lc : p.launchConfigs) {
            Map<String, Object> lm = lc.toJson();
            Path launcher = Path.of(App.expandHome(lc.launcher));
            if (!launcher.isAbsolute()) {
                launcher = Path.of(p.repoDir, lc.launcher);
            }
            lm.put("launcherExists", Files.isExecutable(launcher));
            lm.put("displayAvailable", System.getenv("DISPLAY") != null);
            int resolved = Probe.resolvePort(p, lc, lc.port);
            if (resolved > 0) {
                lm.put("_open", listening.contains(resolved));
            }
            lcs.add(lm);
        }
        m.put("launchConfigs", lcs);
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("parentPom", p.parentPom);
        structure.put("modules", p.modules);
        structure.put("features", p.features);
        structure.put("products", p.products);
        structure.put("targetPlatforms", p.targetPlatforms);
        structure.put("eclipseLaunchConfigs", p.eclipseLaunchConfigs);
        structure.put("requiresJava", p.requiresJava);
        structure.put("structureAt", p.structureAt);
        Map<String, Object> liveStructure = Adapters.detectStructure(Path.of(p.repoDir), p.type);
        structure.put("productUid", Json.str(liveStructure, "productUid", ""));
        structure.put("productName", Json.str(liveStructure, "productName", ""));
        structure.put("productVersion", Json.str(liveStructure, "productVersion", ""));
        structure.put("updateRepository", Json.str(liveStructure, "updateRepository", ""));
        structure.put("updateRepositoryExists",
                Json.bool(liveStructure, "updateRepositoryExists", false));
        structure.put("productOutput", Json.str(liveStructure, "productOutput", ""));
        structure.put("productOutputExists", Json.bool(liveStructure, "productOutputExists", false));
        structure.put("platforms", Json.strList(liveStructure, "platforms"));
        m.put("structure", structure);
        State.Op current = null;
        synchronized (state.ops) {
            for (State.Op op : state.ops.values()) {
                if (op.projectId.equals(p.id)) {
                    current = op;
                    break;
                }
            }
        }
        m.put("currentOp", current == null ? null : current.toJson(false));
        if (withDetail) {
            m.put("git", gitStatus(p));
            m.put("lastBuild", opResult(lastOp(p.id, "build")));
            m.put("lastExport", opResult(lastOp(p.id, "exportProduct")));
            m.put("lastTest", opResult(lastOp(p.id, "test")));
            m.put("lastUpdate", opResult(lastOp(p.id, "update")));
            m.put("lastFuncTest", opResult(lastOp(p.id, "ftest")));
        }
        // Per-instance functional test instances + last results.
        Map<String, Object> latest = ftestLatest();
        List<Map<String, Object>> ftestInstances = new ArrayList<>();
        for (FuncTest.Instance in : FuncTest.instances(Main.currentConfig)) {
            if (!in.projectId.equals(p.id)) {
                continue;
            }
            Object last = latest.get(in.key());
            ftestInstances.add(in.toJson(last instanceof Map<?, ?> lm ? castObj(lm) : null));
        }
        m.put("funcTestInstances", ftestInstances);
        return m;
    }

    private Map<String, Object> ftestLatest() {
        long now = System.currentTimeMillis();
        Map<String, Object> cached = ftestLatestCache;
        if (cached != null && now - ftestLatestCacheTime < 2000) {
            return cached;
        }
        Map<String, Object> latest = FuncTest.latest(app);
        ftestLatestCache = latest;
        ftestLatestCacheTime = now;
        return latest;
    }

    private Map<String, Object> opResult(State.Op op) {
        if (op == null) {
            return null;
        }
        return op.toJson(false);
    }

    private Map<String, Object> opsJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> active = new ArrayList<>();
        synchronized (state.ops) {
            for (State.Op op : state.ops.values()) {
                active.add(op.toJson(false));
            }
        }
        m.put("active", active);
        List<Map<String, Object>> hist = new ArrayList<>();
        synchronized (state.history) {
            for (int i = state.history.size() - 1; i >= 0; i--) {
                hist.add(state.history.get(i).toJson(false));
            }
        }
        m.put("history", hist);
        return m;
    }

    private Map<String, Object> systemJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("cpu", metrics.cpu());
        m.put("memory", metrics.memory());
        m.put("disk", metrics.disk());
        m.put("gpu", metrics.gpu());
        m.put("network", Map.of("rxKbps", metrics.network()[0], "txKbps", metrics.network()[1]));
        m.put("javaProcesses", metrics.javaProcesses());
        m.put("timeMs", System.currentTimeMillis());
        return m;
    }

    private List<Map<String, Object>> notificationsJson() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = state.notifications.size() - 1; i >= 0; i--) {
            out.add(state.notifications.get(i).toJson());
        }
        return out;
    }

    private Map<String, Object> runtimeLogJson(Config.Project p) {
        State.ProjectState ps = state.stateFor(p.id);
        String lcId = ps.launchConfig.isBlank() ? "run" : ps.launchConfig;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("project", p.id);
        m.put("launchConfig", ps.launchConfig);
        m.put("logFile", proc.runtimeLog(p.id, lcId).toString());
        m.put("log", Proc.tailLog(proc.runtimeLog(p.id, lcId), 400_000));
        m.put("crashed", Files.exists(proc.crashMarker(p.id, lcId)));
        return m;
    }

    /**
     * Port Manager inventory endpoint. Query parameters:
     * <ul>
     *   <li>{@code refresh=1} — bypass the cache and re-scan listeners.</li>
     *   <li>{@code port=NNNN} — force a fresh check and return only that entry.</li>
     *   <li>{@code app=EVO} — return only entries of one application.</li>
     * </ul>
     * The scan is read-only: it never terminates processes or reassigns ports.
     */
    private void portsInventoryRoute(HttpExchange ex, String method) throws IOException {
        if (!"GET".equals(method)) {
            json(ex, 405, err("GET required"));
            return;
        }
        String query = ex.getRequestURI().getQuery();
        boolean refresh = false;
        int onlyPort = 0;
        String appFilter = "";
        if (query != null) {
            for (String kv : query.split("&")) {
                if (kv.startsWith("refresh=")) {
                    String v = kv.substring("refresh=".length());
                    refresh = "1".equals(v) || "true".equals(v);
                } else if (kv.startsWith("port=")) {
                    try {
                        onlyPort = Integer.parseInt(kv.substring("port=".length()));
                    } catch (NumberFormatException ignored) {
                        onlyPort = 0;
                    }
                } else if (kv.startsWith("app=")) {
                    String raw = kv.substring("app=".length());
                    try {
                        appFilter = java.net.URLDecoder.decode(raw, StandardCharsets.UTF_8);
                    } catch (IllegalArgumentException e) {
                        appFilter = raw;
                    }
                }
            }
        }
        long now = System.currentTimeMillis();
        if (!refresh && onlyPort <= 0 && portsInventoryCache != null
                && now - portsInventoryCacheTime < 10_000) {
            json(ex, 200, applyPortFilter(portsInventoryCache, appFilter));
            return;
        }
        Map<String, Object> m = Ports.checkAll();
        m.put("controlCenter", Ports.controlCenter(Main.currentPort, app.host));
        if (onlyPort <= 0 && appFilter.isBlank()) {
            portsInventoryCache = m;
            portsInventoryCacheTime = now;
        }
        if (onlyPort > 0) {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
            List<Map<String, Object>> single = new ArrayList<>();
            for (Map<String, Object> e : entries) {
                if (Json.integer(e, "port", -1) == onlyPort) {
                    single.add(e);
                }
            }
            m.put("entries", single);
            recomputePortSummary(m);
        }
        json(ex, 200, applyPortFilter(m, appFilter));
    }

    private static Map<String, Object> applyPortFilter(Map<String, Object> m, String appFilter) {
        if (appFilter == null || appFilter.isBlank()) {
            return m;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        List<Map<String, Object>> filtered = new ArrayList<>();
        for (Map<String, Object> e : entries) {
            if (appFilter.equals(e.get("app"))) {
                filtered.add(e);
            }
        }
        Map<String, Object> copy = new LinkedHashMap<>(m);
        copy.put("entries", filtered);
        recomputePortSummary(copy);
        return copy;
    }

    private static void recomputePortSummary(Map<String, Object> m) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> entries = (List<Map<String, Object>>) m.get("entries");
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String s : List.of("RUNNING", "READY", "OCCUPIED")) {
            long c = entries.stream().filter(e -> s.equals(e.get("status"))).count();
            summary.put(s.toLowerCase(), c);
        }
        m.put("summary", summary);
    }

    private Map<String, Object> portsJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("controlCenter", Map.of("host", app.host, "port", Main.currentPort,
                "configured", app.port));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Config.Project p : Main.currentConfig.projects) {
            for (Config.LaunchConfig lc : p.launchConfigs) {
                int port = Probe.resolvePort(p, lc, lc.port);
                if (port <= 0) {
                    continue;
                }
                boolean open = Probe.tcpOpen("127.0.0.1", port, 800);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("project", p.id);
                e.put("launchConfig", lc.id);
                e.put("label", lc.name);
                e.put("port", port);
                e.put("open", open);
                e.put("owner", open ? Probe.ownerOf(port) : "");
                list.add(e);
            }
        }
        m.put("ports", list);
        return m;
    }

    private Map<String, Object> reportJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Config.Project p : Main.currentConfig.projects) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("project", p.id);
            r.put("build", opResult(lastOp(p.id, "build")));
            r.put("update", opResult(lastOp(p.id, "update")));
            r.put("test", opResult(lastOp(p.id, "test")));
            r.put("runtimeState", state.stateFor(p.id).runtimeState);
            rows.add(r);
        }
        m.put("projects", rows);
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

    private State.Op findOp(long id) {
        synchronized (state.ops) {
            State.Op op = state.ops.get(id);
            if (op != null) {
                return op;
            }
        }
        synchronized (state.history) {
            for (State.Op op : state.history) {
                if (op.id == id) {
                    return op;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------- SSE

    private void sse(HttpExchange ex) throws IOException {
        Headers h = ex.getResponseHeaders();
        h.set("Content-Type", "text/event-stream; charset=utf-8");
        h.set("Cache-Control", "no-cache");
        h.set("Connection", "keep-alive");
        ex.sendResponseHeaders(200, 0);
        SseClient client = new SseClient(ex.getResponseBody());
        sseClients.add(client);
        sendEvent(client, "snapshot", Json.write(snapshot()));
        while (running) {
            try {
                byte[] keepalive = ": ping\n\n".getBytes(StandardCharsets.UTF_8);
                client.out.write(keepalive);
                client.out.flush();
                Thread.sleep(15000);
            } catch (IOException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        sseClients.remove(client);
        try {
            ex.close();
        } catch (Exception ignored) {
            // closing
        }
    }

    private void broadcastLoop() {
        while (running) {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (sseClients.isEmpty()) {
                continue;
            }
            synchronized (state.ops) {
                for (State.Op op : state.ops.values()) {
                    long now = System.currentTimeMillis();
                    Long last = opLastBroadcast.get(op.id);
                    if (last != null && now - last < 350) {
                        continue;
                    }
                    synchronized (op.live) {
                        String log = op.live.toString();
                        int sent = opLogSent.getOrDefault(op.id, 0);
                        if (sent < log.length()) {
                            String chunk = log.substring(sent);
                            opLogSent.put(op.id, log.length());
                            if (chunk.length() > 8192) {
                                chunk = chunk.substring(chunk.length() - 8192);
                            }
                            broadcast("oplog", "{\"id\":" + op.id + ",\"chunk\":"
                                    + Json.write(chunk) + "}");
                        }
                    }
                    broadcast("op", Json.write(op.toJson(false)));
                    opLastBroadcast.put(op.id, now);
                }
            }
            synchronized (state.notifications) {
                if (lastNotifCount >= 0 && state.notifications.size() > lastNotifCount) {
                    lastNotifCount = state.notifications.size();
                    broadcast("notifications", Json.write(notificationsJson()));
                } else if (lastNotifCount < 0) {
                    lastNotifCount = state.notifications.size();
                }
            }
            long now = System.currentTimeMillis();
            long metricDelay = Math.max(metrics.intervalMs(), 1000);
            if (now - lastSysSent > 5000) {
                lastSysSent = now;
                broadcast("system", Json.write(systemJson()));
                broadcast("projects", Json.write(projectsJson(true)));
            }
            if (now - lastMetricsSent > metricDelay) {
                lastMetricsSent = now;
                broadcast("metrics", Json.write(metricsCurrent()));
            }
        }
    }

    private void broadcast(String event, String data) {
        for (SseClient c : sseClients) {
            try {
                sendEvent(c, event, data);
            } catch (IOException e) {
                sseClients.remove(c);
            }
        }
    }

    private void sendEvent(SseClient c, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append('\n');
        for (String line : data.split("\n")) {
            sb.append("data: ").append(line).append('\n');
        }
        sb.append('\n');
        c.out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        c.out.flush();
    }

    // ---------------------------------------------------------- http utils

    private void staticOrIndex(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if (path.equals("/")) {
            path = "/index.html";
        }
        if (path.contains("..")) {
            json(ex, 400, err("Bad path"));
            return;
        }
        byte[] bytes = Main.webResource(path);
        if (bytes == null) {
            bytes = Main.webResource("/index.html");
        }
        if (bytes == null) {
            json(ex, 404, err("Resource not found: " + path));
            return;
        }
        ex.getResponseHeaders().set("Content-Type", contentType(path));
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private String contentType(String path) {
        if (path.endsWith(".html")) {
            return "text/html; charset=utf-8";
        }
        if (path.endsWith(".css")) {
            return "text/css; charset=utf-8";
        }
        if (path.endsWith(".js")) {
            return "application/javascript; charset=utf-8";
        }
        if (path.endsWith(".svg")) {
            return "image/svg+xml";
        }
        if (path.endsWith(".png")) {
            return "image/png";
        }
        return "application/octet-stream";
    }

    private Map<String, Object> readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            String s = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            if (s.isBlank()) {
                return new LinkedHashMap<>();
            }
            return Json.obj(Json.parse(s));
        }
    }

    private void json(HttpExchange ex, int code, Object body) throws IOException {
        byte[] bytes = Json.write(body).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    // ----------------------------------------------------------- parse helpers

    private static Map<String, Config.CommandBlock> operationsFrom(Map<String, Object> body) {
        Map<String, Config.CommandBlock> out = new LinkedHashMap<>();
        Map<String, Object> operations = Json.objField(body, "operations");
        if (operations == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : operations.entrySet()) {
            if (e.getValue() instanceof Map<?, ?> mm) {
                @SuppressWarnings("unchecked")
                Map<String, Object> b = (Map<String, Object>) mm;
                out.put(e.getKey(), new Config.CommandBlock(
                        Json.strList(b, "cmd"),
                        strMapField(b, "env"),
                        Json.str(b, "workDir", "repo"),
                        Json.integer(b, "timeoutMinutes", 120) * 60000L,
                        Json.integer(b, "expectedModules", 0),
                        Json.bool(b, "supported", true)));
            }
        }
        return out;
    }

    private static List<Config.TestSuite> testSuitesFrom(Map<String, Object> body) {
        List<Config.TestSuite> suites = new ArrayList<>();
        Map<String, Object> test = Json.objField(body, "test");
        if (test == null) {
            return suites;
        }
        for (Map<String, Object> s : Json.objList(test, "suites")) {
            suites.add(new Config.TestSuite(
                    Json.str(s, "id", "suite"),
                    Json.str(s, "label", Json.str(s, "id", "suite")),
                    Json.strList(s, "cmd"),
                    strMapField(s, "env"),
                    Json.integer(s, "timeoutMinutes", 120) * 60000L));
        }
        return suites;
    }

    private static List<Config.LaunchConfig> launchConfigsFrom(Map<String, Object> body) {
        List<Config.LaunchConfig> out = new ArrayList<>();
        for (Map<String, Object> l : Json.objList(body, "launchConfigs")) {
            Map<String, Object> r = Json.objField(l, "readiness");
            Config.Readiness readiness = null;
            if (r != null) {
                readiness = new Config.Readiness(
                        Json.str(r, "type", "http"),
                        Json.str(r, "host", "127.0.0.1"),
                        Json.str(r, "resolve", "fixed"),
                        Json.integer(r, "port", 0),
                        Json.str(r, "sourceFile", ""),
                        Json.str(r, "sourceKey", ""),
                        Json.str(r, "path", "/"),
                        Json.str(r, "expect", "200"));
            }
            out.add(new Config.LaunchConfig(
                    Json.str(l, "id", "run-" + out.size()),
                    Json.str(l, "name", Json.str(l, "id", "run-" + out.size())),
                    Json.str(l, "kind", "custom-command"),
                    Json.str(l, "launcher", ""),
                    Json.strList(l, "args"),
                    strMapField(l, "env"),
                    Json.str(l, "workDir", "repo"),
                    Json.integer(l, "port", 0),
                    Json.bool(l, "portFixed", true),
                    Json.bool(l, "requiresDisplay", false),
                    readiness,
                    Json.strList(l, "readyMarkers"),
                    Json.strList(l, "shutdownMarkers"),
                    Json.integer(l, "stopGraceSeconds", 20),
                    Json.integer(l, "startupTimeoutSeconds", 180) * 1000L,
                    Json.bool(l, "default", out.isEmpty())));
        }
        return out;
    }
}
