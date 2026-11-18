package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-workspace dashboard layout persistence (dashboard.jsonc). */
public final class Dash {

    public static final List<String> KNOWN_WIDGETS = List.of(
            "project-status", "build-progress", "test-summary", "runtime-status",
            "cpu", "memory", "gpu", "vram", "active-ops", "recent-ops",
            "log-viewer", "enr-models", "enr-inference", "ports", "workspace-health",
            "git-overview");

    public static Map<String, Object> defaultLayoutMap() {
        Map<String, Object> layout = new LinkedHashMap<>();
        layout.put("name", "default");
        layout.put("columns", 3);
        List<Map<String, Object>> w = new ArrayList<>();
        w.add(widget("project-status", 3, 1, true));
        w.add(widget("active-ops", 2, 1, true));
        w.add(widget("workspace-health", 1, 1, true));
        w.add(widget("build-progress", 1, 1, true));
        w.add(widget("test-summary", 1, 1, true));
        w.add(widget("runtime-status", 1, 1, true));
        w.add(widget("git-overview", 1, 1, true));
        w.add(widget("cpu", 1, 1, true));
        w.add(widget("memory", 1, 1, true));
        w.add(widget("gpu", 1, 1, true));
        w.add(widget("recent-ops", 2, 1, true));
        w.add(widget("log-viewer", 1, 1, true));
        w.add(widget("enr-models", 1, 1, true));
        layout.put("widgets", w);
        return layout;
    }

    private static Map<String, Object> widget(String type, int w, int h, boolean visible) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("w", w);
        m.put("h", h);
        m.put("visible", visible);
        return m;
    }

    private final App app;

    public Dash(App app) {
        this.app = app;
    }

    private Path fileFor(String workspaceId) {
        return app.workspacesDir.resolve(workspaceId).resolve("dashboard.jsonc");
    }

    public synchronized Map<String, Object> load(String workspaceId, Map<String, Object> fallback) {
        try {
            Path f = fileFor(workspaceId);
            if (Files.exists(f)) {
                Map<String, Object> m = Json.obj(Json.parse(
                        Json.stripComments(Files.readString(f))));
                if (m.containsKey("widgets") && !Json.objList(m, "widgets").isEmpty()) {
                    return sanitize(m);
                }
            }
        } catch (Exception e) {
            Main.log("WARN", "Dashboard layout unreadable, using fallback: " + e.getMessage());
        }
        Map<String, Object> sanitized = sanitize(fallback);
        if (Json.objList(sanitized, "widgets").isEmpty()) {
            return defaultLayoutMap();
        }
        return sanitized;
    }

    public synchronized void save(String workspaceId, Map<String, Object> layout) {
        try {
            Path f = fileFor(workspaceId);
            Files.createDirectories(f.getParent());
            Files.writeString(f, Json.write(sanitize(layout)));
        } catch (IOException e) {
            Main.log("ERROR", "Could not persist dashboard layout: " + e.getMessage());
        }
    }

    public synchronized void reset(String workspaceId) {
        try {
            Files.deleteIfExists(fileFor(workspaceId));
        } catch (IOException e) {
            Main.log("ERROR", "Could not reset dashboard layout: " + e.getMessage());
        }
    }

    private Map<String, Object> sanitize(Map<String, Object> layout) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", Json.str(layout, "name", "layout"));
        int columns = Json.integer(layout, "columns", 3);
        out.put("columns", Math.max(1, Math.min(columns, 6)));
        List<Map<String, Object>> widgets = new ArrayList<>();
        for (Map<String, Object> w : Json.objList(layout, "widgets")) {
            String type = Json.str(w, "type");
            if (type == null || !KNOWN_WIDGETS.contains(type)) {
                continue;
            }
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("type", type);
            c.put("w", Math.max(1, Math.min(Json.integer(w, "w", 1), 3)));
            c.put("h", Math.max(1, Math.min(Json.integer(w, "h", 1), 3)));
            c.put("visible", Json.bool(w, "visible", true));
            widgets.add(c);
        }
        out.put("widgets", widgets);
        return out;
    }
}
