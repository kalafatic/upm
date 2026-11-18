package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace configuration model: workspace identity, registered projects
 * (adapter-driven), profiles and the dashboard layout. JSONC files under
 * workspaces/<id>/workspace.jsonc. All path references are validated on load;
 * missing repositories are reported, never silently replaced.
 */
public final class Config {

    public static final int CONFIG_VERSION = 2;

    // ----------------------------------------------------------------- project

    public static final class LaunchConfig {
        public final String id;
        public final String name;
        public final String kind; // eclipse-product | eclipse-application | jar | native | http-server | custom-command
        public final String launcher; // absolute or repo-relative path
        public final List<String> args;
        public final Map<String, String> env;
        public final String workDir; // "repo" | absolute
        public final int port;
        public final boolean portFixed;
        public final boolean requiresDisplay;
        public final Readiness readiness;
        public final List<String> readyMarkers;
        public final int stopGraceSeconds;
        public final long startupTimeoutMs;
        public final boolean isDefault;

        LaunchConfig(String id, String name, String kind, String launcher,
                List<String> args, Map<String, String> env, String workDir, int port,
                boolean portFixed, boolean requiresDisplay, Readiness readiness,
                List<String> readyMarkers, int stopGraceSeconds, long startupTimeoutMs,
                boolean isDefault) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.launcher = launcher;
            this.args = args;
            this.env = env;
            this.workDir = workDir;
            this.port = port;
            this.portFixed = portFixed;
            this.requiresDisplay = requiresDisplay;
            this.readiness = readiness;
            this.readyMarkers = readyMarkers;
            this.stopGraceSeconds = stopGraceSeconds;
            this.startupTimeoutMs = startupTimeoutMs;
            this.isDefault = isDefault;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("kind", kind);
            m.put("launcher", launcher);
            m.put("args", args);
            m.put("env", env);
            m.put("workDir", workDir);
            m.put("port", port);
            m.put("portFixed", portFixed);
            m.put("requiresDisplay", requiresDisplay);
            if (readiness != null) {
                m.put("readiness", readiness.toJson());
            }
            m.put("readyMarkers", readyMarkers);
            m.put("stopGraceSeconds", stopGraceSeconds);
            m.put("startupTimeoutSeconds", startupTimeoutMs / 1000);
            m.put("default", isDefault);
            return m;
        }
    }

    public static final class Readiness {
        public final String type; // http | none
        public final String host;
        public final String resolve; // fixed | portsFile | allocationsFile | range | configFile
        public final int port; // used when resolve == fixed (or probe start)
        public final String sourceFile; // for portsFile/allocationsFile/configFile: relative/absolute
        public final String sourceKey; // property key in the source file
        public final String path;
        public final String expect; // "200" | "any"

        Readiness(String type, String host, String resolve, int port, String sourceFile,
                String sourceKey, String path, String expect) {
            this.type = type;
            this.host = host;
            this.resolve = resolve;
            this.port = port;
            this.sourceFile = sourceFile;
            this.sourceKey = sourceKey;
            this.path = path;
            this.expect = expect;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("type", type);
            m.put("host", host);
            m.put("resolve", resolve);
            m.put("port", port);
            m.put("sourceFile", sourceFile);
            m.put("sourceKey", sourceKey);
            m.put("path", path);
            m.put("expect", expect);
            return m;
        }
    }

    public static final class TestSuite {
        public final String id;
        public final String label;
        public final List<String> cmd;
        public final Map<String, String> env;
        public final long timeoutMs;

        TestSuite(String id, String label, List<String> cmd, Map<String, String> env, long timeoutMs) {
            this.id = id;
            this.label = label;
            this.cmd = cmd;
            this.env = env;
            this.timeoutMs = timeoutMs;
        }
    }

    public static final class CommandBlock {
        public final List<String> cmd;
        public final Map<String, String> env;
        public final String workDir;
        public final long timeoutMs;
        public final int expectedModules;
        public final boolean supported;

        CommandBlock(List<String> cmd, Map<String, String> env, String workDir,
                long timeoutMs, int expectedModules, boolean supported) {
            this.cmd = cmd;
            this.env = env;
            this.workDir = workDir;
            this.timeoutMs = timeoutMs;
            this.expectedModules = expectedModules;
            this.supported = supported;
        }
    }

    public static final class Project {
        public final String id;
        public final String name;
        public final String description;
        public final String repoDir;
        public final String repoUrl;
        public final String type; // adapter id
        public final String detectionConfidence; // DETECTED | PROPOSED | USER_CONFIRMED | UNKNOWN | UNSUPPORTED
        public final String detectionSource;
        public final List<String> detectionEvidence;
        public final Map<String, String> git; // branch, head (snapshot; live data via GitOps)
        public final List<String> startupAfter; // dependency hints for profiles
        public final List<String> logLocations;
        public final List<String> configLocations;
        public final List<String> modelDirectories;
        public final String apiBase;
        public final boolean enrMonitor;
        public final List<String> warnings;

        public final Map<String, CommandBlock> operations; // build|cleanBuild|update|test?|install|package|verify
        public final List<TestSuite> testSuites;
        public final boolean testContinueOnFailure;
        public final List<LaunchConfig> launchConfigs;

        Project(String id, String name, String description, String repoDir, String repoUrl,
                String type, String detectionConfidence, String detectionSource,
                List<String> detectionEvidence, Map<String, String> git,
                List<String> startupAfter, List<String> logLocations, List<String> configLocations,
                List<String> modelDirectories, String apiBase, boolean enrMonitor,
                List<String> warnings, Map<String, CommandBlock> operations,
                List<TestSuite> testSuites, boolean testContinueOnFailure,
                List<LaunchConfig> launchConfigs) {
            this.id = id;
            this.name = name;
            this.description = description;
            this.repoDir = repoDir;
            this.repoUrl = repoUrl;
            this.type = type;
            this.detectionConfidence = detectionConfidence;
            this.detectionSource = detectionSource;
            this.detectionEvidence = detectionEvidence;
            this.git = git;
            this.startupAfter = startupAfter;
            this.logLocations = logLocations;
            this.configLocations = configLocations;
            this.modelDirectories = modelDirectories;
            this.apiBase = apiBase;
            this.enrMonitor = enrMonitor;
            this.warnings = warnings;
            this.operations = operations;
            this.testSuites = testSuites;
            this.testContinueOnFailure = testContinueOnFailure;
            this.launchConfigs = launchConfigs;
        }

        public LaunchConfig launch(String id) {
            if (id == null) {
                return defaultLaunch();
            }
            for (LaunchConfig lc : launchConfigs) {
                if (lc.id.equals(id)) {
                    return lc;
                }
            }
            return defaultLaunch();
        }

        public LaunchConfig defaultLaunch() {
            for (LaunchConfig lc : launchConfigs) {
                if (lc.isDefault) {
                    return lc;
                }
            }
            return launchConfigs.isEmpty() ? null : launchConfigs.get(0);
        }

        public CommandBlock op(String key) {
            return operations.get(key);
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("description", description);
            m.put("repoDir", repoDir);
            m.put("repoUrl", repoUrl);
            m.put("type", type);
            m.put("detectionConfidence", detectionConfidence);
            m.put("detectionSource", detectionSource);
            m.put("detectionEvidence", detectionEvidence);
            m.put("git", git);
            m.put("startupAfter", startupAfter);
            m.put("logLocations", logLocations);
            m.put("configLocations", configLocations);
            m.put("modelDirectories", modelDirectories);
            m.put("apiBase", apiBase);
            m.put("enrMonitor", enrMonitor);
            m.put("warnings", warnings);
            Map<String, Object> ops = new LinkedHashMap<>();
            for (Map.Entry<String, CommandBlock> e : operations.entrySet()) {
                ops.put(e.getKey(), blockJson(e.getValue()));
            }
            m.put("operations", ops);
            List<Map<String, Object>> suites = new ArrayList<>();
            for (TestSuite s : testSuites) {
                Map<String, Object> sm = new LinkedHashMap<>();
                sm.put("id", s.id);
                sm.put("label", s.label);
                sm.put("cmd", s.cmd);
                sm.put("env", s.env);
                sm.put("timeoutMinutes", s.timeoutMs / 60000);
                suites.add(sm);
            }
            m.put("test", Map.of("continueOnFailure", testContinueOnFailure, "suites", suites));
            List<Map<String, Object>> lcs = new ArrayList<>();
            for (LaunchConfig lc : launchConfigs) {
                lcs.add(lc.toJson());
            }
            m.put("launchConfigs", lcs);
            return m;
        }

        private static Map<String, Object> blockJson(CommandBlock b) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("cmd", b.cmd);
            m.put("env", b.env);
            m.put("workDir", b.workDir);
            m.put("timeoutMinutes", b.timeoutMs / 60000);
            m.put("expectedModules", b.expectedModules);
            m.put("supported", b.supported);
            return m;
        }
    }

    // ----------------------------------------------------------------- profile

    public static final class Profile {
        public final String id;
        public final String name;
        public final List<String> projects; // selected projects
        public final List<String> startupOrder;
        public final List<String> stopOrder;
        public final Map<String, String> environment;

        Profile(String id, String name, List<String> projects, List<String> startupOrder,
                List<String> stopOrder, Map<String, String> environment) {
            this.id = id;
            this.name = name;
            this.projects = projects;
            this.startupOrder = startupOrder;
            this.stopOrder = stopOrder;
            this.environment = environment;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("projects", projects);
            m.put("startupOrder", startupOrder);
            m.put("stopOrder", stopOrder);
            m.put("environment", environment);
            return m;
        }
    }

    // ----------------------------------------------------------------- workspace

    public Path workspaceFile;
    public String workspaceId;
    public volatile String workspaceName;
    public volatile String workspaceDescription;
    public final List<Project> projects = new ArrayList<>();
    public final List<Profile> profiles = new ArrayList<>();
    public volatile Map<String, Object> dashboard = new LinkedHashMap<>();

    /** Creates a new in-memory workspace config (used by WsStore.create). */
    static Config create(Path workspaceFile, String workspaceId, String workspaceName,
            String workspaceDescription) {
        Config c = new Config();
        c.workspaceFile = workspaceFile;
        c.workspaceId = workspaceId;
        c.workspaceName = workspaceName;
        c.workspaceDescription = workspaceDescription;
        return c;
    }

    private Config() {
        this.workspaceFile = null;
        this.workspaceId = "";
        this.workspaceName = "";
        this.workspaceDescription = "";
    }

    private Config(Path workspaceFile, String workspaceId, String workspaceName,
            String workspaceDescription) {
        this.workspaceFile = workspaceFile;
        this.workspaceId = workspaceId;
        this.workspaceName = workspaceName;
        this.workspaceDescription = workspaceDescription;
    }

    public Project project(String id) {
        for (Project p : projects) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    public Profile profile(String id) {
        for (Profile p : profiles) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- load/save

    public static Config load(Path workspaceFile) throws IOException {
        String text = Files.readString(workspaceFile);
        Object parsed = Json.parse(Json.stripComments(text));
        Map<String, Object> root = Json.obj(parsed);
        Map<String, Object> ws = Json.objField(root, "workspace");
        if (ws == null) {
            throw new IllegalArgumentException("Missing 'workspace' section");
        }
        Config c = new Config(workspaceFile,
                Json.str(ws, "id", "workspace"),
                Json.str(ws, "name", "Workspace"),
                Json.str(ws, "description", ""));

        List<String> seen = new ArrayList<>();
        for (Map<String, Object> p : Json.objList(root, "projects")) {
            Project project = buildProject(p);
            if (seen.contains(project.id)) {
                throw new IllegalArgumentException("Duplicate project id: " + project.id);
            }
            seen.add(project.id);
            c.projects.add(project);
        }
        for (Map<String, Object> p : Json.objList(root, "profiles")) {
            c.profiles.add(new Profile(
                    Json.str(p, "id", "profile"),
                    Json.str(p, "name", Json.str(p, "id", "profile")),
                    Json.strList(p, "projects"),
                    Json.strList(p, "startupOrder"),
                    Json.strList(p, "stopOrder"),
                    strMap(p, "environment")));
        }
        Map<String, Object> dash = Json.objField(root, "dashboard");
        if (dash != null) {
            c.dashboard = dash;
        }
        return c;
    }

    private static Map<String, String> strMap(Map<String, Object> p, String key) {
        Map<String, String> out = new LinkedHashMap<>();
        Map<String, Object> env = Json.objField(p, key);
        if (env != null) {
            for (Map.Entry<String, Object> e : env.entrySet()) {
                if (e.getValue() instanceof String s) {
                    out.put(e.getKey(), s);
                }
            }
        }
        return out;
    }

    private static Project buildProject(Map<String, Object> p) {
        String id = Json.str(p, "id");
        if (id == null) {
            throw new IllegalArgumentException("Project without id");
        }
        String repoDir = App.expandHome(Json.str(p, "repoDir"));
        Map<String, CommandBlock> ops = new LinkedHashMap<>();
        Map<String, Object> operations = Json.objField(p, "operations");
        if (operations != null) {
            for (Map.Entry<String, Object> e : operations.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> mm) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> b = (Map<String, Object>) mm;
                    ops.put(e.getKey(), new CommandBlock(
                            Json.strList(b, "cmd"),
                            strMap(b, "env"),
                            Json.str(b, "workDir", "repo"),
                            Json.integer(b, "timeoutMinutes", 120) * 60000L,
                            Json.integer(b, "expectedModules", 0),
                            Json.bool(b, "supported", true)));
                }
            }
        }
        List<TestSuite> suites = new ArrayList<>();
        boolean continueOnFailure = false;
        Map<String, Object> test = Json.objField(p, "test");
        if (test != null) {
            continueOnFailure = Json.bool(test, "continueOnFailure", false);
            for (Map<String, Object> s : Json.objList(test, "suites")) {
                suites.add(new TestSuite(
                        Json.str(s, "id", "suite"),
                        Json.str(s, "label", Json.str(s, "id", "suite")),
                        Json.strList(s, "cmd"),
                        strMap(s, "env"),
                        Json.integer(s, "timeoutMinutes", 120) * 60000L));
            }
        }
        List<LaunchConfig> lcs = new ArrayList<>();
        for (Map<String, Object> l : Json.objList(p, "launchConfigs")) {
            Map<String, Object> r = Json.objField(l, "readiness");
            Readiness readiness = null;
            if (r != null) {
                readiness = new Readiness(
                        Json.str(r, "type", "http"),
                        Json.str(r, "host", "127.0.0.1"),
                        Json.str(r, "resolve", "fixed"),
                        Json.integer(r, "port", 0),
                        Json.str(r, "sourceFile", ""),
                        Json.str(r, "sourceKey", ""),
                        Json.str(r, "path", "/"),
                        Json.str(r, "expect", "200"));
            }
            lcs.add(new LaunchConfig(
                    Json.str(l, "id", "run"),
                    Json.str(l, "name", Json.str(l, "id", "run")),
                    Json.str(l, "kind", "custom-command"),
                    Json.str(l, "launcher", ""),
                    Json.strList(l, "args"),
                    strMap(l, "env"),
                    Json.str(l, "workDir", "repo"),
                    Json.integer(l, "port", 0),
                    Json.bool(l, "portFixed", true),
                    Json.bool(l, "requiresDisplay", false),
                    readiness,
                    Json.strList(l, "readyMarkers"),
                    Json.integer(l, "stopGraceSeconds", 20),
                    Json.integer(l, "startupTimeoutSeconds", 180) * 1000L,
                    Json.bool(l, "default", lcs.isEmpty())));
        }
        return new Project(
                id,
                Json.str(p, "name", id),
                Json.str(p, "description", ""),
                repoDir,
                Json.str(p, "repoUrl", ""),
                Json.str(p, "type", "custom"),
                Json.str(p, "detectionConfidence", "UNKNOWN"),
                Json.str(p, "detectionSource", "configuration"),
                Json.strList(p, "detectionEvidence"),
                strMap(p, "git"),
                Json.strList(p, "startupAfter"),
                expandList(Json.strList(p, "logLocations")),
                expandList(Json.strList(p, "configLocations")),
                expandList(Json.strList(p, "modelDirectories")),
                Json.str(p, "apiBase", ""),
                Json.bool(p, "enrMonitor", false),
                Json.strList(p, "warnings"),
                ops,
                suites,
                continueOnFailure,
                lcs);
    }

    private static List<String> expandList(List<String> in) {
        List<String> out = new ArrayList<>();
        for (String s : in) {
            out.add(App.expandHome(s));
        }
        return out;
    }

    public synchronized void save() throws IOException {
        Files.createDirectories(workspaceFile.getParent());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("$schema", "project-manager.schema.json");
        root.put("version", CONFIG_VERSION);
        Map<String, Object> ws = new LinkedHashMap<>();
        ws.put("id", workspaceId);
        ws.put("name", workspaceName);
        ws.put("description", workspaceDescription);
        root.put("workspace", ws);
        List<Map<String, Object>> plist = new ArrayList<>();
        for (Project p : projects) {
            plist.add(p.toJson());
        }
        root.put("projects", plist);
        List<Map<String, Object>> profs = new ArrayList<>();
        for (Profile pr : profiles) {
            profs.add(pr.toJson());
        }
        root.put("profiles", profs);
        root.put("dashboard", dashboard.isEmpty() ? Dash.defaultLayoutMap() : dashboard);
        Files.writeString(workspaceFile, Json.write(root));
    }

    /** Validation: returns list of errors; empty means valid. */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();
        List<String> ids = new ArrayList<>();
        // Pass 1: collect ids and check id-level integrity.
        for (Project p : projects) {
            if (ids.contains(p.id)) {
                errors.add("Duplicate project id: " + p.id);
            }
            ids.add(p.id);
            if (!p.id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
                errors.add("Invalid project id: " + p.id);
            }
        }
        // Pass 2: project-level checks (dependencies may appear in any order).
        for (Project p : projects) {
            if (p.repoDir == null || p.repoDir.isBlank()) {
                errors.add("Project " + p.id + ": missing repoDir");
            } else if (!Files.isDirectory(Path.of(p.repoDir))) {
                errors.add("Project " + p.id + ": repository directory not found: " + p.repoDir);
            }
            if (!Adapters.exists(p.type)) {
                errors.add("Project " + p.id + ": unknown adapter type '" + p.type + "'");
            }
            List<String> lcIds = new ArrayList<>();
            for (LaunchConfig lc : p.launchConfigs) {
                if (lcIds.contains(lc.id)) {
                    errors.add("Project " + p.id + ": duplicate launch config id " + lc.id);
                }
                lcIds.add(lc.id);
                if (lc.launcher == null || lc.launcher.isBlank()) {
                    errors.add("Project " + p.id + ": launch config " + lc.id + " has no launcher");
                }
            }
            for (String dep : p.startupAfter) {
                if (!ids.contains(dep)) {
                    errors.add("Project " + p.id + ": dependency references unknown project " + dep);
                }
            }
        }
        for (Profile pr : profiles) {
            for (String pid : pr.projects) {
                if (!ids.contains(pid)) {
                    errors.add("Profile " + pr.id + ": unknown project " + pid);
                }
            }
            for (String pid : pr.startupOrder) {
                if (!ids.contains(pid)) {
                    errors.add("Profile " + pr.id + ": startupOrder references unknown project " + pid);
                }
            }
        }
        return errors;
    }

    private boolean hasCycle(Profile pr) {
        // Conservative: topological check over selected projects using startupAfter.
        Map<String, Integer> visited = new LinkedHashMap<>();
        for (Project p : projects) {
            if (!pr.projects.contains(p.id)) {
                continue;
            }
            if (visit(p.id, pr, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean visit(String id, Profile pr, Map<String, Integer> visited) {
        if (visited.getOrDefault(id, 0) == 1) {
            return true;
        }
        if (visited.getOrDefault(id, 0) == 2) {
            return false;
        }
        visited.put(id, 1);
        Project p = project(id);
        if (p != null) {
            for (String dep : p.startupAfter) {
                Project d = project(dep);
                if (d != null && pr.projects.contains(dep) && visit(dep, pr, visited)) {
                    return true;
                }
            }
        }
        visited.put(id, 2);
        return false;
    }

    public List<String> dependencyCycle(Profile pr) {
        List<String> cycles = new ArrayList<>();
        for (Project p : projects) {
            if (pr.projects.contains(p.id) && hasCycle(pr)) {
                cycles.add(p.id);
            }
        }
        return cycles;
    }
}
