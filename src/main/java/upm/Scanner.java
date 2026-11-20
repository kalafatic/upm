package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * External project discovery: recursive, read-only scanning of user-selected
 * roots with exclusion rules, plus remote repository inspection. Results are
 * persisted per workspace (discovery.jsonc) so rescans never create
 * duplicate registrations and ignored entries stay ignored.
 */
public final class Scanner {

    public static final List<String> DEFAULT_EXCLUSIONS = List.of(
            ".git", "node_modules", "target", "build", "dist", "out",
            ".gradle", ".idea", ".vscode", "bin", "obj", ".cache",
            "__pycache__", ".venv", "venv", "generated", "coverage");

    private final WsStore store;
    private final String workspaceId;
    private final Path discoveryFile;

    public Scanner(WsStore store, String workspaceId) {
        this.store = store;
        this.workspaceId = workspaceId;
        this.discoveryFile = store.workspaceDir(workspaceId).resolve("discovery.jsonc");
    }

    // ------------------------------------------------------------ persistence

    public synchronized Map<String, Object> loadState() {
        try {
            if (Files.exists(discoveryFile)) {
                return Json.obj(Json.parse(Json.stripComments(Files.readString(discoveryFile))));
            }
        } catch (Exception e) {
            Main.log("WARN", "discovery.jsonc unreadable: " + e.getMessage());
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("scanRoots", new ArrayList<String>());
        m.put("exclusions", new ArrayList<>(DEFAULT_EXCLUSIONS));
        m.put("ignored", new ArrayList<String>());
        m.put("results", new ArrayList<Map<String, Object>>());
        return m;
    }

    public synchronized void saveState(Map<String, Object> state) {
        try {
            Files.createDirectories(discoveryFile.getParent());
            Files.writeString(discoveryFile, Json.write(state));
        } catch (IOException e) {
            Main.log("ERROR", "Could not persist discovery state: " + e.getMessage());
        }
    }

    private static String normKey(String path) {
        try {
            return Path.of(path).toAbsolutePath().normalize().toString();
        } catch (Exception e) {
            return path;
        }
    }

    // -------------------------------------------------------------- scanning

    /** Recursively scans a user-selected root for candidate repositories/projects. */
    public synchronized Map<String, Object> scanRoot(String root, int maxDepth,
            List<String> exclusions, String workspaceIdForResults) {
        Map<String, Object> state = loadState();
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> found = new ArrayList<>();
        int scanned = 0;
        List<String> ex = exclusions == null || exclusions.isEmpty()
                ? new ArrayList<>(DEFAULT_EXCLUSIONS) : exclusions;
        try {
            scanned = walk(Path.of(App.expandHome(root)), maxDepth, ex, found, new ArrayList<>());
        } catch (Exception e) {
            result.put("error", e.getMessage());
            return result;
        }
        // Merge with persistence: registered / ignored flags, last scan time.
        List<String> ignored = Json.strList(state, "ignored");
        List<String> roots = Json.strList(state, "scanRoots");
        String rootKey = normKey(root);
        if (!roots.contains(rootKey)) {
            roots.add(rootKey);
        }
        state.put("scanRoots", roots);
        state.put("exclusions", ex);
        state.put("lastScanMs", System.currentTimeMillis());
        List<Map<String, Object>> cached = Json.objList(state, "results");
        Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
        for (Map<String, Object> c : cached) {
            byKey.put(Json.str(c, "path", ""), c);
        }
        List<Map<String, Object>> results = new ArrayList<>();
        for (Map<String, Object> f : found) {
            String path = Json.str(f, "path");
            String key = normKey(path);
            f.put("ignored", ignored.contains(key));
            f.put("registered", isRegistered(path));
            f.put("registeredId", registeredId(path));
            results.add(f);
            byKey.remove(key);
        }
        state.put("results", results);
        saveState(state);
        result.put("scannedDirectories", scanned);
        result.put("found", results);
        result.put("scanRoots", roots);
        result.put("exclusions", ex);
        return result;
    }

    private int walk(Path dir, int maxDepth, List<String> exclusions,
            List<Map<String, Object>> found, List<String> seen) throws IOException {
        int count = 0;
        if (maxDepth <= 0 || !Files.isDirectory(dir)) {
            return count;
        }
        if (Files.exists(dir.resolve(".git"))
                || (Files.isRegularFile(dir.resolve(".git")))) {
            // Repository root (or worktree link): record once, do not descend.
            String key = normKey(dir.toString());
            if (!seen.contains(key)) {
                seen.add(key);
                found.add(summarize(dir, "repository"));
            }
            return count + 1;
        }
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if (exclusions.contains(name) || name.endsWith(".git")) {
                    continue;
                }
                if (Files.isDirectory(child)) {
                    if (Files.exists(child.resolve(".git"))) {
                        String key = normKey(child.toString());
                        if (!seen.contains(key)) {
                            seen.add(key);
                            found.add(summarize(child, "repository"));
                        }
                        count++;
                    } else if (hasBuildDescriptor(child)) {
                        String key = normKey(child.toString());
                        if (!seen.contains(key)) {
                            seen.add(key);
                            found.add(summarize(child, "project"));
                        }
                        count++;
                    } else {
                        count += walk(child, maxDepth - 1, exclusions, found, seen);
                    }
                }
            }
        }
        return count;
    }

    private boolean hasBuildDescriptor(Path dir) {
        return Files.exists(dir.resolve("pom.xml"))
                || Files.exists(dir.resolve("build.gradle"))
                || Files.exists(dir.resolve("build.gradle.kts"))
                || Files.exists(dir.resolve("settings.gradle"))
                || Files.exists(dir.resolve("CMakeLists.txt"))
                || Files.exists(dir.resolve("Cargo.toml"))
                || Files.exists(dir.resolve("go.mod"))
                || Files.exists(dir.resolve("pyproject.toml"))
                || Files.exists(dir.resolve("package.json"))
                || Files.exists(dir.resolve("Makefile"))
                || Files.exists(dir.resolve("Dockerfile"));
    }

    /** Summarizes one discovered candidate (read-only). */
    private Map<String, Object> summarize(Path dir, String kind) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("path", dir.toString());
        m.put("name", dir.getFileName() == null ? dir.toString() : dir.getFileName().toString());
        m.put("kind", kind);
        Adapters.DetectionResult d = Adapters.detect(dir);
        m.put("typeId", d.typeId);
        m.put("confidence", d.confidence);
        m.put("evidence", d.evidence);
        List<String> modules = Adapters.detectModules(dir, d.typeId);
        m.put("modules", modules);
        m.put("moduleCount", modules.size());
        m.put("launchCapabilities", d.proposedLaunchConfigs.stream()
                .map(l -> l.id).toList());
        try {
            Map<String, Object> structure = Adapters.detectStructure(dir, d.typeId);
            m.put("parentPom", structure.get("parentPom"));
            m.put("parentIsRepoRoot", structure.get("parentIsRepoRoot"));
            m.put("features", structure.get("features"));
            m.put("products", structure.get("products"));
            m.put("targetPlatforms", structure.get("targetPlatforms"));
            m.put("eclipseLaunchConfigs", structure.get("eclipseLaunchConfigs"));
            m.put("requiresJava", structure.get("requiresJava"));
        } catch (Exception e) {
            Main.log("WARN", "structure scan failed for " + dir + ": " + e.getMessage());
        }
        Map<String, Object> g = GitOps.status(dir);
        m.put("git", g.get("isRepo") == Boolean.TRUE);
        if (g.get("isRepo") == Boolean.TRUE) {
            m.put("branch", g.get("branch"));
            m.put("head", g.get("head"));
            m.put("remotes", g.get("remotes"));
            m.put("dirty", g.get("dirty"));
        }
        return m;
    }

    // ------------------------------------------------------ remote inspection

    /** Remote inspection without cloning (ls-remote + provider metadata). */
    public Map<String, Object> inspectRemote(String url) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("url", url);
        if (!GitOps.validUrl(url)) {
            m.put("error", "Not a valid Git URL");
            return m;
        }
        if (!GitOps.installed()) {
            m.put("error", "Git CLI is not installed");
            return m;
        }
        GitOps.Result ls = GitOps.lsRemote(url);
        if (!ls.ok()) {
            m.put("error", "Repository not reachable: " + ls.output().strip());
            return m;
        }
        m.put("reachable", true);
        m.put("defaultBranch", GitOps.defaultBranchFrom(ls.output()));
        // Branch listing from ls-remote refs.
        List<String> branches = new ArrayList<>();
        for (String line : ls.output().split("\n")) {
            String[] parts = line.strip().split("\\s+");
            if (parts.length < 2) {
                continue;
            }
            String ref = parts[1];
            if (ref.startsWith("refs/heads/")) {
                branches.add(ref.substring("refs/heads/".length()));
            }
        }
        m.put("branches", branches.stream().distinct().limit(40).toList());
        return m;
    }

    /** Moved-repo detection: registered project path missing + same name found elsewhere. */
    public List<Map<String, Object>> findMovedProjects(Config cfg) {
        List<Map<String, Object>> out = new ArrayList<>();
        Map<String, Object> state = loadState();
        List<Map<String, Object>> results = Json.objList(state, "results");
        for (Config.Project p : cfg.projects) {
            if (p.repoDir != null && Files.isDirectory(Path.of(p.repoDir))) {
                continue;
            }
            String name = new java.io.File(p.repoDir == null ? "" : p.repoDir).getName();
            for (Map<String, Object> r : results) {
                String rpath = Json.str(r, "path", "");
                String rname = new java.io.File(rpath).getName();
                if (name.equals(rname) && Files.isDirectory(Path.of(rpath))) {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("projectId", p.id);
                    m.put("oldPath", p.repoDir);
                    m.put("candidatePath", rpath);
                    out.add(m);
                }
            }
        }
        return out;
    }

    /** Remaps a registered project to a new path (explicit user action). */
    public synchronized String remapProject(Config cfg, String projectId, String newPath) {
        Config.Project p = cfg.project(projectId);
        if (p == null) {
            return "Unknown project: " + projectId;
        }
        if (!Files.isDirectory(Path.of(App.expandHome(newPath)))) {
            return "New path does not exist: " + newPath;
        }
        String newRepo = App.expandHome(newPath);
        Config.Project updated = new Config.Project(p.id, p.name, p.description, newRepo,
                p.repoUrl, p.type, "USER_CONFIRMED", "remap", p.detectionEvidence, p.git,
                p.startupAfter, p.logLocations, p.configLocations, p.modelDirectories,
                p.apiBase, p.enrMonitor, p.warnings, p.operations, p.testSuites,
                p.testContinueOnFailure, p.launchConfigs);
        synchronized (cfg) {
            for (int i = 0; i < cfg.projects.size(); i++) {
                if (cfg.projects.get(i).id.equals(projectId)) {
                    cfg.projects.set(i, updated);
                    break;
                }
            }
            try {
                cfg.save();
            } catch (IOException e) {
                return "Could not save workspace: " + e.getMessage();
            }
        }
        return null;
    }

    /** Toggles the ignore flag for a discovered path. */
    public synchronized Map<String, Object> setIgnored(String path, boolean ignore) {
        Map<String, Object> state = loadState();
        List<String> ignored = new ArrayList<>(Json.strList(state, "ignored"));
        String key = normKey(path);
        ignored.remove(key);
        if (ignore) {
            ignored.add(key);
        }
        state.put("ignored", ignored);
        saveState(state);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("ignored", ignored);
        return m;
    }

    private boolean isRegistered(String path) {
        return registeredId(path) != null;
    }

    private String registeredId(String path) {
        if (Main.currentConfig == null) {
            return null;
        }
        String key = normKey(path);
        for (Config.Project p : Main.currentConfig.projects) {
            if (p.repoDir != null && normKey(p.repoDir).equals(key)) {
                return p.id;
            }
            // Subdirectory registrations (module of a discovered repository).
            if (p.repoDir != null && key.startsWith(normKey(p.repoDir) + "/")) {
                return p.id;
            }
        }
        return null;
    }
}
