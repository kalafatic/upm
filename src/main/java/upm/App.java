package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Application-level settings (config/application.jsonc). Everything is
 * relocatable: paths are resolved against the application directory, and no
 * machine-specific absolute path is hardcoded in the source.
 */
public final class App {

    public volatile String name = "UPM";
    public volatile String version = "2.6.11";
    public volatile String host = "127.0.0.1";
    public volatile int port = 29080;
    public volatile int portFallbackRange = 100;
    public volatile boolean openBrowser = true;
    public volatile String theme = "dark";
    public volatile String language = "en";
    public volatile String defaultWorkspace = "default";
    public volatile String defaultRepositoryDir = "~/git";
    public volatile int monitorRefreshMs = 3000;
    public volatile int logRetentionDays = 7;
    public volatile int historyRetention = 200;
    public volatile boolean confirmDestructive = true;
    public volatile boolean autoSaveWorkspace = true;
    public volatile String browserCommand = "";
    public volatile boolean webServerEnabled = true;
    public volatile boolean webServerAutoStart = true;
    public volatile Map<String, String> detectedTools = new LinkedHashMap<>();

    public final Path appDir;
    public final Path configFile;
    public final Path workspacesDir;
    public final Path dataDir;
    public final Path logsDir;
    public final Path backupDir;
    public final Path runtimeDir;
    public final Path opsDir;

    private App(Path appDir, Path configFile) {
        this.appDir = appDir;
        this.configFile = configFile;
        // Defaults relative to the application directory (relocatable).
        this.workspacesDir = appDir.resolve("workspaces");
        this.dataDir = appDir.resolve("state");
        this.logsDir = appDir.resolve("logs");
        this.backupDir = appDir.resolve("state/backups");
        this.runtimeDir = appDir.resolve("state/runtime");
        this.opsDir = appDir.resolve("state/operations");
    }

    /** Locates the application directory: the process working directory when it
     *  is a real UPM project root, otherwise the code source (jar or classes). */
    public static Path locateAppDir() {
        // Prefer the working directory when it is the project root. This makes
        // Eclipse Run/Debug launches (classpath = target/classes) and packaged
        // launches resolve the same configuration paths, independent of the
        // accidental working directory.
        Path userDir = Path.of(System.getProperty("user.dir"));
        if (looksLikeAppRoot(userDir)) {
            return userDir.toAbsolutePath();
        }
        // Fall back to the code source: the jar's directory (packaged,
        // relocatable) or, for a classes directory, the project root above it.
        try {
            Path code = Path.of(App.class.getProtectionDomain().getCodeSource()
                    .getLocation().toURI()).toAbsolutePath();
            if (Files.isRegularFile(code)) {
                return code.getParent();
            }
            if (Files.isDirectory(code)) {
                Path parent = code.getParent();
                if (looksLikeAppRoot(parent)) {
                    return parent;
                }
                Path up = parent == null ? null : parent.getParent();
                if (up != null && looksLikeAppRoot(up)) {
                    return up;
                }
                return parent;
            }
        } catch (Exception ignored) {
            // fall through
        }
        return userDir.toAbsolutePath();
    }

    /** A directory that contains the UPM source layout is the project root. */
    private static boolean looksLikeAppRoot(Path dir) {
        if (dir == null) {
            return false;
        }
        return Files.isDirectory(dir.resolve("src/main/java"))
                || Files.isRegularFile(dir.resolve("pom.xml"))
                || Files.isRegularFile(dir.resolve("build.sh"));
    }

    public static App load(Path appDir, String configPath) {
        Path configFile = Path.of(configPath);
        if (!configFile.isAbsolute()) {
            configFile = appDir.resolve(configPath);
        }
        App app = new App(appDir, configFile);
        if (Files.exists(configFile)) {
            try {
                Map<String, Object> root = Json.obj(Json.parse(
                        Json.stripComments(Files.readString(configFile))));
                Map<String, Object> a = Json.objField(root, "application");
                if (a != null) {
                    apply(app, a, appDir);
                }
            } catch (Exception e) {
                Main.log("WARN", "application.jsonc invalid (" + e.getMessage()
                        + "); using defaults");
            }
        } else {
            app.save();
        }
        return app;
    }

    private static void apply(App app, Map<String, Object> a, Path appDir) {
        app.name = Json.str(a, "name", app.name);
        app.host = Json.str(a, "host", app.host);
        app.port = Json.integer(a, "port", app.port);
        app.portFallbackRange = Json.integer(a, "portFallbackRange", app.portFallbackRange);
        app.openBrowser = Json.bool(a, "openBrowser", app.openBrowser);
        app.theme = Json.str(a, "theme", app.theme);
        app.language = Json.str(a, "language", app.language);
        app.defaultWorkspace = Json.str(a, "defaultWorkspace", app.defaultWorkspace);
        app.defaultRepositoryDir = expandHome(Json.str(a, "defaultRepositoryDir", app.defaultRepositoryDir));
        app.monitorRefreshMs = Json.integer(a, "monitorRefreshMs", app.monitorRefreshMs);
        app.logRetentionDays = Json.integer(a, "logRetentionDays", app.logRetentionDays);
        app.historyRetention = Json.integer(a, "historyRetention", app.historyRetention);
        app.confirmDestructive = Json.bool(a, "confirmDestructive", app.confirmDestructive);
        app.autoSaveWorkspace = Json.bool(a, "autoSaveWorkspace", app.autoSaveWorkspace);
        app.browserCommand = Json.str(a, "browserCommand", "");
        app.webServerEnabled = Json.bool(a, "webServerEnabled", app.webServerEnabled);
        app.webServerAutoStart = Json.bool(a, "webServerAutoStart", app.webServerAutoStart);
        Map<String, Object> tools = Json.objField(a, "detectedTools");
        app.detectedTools = new LinkedHashMap<>();
        if (tools != null) {
            for (Map.Entry<String, Object> e : tools.entrySet()) {
                if (e.getValue() instanceof String s) {
                    app.detectedTools.put(e.getKey(), s);
                }
            }
        }
    }

    public synchronized void save() {
        try {
            Files.createDirectories(configFile.getParent());
            Map<String, Object> a = new LinkedHashMap<>();
            a.put("name", name);
            a.put("host", host);
            a.put("port", port);
            a.put("portFallbackRange", portFallbackRange);
            a.put("openBrowser", openBrowser);
            a.put("theme", theme);
            a.put("language", language);
            a.put("defaultWorkspace", defaultWorkspace);
            a.put("defaultRepositoryDir", defaultRepositoryDir);
            a.put("monitorRefreshMs", monitorRefreshMs);
            a.put("logRetentionDays", logRetentionDays);
            a.put("historyRetention", historyRetention);
            a.put("confirmDestructive", confirmDestructive);
            a.put("autoSaveWorkspace", autoSaveWorkspace);
            a.put("browserCommand", browserCommand);
            a.put("webServerEnabled", webServerEnabled);
            a.put("webServerAutoStart", webServerAutoStart);
            a.put("detectedTools", new LinkedHashMap<>(detectedTools));
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("application", a);
            Files.writeString(configFile, Json.write(root));
        } catch (IOException e) {
            Main.log("ERROR", "Could not save application.jsonc: " + e.getMessage());
        }
    }

    public static String expandHome(String s) {
        if (s == null) {
            return null;
        }
        if (s.equals("~")) {
            return System.getProperty("user.home");
        }
        if (s.startsWith("~/")) {
            return System.getProperty("user.home") + s.substring(1);
        }
        return s;
    }

    /** Validates a settings map before saving. Returns error list. */
    public static List<String> validateSettings(Map<String, Object> a) {
        List<String> errors = new ArrayList<>();
        if (a.containsKey("port")) {
            int port = Json.integer(a, "port", -1);
            if (port < 1024 || port > 65535) {
                errors.add("HTTP port must be between 1024 and 65535.");
            }
        }
        String host = Json.str(a, "host");
        if (host != null && !host.equals("127.0.0.1") && !host.equals("localhost")) {
            errors.add("For safety the host should be 127.0.0.1 or localhost.");
        }
        int refresh = Json.integer(a, "monitorRefreshMs", -1);
        if (refresh >= 0 && (refresh < 500 || refresh > 60000)) {
            errors.add("Monitor refresh must be between 500 and 60000 ms.");
        }
        String theme = Json.str(a, "theme");
        if (theme != null && !theme.equals("dark") && !theme.equals("light")) {
            errors.add("Theme must be 'dark' or 'light'.");
        }
        return errors;
    }

    public static void applySettingsMap(App app, Map<String, Object> a) {
        if (a.containsKey("name")) {
            app.name = Json.str(a, "name", app.name);
        }
        if (a.containsKey("host")) {
            app.host = Json.str(a, "host", app.host);
        }
        if (a.containsKey("port")) {
            app.port = Json.integer(a, "port", app.port);
        }
        if (a.containsKey("portFallbackRange")) {
            app.portFallbackRange = Json.integer(a, "portFallbackRange", app.portFallbackRange);
        }
        if (a.containsKey("openBrowser")) {
            app.openBrowser = Json.bool(a, "openBrowser", app.openBrowser);
        }
        if (a.containsKey("theme")) {
            app.theme = Json.str(a, "theme", app.theme);
        }
        if (a.containsKey("language")) {
            app.language = Json.str(a, "language", app.language);
        }
        if (a.containsKey("defaultWorkspace")) {
            app.defaultWorkspace = Json.str(a, "defaultWorkspace", app.defaultWorkspace);
        }
        if (a.containsKey("defaultRepositoryDir")) {
            app.defaultRepositoryDir = expandHome(Json.str(a, "defaultRepositoryDir", app.defaultRepositoryDir));
        }
        if (a.containsKey("monitorRefreshMs")) {
            app.monitorRefreshMs = Json.integer(a, "monitorRefreshMs", app.monitorRefreshMs);
        }
        if (a.containsKey("logRetentionDays")) {
            app.logRetentionDays = Json.integer(a, "logRetentionDays", app.logRetentionDays);
        }
        if (a.containsKey("historyRetention")) {
            app.historyRetention = Json.integer(a, "historyRetention", app.historyRetention);
        }
        if (a.containsKey("confirmDestructive")) {
            app.confirmDestructive = Json.bool(a, "confirmDestructive", app.confirmDestructive);
        }
        if (a.containsKey("autoSaveWorkspace")) {
            app.autoSaveWorkspace = Json.bool(a, "autoSaveWorkspace", app.autoSaveWorkspace);
        }
        if (a.containsKey("browserCommand")) {
            app.browserCommand = Json.str(a, "browserCommand", "");
        }
        if (a.containsKey("webServerEnabled")) {
            app.webServerEnabled = Json.bool(a, "webServerEnabled", app.webServerEnabled);
        }
        if (a.containsKey("webServerAutoStart")) {
            app.webServerAutoStart = Json.bool(a, "webServerAutoStart", app.webServerAutoStart);
        }
    }
}
