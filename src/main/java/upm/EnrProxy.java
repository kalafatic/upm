package upm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ENR monitoring integration. Uses the existing ENR HTTP API (/api/v1) as the
 * only management contract. The ENR project is discovered from the workspace
 * configuration (enrMonitor flag / apiBase), never hardcoded.
 */
public final class EnrProxy {

    private final State state;

    public EnrProxy(State state) {
        this.state = state;
    }

    public Config.Project enr() {
        Config cfg = Main.currentConfig;
        if (cfg == null) {
            return null;
        }
        for (Config.Project p : cfg.projects) {
            if (p.enrMonitor && p.apiBase != null && !p.apiBase.isBlank()) {
                return p;
            }
        }
        return null;
    }

    private String base() {
        Config.Project p = enr();
        return p == null ? null : p.apiBase;
    }

    public boolean available() {
        String base = base();
        if (base == null) {
            return false;
        }
        String body = Probe.httpGet(base + "/health", 2500);
        return body != null && body.contains("\"status\"");
    }

    public Map<String, Object> get(String subPath) {
        String base = base();
        if (base == null) {
            return err("ENR not configured");
        }
        if (!isSafeGet(subPath)) {
            return err("ENR path not allowed through UPM");
        }
        String body = Probe.httpGet(base + subPath, 4000);
        if (body == null) {
            return err("ENR unreachable at " + base);
        }
        try {
            return Json.obj(Json.parse(body));
        } catch (Exception e) {
            return err("Invalid ENR response: " + e.getMessage());
        }
    }

    private static boolean isSafeGet(String path) {
        return path.startsWith("/health") || path.startsWith("/status")
                || path.startsWith("/models") || path.startsWith("/devices")
                || path.startsWith("/metrics") || path.startsWith("/sessions");
    }

    public Map<String, Object> post(String subPath, String body) {
        String base = base();
        if (base == null) {
            return err("ENR not configured");
        }
        boolean allowed = subPath.equals("/models/load") || subPath.equals("/models/unload");
        if (!allowed) {
            return err("ENR action not allowed through UPM");
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(base + subPath).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(60000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            InputStream in = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String resp = in == null ? "" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
            conn.disconnect();
            try {
                return Json.obj(Json.parse(resp));
            } catch (Exception e) {
                Map<String, Object> m = err(resp);
                m.put("httpCode", code);
                return m;
            }
        } catch (IOException e) {
            return err("ENR request failed: " + e.getMessage());
        }
    }

    private static Map<String, Object> err(String msg) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", msg);
        return m;
    }

    public Map<String, Object> runtime() {
        Map<String, Object> m = new LinkedHashMap<>();
        Config.Project p = enr();
        if (p != null) {
            State.ProjectState ps = state.projects.get(p.id);
            m.put("project", p.id);
            m.put("managed", ps != null);
            if (ps != null) {
                m.put("state", ps.runtimeState);
                m.put("pid", ps.pid);
                m.put("startedAt", ps.startedAt);
                m.put("mode", ps.launchConfig);
                m.put("lastError", ps.lastError);
            }
        }
        m.put("health", get("/health"));
        m.put("status", get("/status"));
        m.put("devices", get("/devices"));
        return m;
    }

    public Map<String, Object> models() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loaded", get("/models"));
        List<Map<String, Object>> files = new ArrayList<>();
        Config.Project p = enr();
        if (p != null) {
            for (String dir : p.modelDirectories) {
                try {
                    Path d = Path.of(dir);
                    if (!Files.isDirectory(d)) {
                        continue;
                    }
                    try (var stream = Files.list(d)) {
                        for (Path f : (Iterable<Path>) stream::iterator) {
                            String name = f.getFileName().toString();
                            if (!name.endsWith(".gguf") && !name.endsWith(".evo")) {
                                continue;
                            }
                            Map<String, Object> e = new LinkedHashMap<>();
                            e.put("name", name);
                            e.put("path", f.toString());
                            try {
                                e.put("sizeBytes", Files.size(f));
                            } catch (IOException ignored) {
                                e.put("sizeBytes", -1);
                            }
                            files.add(e);
                        }
                    }
                } catch (IOException ignored) {
                    // directory not readable
                }
            }
        }
        m.put("files", files);
        return m;
    }

    public Map<String, Object> inference() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metrics", get("/metrics"));
        m.put("sessions", get("/sessions"));
        return m;
    }
}
