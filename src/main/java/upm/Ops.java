package upm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Operation execution engine. Adapter-defined commands are executed here —
 * the engine owns process spawning, streaming, parsing, cancellation and
 * history; adapters only supply commands and capabilities.
 */
public final class Ops {

    private static final int MAX_LIVE_BUFFER = 512 * 1024;

    private final App app;
    private final State state;
    private final Proc proc;
    private final Map<Long, Thread> threads = new ConcurrentHashMap<>();
    private final Map<String, String> projectOp = new ConcurrentHashMap<>();

    private static final Pattern BUILDING = Pattern.compile("\\[INFO\\] Building\\s+([^\\s]+)(?:\\s+(\\d+)/(\\d+))?.*");
    private static final Pattern TESTS_RUN = Pattern.compile(
            "Tests run:\\s*(\\d+),\\s*Failures:\\s*(\\d+),\\s*Errors:\\s*(\\d+),\\s*Skipped:\\s*(\\d+)");

    public Ops(App app, State state, Proc proc) {
        this.app = app;
        this.state = state;
        this.proc = proc;
    }

    // ------------------------------------------------------------- submission

    public synchronized String submit(String projectId, String type, String mode,
            Integer port, Map<String, Object> extra) {
        boolean global = "launchAll".equals(type) || "stopAll".equals(type)
                || "restartAll".equals(type);
        boolean profileOp = "profileLaunch".equals(type) || "profileStop".equals(type);
        Config cfg = Main.currentConfig;
        if (!global && !profileOp) {
            if (cfg == null || cfg.project(projectId) == null) {
                return "{\"error\":\"Unknown project: " + projectId + "\"}";
            }
            if ("run".equals(type)) {
                Config.Project p = cfg.project(projectId);
                Config.LaunchConfig lc = p.launch(mode);
                if (lc == null) {
                    return "{\"error\":\"Unsupported launch configuration '" + mode
                            + "' for " + projectId + " (available: "
                            + String.join(", ",
                                    p.launchConfigs.stream().map(l -> l.id).toList()) + ")\"}";
                }
                String current = projectOp.get(projectId);
                if (current != null && !current.equals("stop") && !current.equals("restart")) {
                    return "{\"error\":\"Project " + projectId + " already has an active operation ("
                            + current + ").\"}";
                }
                if (state.stateFor(projectId).runtimeState.equals("STOPPING")) {
                    return "{\"error\":\"Project " + projectId + " is stopping; wait until STOPPED.\"}";
                }
            } else if ("stop".equals(type) || "restart".equals(type)) {
                // allowed alongside runs
            } else {
                String current = projectOp.get(projectId);
                if (current != null) {
                    return "{\"error\":\"Project " + projectId + " already has an active operation ("
                            + current + ").\"}";
                }
                State.ProjectState ps = state.stateFor(projectId);
                if (("build".equals(type) || "cleanBuild".equals(type) || "update".equals(type)
                        || "test".equals(type) || "package".equals(type) || "verify".equals(type)
                        || "install".equals(type) || "validate".equals(type)
                        || "buildModule".equals(type)
                        || "buildProduct".equals(type) || "buildFeatures".equals(type)
                        || "buildPlugins".equals(type)
                        || "exportProduct".equals(type) || "exportLinux".equals(type)
                        || "exportWindows".equals(type) || "exportMacosx".equals(type)
                        || "exportAll".equals(type) || "buildP2Repository".equals(type)
                        || "publishUpdateSite".equals(type))
                        && (ps.runtimeState.equals("STARTING") || ps.runtimeState.equals("RUNNING")
                        || ps.runtimeState.equals("READY"))) {
                    return "{\"error\":\"Project " + projectId + " runtime is "
                            + ps.runtimeState + "; stop it before building.\"}";
                }
            }
        }
        State.Op op = state.newOp(projectId, type, mode);
        Thread t = new Thread(() -> execute(op, type, mode, port, extra), "upm-op-" + op.id);
        t.setDaemon(true);
        threads.put(op.id, t);
        if (!global && !profileOp) {
            projectOp.put(projectId, type);
        }
        t.start();
        return "{\"opId\":" + op.id + "}";
    }

    private void release(State.Op op) {
        if (!("launchAll".equals(op.type) || "stopAll".equals(op.type)
                || "restartAll".equals(op.type))) {
            projectOp.remove(op.projectId, op.type);
        }
        threads.remove(op.id);
    }

    // --------------------------------------------------------------- execute

    private void execute(State.Op op, String type, String mode, Integer port,
            Map<String, Object> extra) {
        try {
            switch (type) {
                case "build", "cleanBuild", "update", "package", "verify", "install", "validate",
                        "buildProduct", "buildFeatures", "buildPlugins", "exportProduct",
                        "exportLinux", "exportWindows", "exportMacosx", "exportAll",
                        "buildP2Repository", "publishUpdateSite" ->
                        runAdapterOp(op, op.projectId, type);
                case "validateProduct" -> runValidateProduct(op, op.projectId);
                case "validateUpdateRepository" -> runValidateUpdateRepository(op, op.projectId);
                case "inspectVersions" -> runInspectVersions(op, op.projectId);
                case "buildModule" -> runBuildModule(op, op.projectId, extra);
                case "test" -> runTests(op, op.projectId);
                case "run" -> runRuntime(op, op.projectId, mode, port);
                case "stop" -> runStop(op, op.projectId);
                case "restart" -> runRestart(op, op.projectId, mode);
                case "launchAll" -> launchAll(op);
                case "stopAll" -> stopAll(op);
                case "restartAll" -> restartAll(op);
                case "profileLaunch" -> profileLaunch(op, mode);
                case "profileStop" -> profileStop(op, mode);
                case "gitFetch" -> runGit(op, op.projectId, "fetch", null);
                case "gitPull" -> runGit(op, op.projectId, "pull", null);
                case "gitPush" -> runGit(op, op.projectId, "push", null);
                case "gitCommit" -> runGit(op, op.projectId, "commit",
                        extra == null ? null : (String) extra.get("message"));
                case "checkUpdates" -> runCheckUpdates(op, op.projectId);
                case "updateAll" -> runUpdateAll(op, op.projectId, extra);
                case "ftest" -> runFuncTest(op, op.projectId, mode, extra);
                default -> state.finishOp(op, "FAILED", null, "Unknown operation type: " + type);
            }
        } catch (Throwable e) {
            state.finishOp(op, "FAILED", null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            release(op);
        }
    }

    // ------------------------------------------------------------ adapter ops

    private void runAdapterOp(State.Op op, String projectId, String kind) {
        Config.Project p = Main.currentConfig.project(projectId);
        Config.CommandBlock block = p.op(kind);
        if (block == null || !block.supported || block.cmd.isEmpty()) {
            state.finishOp(op, "FAILED", null, "Operation '" + kind
                    + "' is not configured for this project (adapter " + p.type + ").");
            return;
        }
        int exit = exec(op, p, block, kind, null, null);
        if (exit == Integer.MIN_VALUE) {
            return;
        }
        String what = opLabel(kind);
        if (exit == 0) {
            state.finishOp(op, "SUCCEEDED", what + " completed successfully.", null);
        } else {
            state.finishOp(op, "FAILED", what + " failed with exit code " + exit + ".",
                    lastErrorLine(op));
        }
    }

    private static String opLabel(String kind) {
        return switch (kind) {
            case "build" -> "Build";
            case "cleanBuild" -> "Clean build";
            case "update" -> "Dependency update";
            case "package" -> "Package";
            case "verify" -> "Verify";
            case "install" -> "Install";
            case "validate" -> "Validate";
            case "buildModule" -> "Module build";
            case "buildProduct" -> "Build product";
            case "validateProduct" -> "Validate product";
            case "buildFeatures" -> "Build features";
            case "buildPlugins" -> "Build plugins";
            case "exportProduct" -> "Export product";
            case "exportLinux" -> "Export Linux";
            case "exportWindows" -> "Export Windows";
            case "exportMacosx" -> "Export macOS";
            case "exportAll" -> "Export all platforms";
            case "buildP2Repository" -> "Build p2 repository";
            case "publishUpdateSite" -> "Publish update site";
            case "validateUpdateRepository" -> "Validate update repository";
            case "inspectVersions" -> "Inspect published versions";
            case "checkUpdates" -> "Check updates";
            case "updateAll" -> "Update all";
            default -> kind;
        };
    }

    // ----------------------------------------------------------- module build

    /** Builds one reactor module together with its required dependencies. */
    private void runBuildModule(State.Op op, String projectId, Map<String, Object> extra) {
        Config.Project p = Main.currentConfig.project(projectId);
        String module = extra == null ? null : Json.str(extra, "module");
        if (module == null || module.isBlank()) {
            state.finishOp(op, "FAILED", null, "buildModule requires a 'module' parameter.");
            return;
        }
        String norm = module.replace("\\", "/");
        while (norm.startsWith("./")) {
            norm = norm.substring(2);
        }
        boolean valid = false;
        for (String m : p.modules) {
            if (m.equals(norm)) {
                valid = true;
                break;
            }
        }
        if (!valid) {
            state.finishOp(op, "FAILED", null, "Module '" + module
                    + "' is not part of this project's reactor. Available: "
                    + String.join(", ", p.modules));
            return;
        }
        Config.CommandBlock base = p.op("build");
        if (base == null || base.cmd.isEmpty()) {
            state.finishOp(op, "FAILED", null, "No build command configured for this project.");
            return;
        }
        boolean withTests = extra != null
                && Boolean.TRUE.equals(extra.get("withTests"));
        String mvn = base.cmd.get(0);
        List<String> cmd = new ArrayList<>();
        cmd.add(mvn);
        cmd.add("-B");
        if (!withTests) {
            cmd.add("-DskipTests");
        }
        cmd.add("clean");
        cmd.add("verify");
        cmd.add("-pl");
        cmd.add(norm);
        cmd.add("-am");
        Config.CommandBlock block = new Config.CommandBlock(cmd, base.env, base.workDir,
                base.timeoutMs, 0, true);
        op.phase = "Module build";
        op.task = "Building module " + norm + " (with dependencies)";
        int exit = exec(op, p, block, "build", null, null);
        if (exit == Integer.MIN_VALUE) {
            return;
        }
        if (exit == 0) {
            state.finishOp(op, "SUCCEEDED", "Module build (" + norm + ") completed successfully.",
                    null);
        } else {
            state.finishOp(op, "FAILED", "Module build (" + norm + ") failed with exit code "
                    + exit + ".", lastErrorLine(op));
        }
    }

    // --------------------------------------------- product/update-site checks

    /** Validates .product definitions: application ids and feature references. */
    private void runValidateProduct(State.Op op, String projectId) {
        Config.Project p = Main.currentConfig.project(projectId);
        Path dir = Path.of(p.repoDir);
        List<String> products = Adapters.findPaths(dir, 6, "*.product");
        if (products.isEmpty()) {
            state.finishOp(op, "FAILED", null, "No .product files found under " + p.repoDir);
            return;
        }
        Set<String> appIds = new LinkedHashSet<>();
        for (String a : Adapters.findPaths(dir, 6, "plugin.xml")) {
            String xml = Adapters.readFirstLines(dir.resolve(a), 2000);
            Matcher m = Pattern.compile("<application\\s[^>]*id=\"([^\"]+)\"").matcher(xml);
            while (m.find()) {
                appIds.add(m.group(1));
            }
            // Application ids without an explicit id attribute are derived
            // from the bundle symbolic name + extension id.
            Matcher extBlock = Pattern.compile(
                    "<extension[^>]*point=\"org\\.eclipse\\.core\\.runtime\\.applications\"[^>]*>")
                    .matcher(xml);
            if (extBlock.find()) {
                String bundleId = null;
                Path manifest = dir.resolve(a).getParent().resolve("META-INF/MANIFEST.MF");
                if (Files.isRegularFile(manifest)) {
                    Matcher bsn = Pattern.compile("Bundle-SymbolicName:\\s*([^;\\s]+)")
                            .matcher(Adapters.readFirstLines(manifest, 100));
                    if (bsn.find()) {
                        bundleId = bsn.group(1);
                    }
                }
                Matcher eid = Pattern.compile("id=\"([^\"]+)\"").matcher(extBlock.group(0));
                String extId = eid.find() ? eid.group(1) : "application";
                // Some products use the full application id as the
                // extension id directly.
                if (extId.contains(".")) {
                    appIds.add(extId);
                }
                if (bundleId != null) {
                    appIds.add(bundleId + "." + extId);
                }
            }
        }
        Set<String> featureIds = new LinkedHashSet<>();
        for (String f : Adapters.findPaths(dir, 5, "feature.xml")) {
            Matcher m = Pattern.compile("<feature[^>]*id=\"([^\"]+)\"").matcher(
                    Adapters.readFirstLines(dir.resolve(f), 500));
            if (m.find()) {
                featureIds.add(m.group(1));
            }
        }
        List<String> results = new ArrayList<>();
        boolean ok = true;
        for (String prod : products) {
            String xml = Adapters.readFirstLines(dir.resolve(prod), 5000);
            Matcher uid = Pattern.compile("<product[^>]*uid=\"([^\"]+)\"").matcher(xml);
            Matcher app = Pattern.compile("<product[^>]*application=\"([^\"]+)\"").matcher(xml);
            Matcher ver = Pattern.compile("<product[^>]*version=\"([^\"]+)\"").matcher(xml);
            String uidS = uid.find() ? uid.group(1) : "?";
            String appS = app.find() ? app.group(1) : "";
            String verS = ver.find() ? ver.group(1) : "?";
            results.add("Product " + uidS + " (" + verS + ")");
            if (appS.isEmpty()) {
                results.add("  - missing application reference");
                ok = false;
            } else if (!appIds.contains(appS)) {
                results.add("  - application id not declared in any plugin.xml: " + appS);
                ok = false;
            } else {
                results.add("  + application " + appS + " declared");
            }
            Matcher feat = Pattern.compile("<feature id=\"([^\"]+)\"").matcher(xml);
            boolean foundFeature = false;
            while (feat.find()) {
                foundFeature = true;
                String fid = feat.group(1);
                if (featureIds.contains(fid) || fid.startsWith("org.eclipse.")) {
                    results.add("  + feature " + fid);
                } else {
                    results.add("  - feature not found in reactor (must resolve from target platform): " + fid);
                    ok = false;
                }
            }
            if (!foundFeature) {
                // Only <plugin> entries outside the <configurations> section
                // define the bundle-based product content.
                String content = xml;
                int cfgIdx = content.indexOf("<configurations");
                if (cfgIdx >= 0) {
                    content = content.substring(0, cfgIdx);
                }
                Matcher pl = Pattern.compile("<plugin id=\"([^\"]+)\"").matcher(content);
                boolean foundPlugin = false;
                while (pl.find()) {
                    foundPlugin = true;
                    results.add("  + bundle-based product entry: " + pl.group(1));
                }
                if (!foundPlugin) {
                    results.add("  - product contains neither features nor plugins");
                    ok = false;
                }
            }
        }
        state.finishOp(op, ok ? "SUCCEEDED" : "FAILED",
                ok ? "Product definitions valid (" + products.size() + " product file(s))."
                        : "Product validation failed — see details.",
                String.join("\n", results));
    }

    /** Collects built p2 repositories (target/repository) under the repo root. */
    private static List<Path> builtRepositories(Path dir) {
        List<Path> repos = new ArrayList<>();
        try (var stream = Files.walk(dir, 8)) {
            for (Path d : (Iterable<Path>) stream::iterator) {
                if (d.getFileName() != null && d.getFileName().toString().equals("repository")
                        && d.getParent() != null && d.getParent().getFileName() != null
                        && d.getParent().getFileName().toString().equals("target")) {
                    repos.add(d);
                }
            }
        } catch (IOException ignored) {
            // best effort
        }
        return repos;
    }

    /** Validates a built p2 repository (target/repository) structure. */
    private void runValidateUpdateRepository(State.Op op, String projectId) {
        Config.Project p = Main.currentConfig.project(projectId);
        Path dir = Path.of(p.repoDir);
        List<Path> repos = builtRepositories(dir);
        if (repos.isEmpty()) {
            state.finishOp(op, "FAILED", null,
                    "No built p2 repository found (build the product first).");
            return;
        }
        List<String> results = new ArrayList<>();
        boolean ok = true;
        for (Path r : repos) {
            results.add("Repository: " + dir.relativize(r));
            boolean hasContent = Files.exists(r.resolve("content.jar"))
                    || Files.exists(r.resolve("content.xml.xz"));
            boolean hasArtifacts = Files.exists(r.resolve("artifacts.jar"))
                    || Files.exists(r.resolve("artifacts.xml.xz"));
            results.add(hasContent ? "  + content metadata present" : "  - content metadata MISSING");
            results.add(hasArtifacts ? "  + artifact metadata present" : "  - artifact metadata MISSING");
            results.add(Files.isDirectory(r.resolve("features")) ? "  + features/ present" : "  - features/ MISSING");
            results.add(Files.isDirectory(r.resolve("plugins")) ? "  + plugins/ present" : "  - plugins/ MISSING");
            if (!(hasContent && hasArtifacts)) {
                ok = false;
            }
        }
        state.finishOp(op, ok ? "SUCCEEDED" : "FAILED",
                ok ? "Update repository valid (" + repos.size() + " repository/repositories checked)."
                        : "Update repository validation failed.",
                String.join("\n", results));
    }

    /** Lists IU versions published in the built p2 repository. */
    private void runInspectVersions(State.Op op, String projectId) {
        Config.Project p = Main.currentConfig.project(projectId);
        Path dir = Path.of(p.repoDir);
        List<Path> repos = builtRepositories(dir);
        if (repos.isEmpty()) {
            state.finishOp(op, "FAILED", null,
                    "No built p2 repository found (build the product first).");
            return;
        }
        Path repo = repos.get(0);
        List<String> lines = new ArrayList<>();
        boolean parsed = false;
        Path contentJar = repo.resolve("content.jar");
        if (Files.isRegularFile(contentJar)) {
            try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(contentJar.toFile())) {
                java.util.zip.ZipEntry entry = zf.getEntry("content.xml");
                if (entry != null) {
                    javax.xml.parsers.DocumentBuilder db = javax.xml.parsers.DocumentBuilderFactory
                            .newInstance().newDocumentBuilder();
                    org.w3c.dom.Document doc = db.parse(zf.getInputStream(entry));
                    org.w3c.dom.NodeList units = doc.getElementsByTagName("unit");
                    for (int i = 0; i < units.getLength(); i++) {
                        org.w3c.dom.Element u = (org.w3c.dom.Element) units.item(i);
                        String id = u.getAttribute("id");
                        if (id.startsWith("eu.kalafatic")) {
                            lines.add(id + " " + u.getAttribute("version"));
                        }
                    }
                    parsed = true;
                }
            } catch (Exception e) {
                lines.add("content.xml parse error: " + e.getMessage());
            }
        }
        if (!parsed) {
            lines.add("No content.jar/content.xml in " + dir.relativize(repo));
        }
        state.finishOp(op, parsed ? "SUCCEEDED" : "FAILED",
                parsed ? "Published product IUs in " + dir.relativize(repo) + ": " + lines.size()
                        : "Could not inspect published versions.",
                String.join("\n", lines));
    }

    // --------------------------------------------------------- update workflow

    /** Fetches remote changes and reports divergence WITHOUT touching the worktree. */
    private void runCheckUpdates(State.Op op, String projectId) {
        Config.Project p = Main.currentConfig.project(projectId);
        Path dir = Path.of(p.repoDir);
        op.phase = "Git";
        op.task = "Checking updates — " + p.repoDir;
        if (!GitOps.installed()) {
            state.finishOp(op, "FAILED", null, "Git CLI not found. Install Git first.");
            return;
        }
        if (!GitOps.isRepo(dir)) {
            state.finishOp(op, "FAILED", null, p.repoDir + " is not a Git repository.");
            return;
        }
        Map<String, Object> st = GitOps.status(dir);
        if (Boolean.TRUE.equals(st.get("dirty"))) {
            state.finishOp(op, "FAILED",
                    "Local changes present: " + st.get("modifiedCount") + " modified, "
                            + st.get("untrackedCount") + " untracked, "
                            + st.get("stagedCount") + " staged.",
                    "Check Updates only reports divergence; local changes were left untouched.");
            return;
        }
        GitOps.Result fetch = GitOps.fetch(dir);
        if (!fetch.ok()) {
            state.finishOp(op, "FAILED", "Fetch failed (rc " + fetch.rc() + ").",
                    fetch.error() + "\n" + fetch.output());
            return;
        }
        Map<String, Object> divergence = divergence(dir);
        StringBuilder sb = new StringBuilder();
        sb.append("Fetched remote refs successfully. ");
        sb.append("Branch: ").append(st.get("branch")).append(". ");
        long behind = Json.longVal(divergence, "behind", 0);
        long ahead = Json.longVal(divergence, "ahead", 0);
        sb.append(ahead).append(" outgoing / ").append(behind).append(" incoming commits.");
        if (behind == 0 && ahead == 0) {
            sb.append(" Working tree is up to date.");
        }
        state.finishOp(op, "SUCCEEDED", sb.toString(), null);
        state.notify("info", projectId, "Check updates", sb.toString(), "");
    }

    /**
     * Safe orchestrated update: inspect status, fetch, analyze divergence, and
     * integrate ONLY when authorized and safe. Never discards local changes,
     * never resets, never rebases, never switches branches.
     */
    private void runUpdateAll(State.Op op, String projectId, Map<String, Object> extra) {
        Config.Project p = Main.currentConfig.project(projectId);
        Path dir = Path.of(p.repoDir);
        List<String> steps = new ArrayList<>();
        boolean confirmed = extra != null && Boolean.TRUE.equals(extra.get("confirm"));
        boolean updateDeps = extra != null && Boolean.TRUE.equals(extra.get("updateDeps"));
        if (!GitOps.installed()) {
            state.finishOp(op, "FAILED", null, "Git CLI not found. Install Git first.");
            return;
        }
        if (!GitOps.isRepo(dir)) {
            state.finishOp(op, "FAILED", null, p.repoDir + " is not a Git repository.");
            return;
        }
        // 1. Inspect local state.
        op.phase = "Inspection";
        op.task = "Checking local state";
        Map<String, Object> st = GitOps.status(dir);
        steps.add("1. Git status inspected: branch=" + st.get("branch"));
        if (Boolean.TRUE.equals(st.get("dirty"))) {
            String detail = "Local changes present (" + st.get("modifiedCount") + " modified, "
                    + st.get("untrackedCount") + " untracked, " + st.get("stagedCount")
                    + " staged). Update All stopped; local changes were NOT discarded.";
            steps.add("2. STOPPED: " + detail);
            state.finishOp(op, "FAILED", "Update All stopped safely: local changes present.",
                    detail + "\n\nSteps:\n" + String.join("\n", steps));
            return;
        }
        // 2. Fetch.
        op.phase = "Fetch";
        op.task = "Fetching remote changes";
        GitOps.Result fetch = GitOps.fetch(dir);
        if (!fetch.ok()) {
            steps.add("2. STOPPED: fetch failed (rc " + fetch.rc() + ")");
            state.finishOp(op, "FAILED", "Update All stopped: fetch failed.",
                    fetch.error() + "\n" + fetch.output() + "\n\nSteps:\n"
                            + String.join("\n", steps));
            return;
        }
        steps.add("2. Remote refs fetched.");
        // 3. Analyze divergence.
        op.phase = "Divergence analysis";
        Map<String, Object> div = divergence(dir);
        long behind = Json.longVal(div, "behind", 0);
        long ahead = Json.longVal(div, "ahead", 0);
        boolean hasUpstream = Boolean.TRUE.equals(div.get("hasUpstream"));
        steps.add("3. Divergence: " + ahead + " outgoing, " + behind + " incoming"
                + (hasUpstream ? "" : " (no upstream configured)"));
        if (!hasUpstream) {
            state.finishOp(op, "FAILED",
                    "Update All stopped: no upstream branch configured.",
                    "Configure a tracking remote/branch first. Nothing was changed.\n\nSteps:\n"
                            + String.join("\n", steps));
            return;
        }
        if (ahead > 0) {
            state.finishOp(op, "FAILED",
                    "Update All stopped: local branch has " + ahead
                            + " unpushed commit(s).",
                    "Push or resolve the divergence first; no rebase/reset is ever performed "
                            + "automatically.\n\nSteps:\n" + String.join("\n", steps));
            return;
        }
        if (behind == 0) {
            steps.add("4. Working tree already up to date; no pull needed.");
            if (updateDeps) {
                steps.add("5. Dependency update requested; running project 'update' operation.");
                Config.CommandBlock block = p.op("update");
                if (block != null && block.supported && !block.cmd.isEmpty()) {
                    op.phase = "Dependencies";
                    int exit = exec(op, p, block, "update", null, null);
                    steps.add(exit == 0 ? "5. Dependencies updated." : "5. Dependency update FAILED (exit " + exit + ").");
                    if (exit != 0) {
                        state.finishOp(op, "FAILED", "Update All: dependencies failed to update.",
                                String.join("\n", steps));
                        return;
                    }
                } else {
                    steps.add("5. No dependency-update operation configured; skipped.");
                }
            }
            state.finishOp(op, "SUCCEEDED", "Update All: up to date" + (updateDeps
                    ? ", dependencies refreshed" : "") + ".", String.join("\n", steps));
            state.notify("success", projectId, "Update All", "Up to date; nothing to integrate.", "");
            return;
        }
        // 4. Integrate only with explicit confirmation.
        if (!confirmed) {
            steps.add("4. STOPPED: " + behind
                    + " incoming commit(s) available but integration was not confirmed.");
            state.finishOp(op, "FAILED",
                    "Update All stopped: " + behind
                            + " incoming commit(s) await your confirmation.",
                    "Re-run Update All with confirm=true to integrate via fast-forward pull.\n\n"
                            + "Steps:\n" + String.join("\n", steps));
            return;
        }
        op.phase = "Integration";
        op.task = "Integrating remote changes (fast-forward only)";
        GitOps.Result pull = GitOps.pull(dir);
        if (!pull.ok()) {
            steps.add("4. STOPPED: fast-forward pull failed (rc " + pull.rc()
                    + "); nothing was modified beyond a rejected pull attempt.");
            state.finishOp(op, "FAILED", "Update All stopped: integration failed.",
                    pull.error() + "\n" + pull.output() + "\n\nSteps:\n"
                            + String.join("\n", steps));
            return;
        }
        steps.add("4. Fast-forward pull succeeded.");
        // 5. Dependencies.
        if (updateDeps) {
            Config.CommandBlock block = p.op("update");
            if (block != null && block.supported && !block.cmd.isEmpty()) {
                op.phase = "Dependencies";
                op.task = "Updating dependencies";
                int exit = exec(op, p, block, "update", null, null);
                steps.add(exit == 0 ? "5. Dependencies updated." : "5. Dependency update FAILED (exit " + exit + ").");
                if (exit != 0) {
                    state.finishOp(op, "FAILED", "Update All: code updated but dependency "
                            + "update failed.", String.join("\n", steps));
                    return;
                }
            } else {
                steps.add("5. No dependency-update operation configured; skipped.");
            }
        }
        // 6. Validation (fast reactor validation when configured).
        Config.CommandBlock validate = p.op("validate");
        if (validate != null && validate.supported && !validate.cmd.isEmpty()) {
            op.phase = "Validation";
            op.task = "Validating integrated state";
            int vExit = exec(op, p, validate, "validate", null, null);
            steps.add(vExit == 0 ? "6. Validation passed." : "6. Validation FAILED (exit " + vExit + ").");
            if (vExit != 0) {
                state.finishOp(op, "FAILED", "Update All: code integrated but validation "
                        + "failed.", String.join("\n", steps));
                return;
            }
        } else {
            steps.add("6. No validation operation configured; run Build to verify the integrated state.");
        }
        state.finishOp(op, "SUCCEEDED", "Update All completed: integrated " + behind
                + " incoming commit(s)." + (updateDeps ? " Dependencies refreshed." : ""),
                String.join("\n", steps));
        state.notify("success", projectId, "Update All", "Integrated " + behind
                + " commit(s) via fast-forward.", "");
    }

    /** ahead/behind counts versus the upstream tracking branch. */
    private Map<String, Object> divergence(Path dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("hasUpstream", false);
        m.put("ahead", 0);
        m.put("behind", 0);
        GitOps.Result up = GitOps.execIn(dir, List.of("rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}"), 20);
        if (!up.ok()) {
            return m;
        }
        m.put("hasUpstream", true);
        m.put("upstream", up.output().strip());
        GitOps.Result counts = GitOps.execIn(dir,
                List.of("rev-list", "--left-right", "--count", "HEAD...@{upstream}"), 60);
        if (counts.ok()) {
            String[] parts = counts.output().strip().split("\\s+");
            if (parts.length == 2) {
                try {
                    m.put("ahead", Long.parseLong(parts[0]));
                    m.put("behind", Long.parseLong(parts[1]));
                } catch (NumberFormatException ignored) {
                    // leave defaults
                }
            }
        }
        return m;
    }

    private void runTests(State.Op op, String projectId) {
        Config.Project p = Main.currentConfig.project(projectId);
        if (p.testSuites.isEmpty()) {
            Config.CommandBlock block = p.op("test");
            if (block != null && block.supported && !block.cmd.isEmpty()) {
                int exit = exec(op, p, block, "test", "all", "Tests");
                if (exit == Integer.MIN_VALUE) {
                    return;
                }
                if (exit == 0) {
                    state.finishOp(op, "SUCCEEDED", "Tests completed successfully.", null);
                } else {
                    state.finishOp(op, "FAILED", "Tests failed with exit code " + exit + ".",
                            lastErrorLine(op));
                }
                return;
            }
            state.finishOp(op, "FAILED", null, "No test configuration for this project.");
            return;
        }
        int suitesOk = 0;
        int suitesFailed = 0;
        int totalRun = 0;
        int totalFail = 0;
        int totalErr = 0;
        int totalSkip = 0;
        List<String> suiteResults = new ArrayList<>();
        for (Config.TestSuite s : p.testSuites) {
            if (op.cancelRequested) {
                state.finishOp(op, "CANCELLED", "Tests cancelled.",
                        "Ran " + suitesOk + " suites before cancellation.");
                return;
            }
            op.phase = "Tests";
            op.suite = s.id;
            op.task = s.label;
            op.testsRun = 0;
            op.testsFailed = 0;
            op.testsErrors = 0;
            op.testsSkipped = 0;
            op.finalTestsRun = -1;
            append(op, "\n=== Test suite: " + s.label + " (" + s.id + ") ===\n");
            Config.CommandBlock block = new Config.CommandBlock(s.cmd, s.env, "repo",
                    s.timeoutMs, 0, true);
            int exit = exec(op, p, block, "test", s.id, s.label);
            if (exit == Integer.MIN_VALUE) {
                return;
            }
            if (op.finalTestsRun >= 0) {
                op.testsRun = op.finalTestsRun;
                op.testsFailed = op.finalTestsFailed;
                op.testsErrors = op.finalTestsErrors;
                op.testsSkipped = op.finalTestsSkipped;
            }
            boolean ok = exit == 0;
            if (ok) {
                suitesOk++;
            } else {
                suitesFailed++;
            }
            if (op.testsRun >= 0) {
                totalRun += op.testsRun;
                totalFail += Math.max(op.testsFailed, 0);
                totalErr += Math.max(op.testsErrors, 0);
                totalSkip += Math.max(op.testsSkipped, 0);
            }
            suiteResults.add((ok ? "PASS" : "FAIL") + "  " + s.label + " (exit " + exit + ", "
                    + (op.testsRun >= 0 ? op.testsRun + " tests, " + op.testsFailed + " failures, "
                    + op.testsErrors + " errors, " + op.testsSkipped + " skipped" : "no surefire summary")
                    + ")");
            if (!ok && !p.testContinueOnFailure) {
                break;
            }
        }
        op.suite = "";
        String summary = "Test results: " + suitesOk + "/" + (suitesOk + suitesFailed)
                + " suites passed";
        if (totalRun > 0) {
            summary += ", " + totalRun + " tests, " + totalFail + " failures, " + totalErr
                    + " errors, " + totalSkip + " skipped";
        }
        if (suitesFailed > 0) {
            state.finishOp(op, "FAILED", summary, String.join("\n", suiteResults));
            state.notify("error", projectId, "Tests failed", summary,
                    "Open the operation log for the failing suite.");
        } else {
            state.finishOp(op, "SUCCEEDED", summary, null);
            state.notify("success", projectId, "Tests completed", summary, "");
        }
    }

    /**
     * Instance-level functional test (TEST action). Executes a real,
     * product-specific functional scenario for a single (project, launch
     * configuration, execution mode) triple and persists the structured result.
     */
    private void runFuncTest(State.Op op, String projectId, String lcId,
            Map<String, Object> extra) {
        Config cfg = Main.currentConfig;
        Config.Project p = cfg == null ? null : cfg.project(projectId);
        if (p == null) {
            state.finishOp(op, "FAILED", null, "Unknown project: " + projectId);
            return;
        }
        Config.LaunchConfig lc = p.launch(lcId);
        if (lc == null) {
            state.finishOp(op, "FAILED", null, "Unsupported launch configuration '" + lcId
                    + "' for " + projectId + " (available: "
                    + String.join(", ", p.launchConfigs.stream().map(l -> l.id).toList()) + ")");
            return;
        }
        String modeStr = extra == null ? null : Json.str(extra, "mode", "web");
        FuncTest.Mode mode = FuncTest.Mode.from(modeStr);
        if (!FuncTest.deriveModes(lc).contains(mode)) {
            state.finishOp(op, "FAILED", null, "Execution mode " + mode.label
                    + " is not applicable to launch configuration '" + lcId + "' ("
                    + (lc.requiresDisplay ? "RCP" : "headless" + (lc.readiness != null
                            && "http".equals(lc.readiness.type) ? ", web" : "")) + ").");
            return;
        }
        FuncTest.Run run = FuncTest.run(app, state, proc, op, p, lc, mode);
        FuncTest.save(app, run);
        FuncTest.writeLatest(app, run);
        switch (run.status) {
            case PASS -> state.finishOp(op, "SUCCEEDED", run.summary, null);
            case FAIL -> state.finishOp(op, "FAILED", run.summary,
                    FuncTest.Status.FAIL.name() + ": " + run.summary);
            case PARTIAL -> state.finishOp(op, "FAILED", run.summary,
                    FuncTest.Status.PARTIAL.name() + ": " + run.summary);
            case BLOCKED -> state.finishOp(op, "FAILED", run.summary,
                    FuncTest.Status.BLOCKED.name() + ": " + run.summary);
            default -> state.finishOp(op, "FAILED", run.summary,
                    FuncTest.Status.NOT_IMPLEMENTED.name() + ": " + run.summary);
        }
        if (run.status == FuncTest.Status.PASS) {
            state.notify("success", projectId, "Functional test passed",
                    run.launchConfigId + " (" + run.mode.label + "): " + run.summary, "");
        } else {
            state.notify(run.status == FuncTest.Status.FAIL ? "error" : "warning", projectId,
                    "Functional test " + run.status.name().toLowerCase(),
                    run.launchConfigId + " (" + run.mode.label + "): " + run.summary,
                    "Open the test run details for step-level evidence.");
        }
    }

    // ---------------------------------------------------------------- run op

    private void runRuntime(State.Op op, String projectId, String lcId, Integer port) {
        Config.Project p = Main.currentConfig.project(projectId);
        Config.LaunchConfig lc = p.launch(lcId);
        op.phase = "Process creation";
        op.task = "Starting " + (lc != null ? lc.name : lcId);
        String err;
        try {
            err = proc.start(p, lc, port, op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err = "Interrupted";
        }
        if (err == null) {
            state.finishOp(op, "SUCCEEDED", "Runtime started and reached READY ("
                    + (lc != null ? lc.name : lcId) + ").", null);
        } else if (op.cancelRequested) {
            state.finishOp(op, "CANCELLED", "Startup cancelled.", err);
        } else {
            state.finishOp(op, "FAILED", "Runtime start failed.", err);
        }
    }

    private void runStop(State.Op op, String projectId) {
        op.phase = "Shutdown";
        op.task = "Stopping " + projectId;
        State.ProjectState ps = state.stateFor(projectId);
        String err = ps.runtimeState.equals("EXTERNAL")
                ? proc.stopExternal(projectId)
                : proc.stop(projectId, null);
        if (err == null) {
            state.finishOp(op, "SUCCEEDED", "Stopped.", null);
        } else {
            state.finishOp(op, "FAILED", "Stop failed.", err);
        }
    }

    private void runRestart(State.Op op, String projectId, String lcId) {
        Config.Project p = Main.currentConfig.project(projectId);
        State.ProjectState ps = state.stateFor(projectId);
        Integer lastPort = ps.boundPort > 0 ? ps.boundPort : null;
        String lastLc = ps.launchConfig;
        op.phase = "Shutdown";
        op.task = "Stopping " + projectId + " (restart)";
        String err = proc.stop(projectId, null);
        if (err != null) {
            state.finishOp(op, "FAILED", "Restart failed at stop step.", err);
            return;
        }
        String lc = lcId != null && !lcId.isBlank() ? lcId
                : !lastLc.isBlank() ? lastLc
                : p.defaultLaunch() != null ? p.defaultLaunch().id : null;
        if (lc == null) {
            state.finishOp(op, "FAILED", "Restart failed: no launch configuration.", null);
            return;
        }
        Config.LaunchConfig cfg = p.launch(lc);
        op.phase = "Process creation";
        op.task = "Starting " + lc;
        if (lastPort != null && cfg != null && cfg.readiness != null
                && !"range".equals(cfg.readiness.resolve)) {
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline
                    && Probe.tcpOpen(cfg.readiness.host, lastPort, 600)) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        try {
            err = proc.start(p, cfg, lastPort, op);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err = "Interrupted";
        }
        if (err == null) {
            state.finishOp(op, "SUCCEEDED", "Restarted (" + lc + ", READY).", null);
        } else {
            state.finishOp(op, "FAILED", "Restart failed at start step.", err);
        }
    }

    // ------------------------------------------------------------ git ops

    private void runGit(State.Op op, String projectId, String action, String message) {
        Config.Project p = Main.currentConfig.project(projectId);
        op.phase = "Git";
        op.task = "git " + action + " — " + p.repoDir;
        if (!GitOps.installed()) {
            state.finishOp(op, "FAILED", null, "Git CLI not found. Install Git first.");
            return;
        }
        GitOps.Result r = switch (action) {
            case "fetch" -> GitOps.fetch(Path.of(p.repoDir));
            case "pull" -> GitOps.pull(Path.of(p.repoDir));
            case "push" -> GitOps.push(Path.of(p.repoDir));
            case "commit" -> GitOps.commit(Path.of(p.repoDir), message == null ? "Commit" : message);
            default -> new GitOps.Result(-1, "unknown", "");
        };
        op.phase = "Completion";
        if (r.ok()) {
            state.finishOp(op, "SUCCEEDED", "git " + action + " completed.", null);
            state.notify("success", projectId, "git " + action, "completed", "");
        } else {
            state.finishOp(op, "FAILED", "git " + action + " failed (rc " + r.rc() + ").",
                    r.error() + "\n" + r.output());
        }
    }

    // --------------------------------------------------------------- profiles

    /** Dependency-aware startup order for a profile's projects. */
    private List<String> orderedStartup(Config cfg, Config.Profile profile) {
        List<String> order = new ArrayList<>(profile.startupOrder);
        // Fill in selected projects missing from the explicit order, honoring
        // startupAfter dependencies via a simple topological pass.
        List<String> remaining = new ArrayList<>();
        for (String pid : profile.projects) {
            if (!order.contains(pid)) {
                remaining.add(pid);
            }
        }
        int guard = 0;
        while (!remaining.isEmpty() && guard++ < 100) {
            String placed = null;
            for (String pid : new ArrayList<>(remaining)) {
                Config.Project p = cfg.project(pid);
                boolean depsReady = true;
                if (p != null) {
                    for (String dep : p.startupAfter) {
                        if (remaining.contains(dep)) {
                            depsReady = false;
                            break;
                        }
                    }
                }
                if (depsReady) {
                    order.add(pid);
                    remaining.remove(pid);
                    placed = pid;
                    break;
                }
            }
            if (placed == null) {
                // cycle: append remaining as-is
                order.addAll(remaining);
                remaining.clear();
            }
        }
        return order;
    }

    private void profileLaunch(State.Op op, String profileId) {
        Config cfg = Main.currentConfig;
        Config.Profile profile = cfg.profile(profileId);
        if (profile == null) {
            state.finishOp(op, "FAILED", null, "Unknown profile: " + profileId);
            return;
        }
        List<String> order = orderedStartup(cfg, profile);
        int done = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        for (String id : order) {
            Config.Project p = cfg.project(id);
            State.ProjectState ps = state.stateFor(id);
            op.task = "Launching " + id + " (" + (order.indexOf(id) + 1) + "/" + order.size() + ")";
            if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
                results.add(id + ": already running");
                done++;
                continue;
            }
            Config.LaunchConfig lc = p.defaultLaunch();
            if (lc == null) {
                results.add(id + ": skipped (no launch configuration)");
                continue;
            }
            Integer port = ps.boundPort > 0 ? ps.boundPort : null;
            String err;
            try {
                err = proc.start(p, lc, port, op);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "Interrupted";
            }
            if (err == null) {
                done++;
                results.add(id + ": OK (" + lc.id + ")");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Profile " + profile.name + ": " + done + "/"
                    + order.size() + " ready.", null);
        } else {
            state.finishOp(op, "FAILED", "Profile " + profile.name + ": partial success — "
                    + done + " ready, " + failed + " failed.", String.join("\n", results));
        }
    }

    private void profileStop(State.Op op, String profileId) {
        Config cfg = Main.currentConfig;
        Config.Profile profile = cfg.profile(profileId);
        if (profile == null) {
            state.finishOp(op, "FAILED", null, "Unknown profile: " + profileId);
            return;
        }
        List<String> order = profile.stopOrder.isEmpty()
                ? new ArrayList<>(orderedStartup(cfg, profile))
                : new ArrayList<>(profile.stopOrder);
        if (profile.stopOrder.isEmpty()) {
            java.util.Collections.reverse(order);
        }
        int ok = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        for (String id : order) {
            State.ProjectState ps = state.stateFor(id);
            op.task = "Stopping " + id;
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                continue;
            }
            String err = ps.runtimeState.equals("EXTERNAL")
                    ? proc.stopExternal(id)
                    : proc.stop(id, null);
            if (err == null) {
                ok++;
                results.add(id + ": OK");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Profile " + profile.name + ": " + ok + " stopped.", null);
        } else {
            state.finishOp(op, "FAILED", "Profile " + profile.name + ": " + ok + " stopped, "
                    + failed + " failed.", String.join("\n", results));
        }
    }

    // ---------------------------------------------------------------- global

    private void launchAll(State.Op op) {
        Config cfg = Main.currentConfig;
        List<String> order = new ArrayList<>();
        for (Config.Project p : cfg.projects) {
            order.add(p.id);
        }
        // topological pass over startupAfter
        order.sort((a, b) -> Integer.compare(depRank(cfg, a, new LinkedHashMap<>()),
                depRank(cfg, b, new LinkedHashMap<>())));
        int done = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        for (String id : order) {
            Config.Project p = cfg.project(id);
            State.ProjectState ps = state.stateFor(id);
            op.task = "Launching " + id + " (" + (order.indexOf(id) + 1) + "/" + order.size() + ")";
            if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
                results.add(id + ": already running");
                done++;
                continue;
            }
            Config.LaunchConfig lc = p.defaultLaunch();
            if (lc == null) {
                results.add(id + ": skipped (no launch configuration)");
                continue;
            }
            Integer port = ps.boundPort > 0 ? ps.boundPort : null;
            String err;
            try {
                err = proc.start(p, lc, port, op);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "Interrupted";
            }
            if (err == null) {
                done++;
                results.add(id + ": OK (" + lc.id + ")");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Launch All: " + done + "/" + order.size() + " ready.", null);
        } else {
            state.finishOp(op, "FAILED", "Launch All: partial success — " + done + " ready, "
                    + failed + " failed.", String.join("\n", results));
        }
    }

    private int depRank(Config cfg, String id, Map<String, Integer> memo) {
        if (memo.containsKey(id)) {
            return memo.get(id);
        }
        memo.put(id, 0);
        Config.Project p = cfg.project(id);
        int rank = 0;
        if (p != null) {
            for (String dep : p.startupAfter) {
                rank = Math.max(rank, 1 + depRank(cfg, dep, memo));
            }
        }
        memo.put(id, rank);
        return rank;
    }

    private void stopAll(State.Op op) {
        List<String> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        Config cfg = Main.currentConfig;
        for (Config.Project p : cfg.projects) {
            State.ProjectState ps = state.stateFor(p.id);
            op.task = "Stopping " + p.id;
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                continue;
            }
            String err = ps.runtimeState.equals("EXTERNAL")
                    ? proc.stopExternal(p.id)
                    : proc.stop(p.id, null);
            if (err == null) {
                ok++;
                results.add(p.id + ": OK");
            } else {
                failed++;
                results.add(p.id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            state.finishOp(op, "SUCCEEDED", "Stop All: " + ok + " stopped.", null);
        } else {
            state.finishOp(op, "FAILED", "Stop All: " + ok + " stopped, " + failed + " failed.",
                    String.join("\n", results));
        }
    }

    private void restartAll(State.Op op) {
        stopAllPhase(op);
        if (!op.status.equals("RUNNING")) {
            state.finishOp(op, "FAILED", "Restart All aborted: some projects could not be stopped.",
                    op.error);
            return;
        }
        launchAllPhase(op);
        if (op.status.equals("RUNNING")) {
            state.finishOp(op, "SUCCEEDED", "Restart All: all projects stopped and relaunched.", null);
        }
    }

    private void stopAllPhase(State.Op op) {
        List<String> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;
        for (Config.Project p : Main.currentConfig.projects) {
            State.ProjectState ps = state.stateFor(p.id);
            op.task = "Stopping " + p.id;
            if (ps.runtimeState.equals("STOPPED") || ps.runtimeState.equals("FAILED")) {
                continue;
            }
            String err = ps.runtimeState.equals("EXTERNAL")
                    ? proc.stopExternal(p.id)
                    : proc.stop(p.id, null);
            if (err == null) {
                ok++;
                results.add(p.id + ": OK");
            } else {
                failed++;
                results.add(p.id + ": FAILED — " + firstLine(err));
            }
        }
        if (failed == 0) {
            op.task = "All stopped (" + ok + ")";
        } else {
            op.error = String.join("\n", results);
            op.status = "FAILED";
        }
    }

    private void launchAllPhase(State.Op op) {
        int done = 0;
        int failed = 0;
        List<String> results = new ArrayList<>();
        List<String> order = new ArrayList<>();
        for (Config.Project p : Main.currentConfig.projects) {
            order.add(p.id);
        }
        for (String id : order) {
            Config.Project p = Main.currentConfig.project(id);
            State.ProjectState ps = state.stateFor(id);
            op.task = "Launching " + id + " (" + (order.indexOf(id) + 1) + "/" + order.size() + ")";
            if (ps.runtimeState.equals("READY") || ps.runtimeState.equals("RUNNING")) {
                results.add(id + ": already running");
                done++;
                continue;
            }
            Config.LaunchConfig lc = p.defaultLaunch();
            if (lc == null) {
                results.add(id + ": skipped (no launch configuration)");
                continue;
            }
            Integer port = ps.boundPort > 0 ? ps.boundPort : null;
            String err;
            try {
                err = proc.start(p, lc, port, op);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                err = "Interrupted";
            }
            if (err == null) {
                done++;
                results.add(id + ": OK (" + lc.id + ")");
            } else {
                failed++;
                results.add(id + ": FAILED — " + firstLine(err));
            }
        }
        op.phase = "Completion";
        if (failed == 0) {
            op.task = "All launched: " + done + "/" + order.size() + " ready";
        } else {
            op.status = "FAILED";
            op.error = String.join("\n", results);
        }
    }

    private String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        return i >= 0 ? s.substring(0, i) : s;
    }

    // ------------------------------------------------------------- exec core

    private int exec(State.Op op, Config.Project p, Config.CommandBlock block,
            String kind, String suiteId, String suiteLabel) {
        List<String> cmd = block.cmd;
        ProcessBuilder pb = new ProcessBuilder(cmd);
        Path wd = Path.of(p.repoDir);
        if (block.workDir != null && !block.workDir.equals("repo")) {
            Path w = Path.of(App.expandHome(block.workDir));
            if (!w.isAbsolute()) {
                w = Path.of(p.repoDir, block.workDir);
            }
            wd = w;
        }
        pb.directory(wd.toFile());
        pb.redirectErrorStream(true);
        Map<String, String> envExpanded = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : block.env.entrySet()) {
            String v = e.getValue();
            v = v.replace("{java21}", String.valueOf(Adapters.detectTools().getOrDefault("java21", "")));
            v = v.replace("{java25}", String.valueOf(Adapters.detectTools().getOrDefault("java25", "")));
            v = v.replace("{repo}", p.repoDir).replace("{app}", app.appDir.toString());
            envExpanded.put(e.getKey(), v);
        }
        String javaBin = System.getProperty("java.home") + "/bin";
        if (envExpanded.containsKey("JAVA_HOME") && !envExpanded.get("JAVA_HOME").isBlank()) {
            javaBin = Path.of(envExpanded.get("JAVA_HOME"), "bin").toString();
        }
        String path = System.getenv("PATH");
        pb.environment().put("PATH", javaBin + (path == null ? "" : ":" + path));
        pb.environment().putAll(envExpanded);
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            state.finishOp(op, "FAILED", null, "Failed to start command: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
        Path opLog = app.opsDir.resolve(op.id + ".log");
        op.phase = "Initialization";
        op.task = String.join(" ", cmd);
        try {
            Files.createDirectories(opLog.getParent());
        } catch (IOException ignored) {
            // best effort
        }
        long deadline = System.currentTimeMillis() + block.timeoutMs;
        List<String> modules = new ArrayList<>();
        boolean sawReactor = false;
        java.io.BufferedWriter logWriter = null;
        try {
            logWriter = Files.newBufferedWriter(opLog, StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE);
        } catch (IOException ignored) {
            // best effort
        }
        final java.io.BufferedWriter writer = logWriter;
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                append(op, line + "\n");
                if (writer != null) {
                    try {
                        writer.write(line);
                        writer.write('\n');
                    } catch (IOException ignored) {
                        // best effort
                    }
                }
                parseLine(op, line, kind, block.expectedModules);
                if (line.contains("Reactor Build Order:")) {
                    sawReactor = true;
                }
                if (sawReactor && line.trim().startsWith("[INFO] ")) {
                    String t = line.trim().substring(7);
                    if (t.matches("\\S+\\s+.*")) {
                        String mod = t.split("\\s+")[0];
                        if (!modules.contains(mod) && !mod.contains("---")) {
                            modules.add(mod);
                        }
                    }
                }
                if (System.currentTimeMillis() > deadline) {
                    destroyTree(process);
                    closeWriter(writer);
                    String msg = "Operation timed out after " + (block.timeoutMs / 60000) + " min";
                    state.finishOp(op, "FAILED", null, msg);
                    state.notify("error", op.projectId, kind + " timed out", msg,
                            "Check " + opLog + " for the point where it stalled.");
                    return Integer.MIN_VALUE;
                }
                if (op.cancelRequested) {
                    destroyTree(process);
                    closeWriter(writer);
                    state.finishOp(op, "CANCELLED", kind + " cancelled by user.", null);
                    return Integer.MIN_VALUE;
                }
            }
        } catch (IOException e) {
            closeWriter(writer);
            state.finishOp(op, "FAILED", null, "Reading process output failed: " + e.getMessage());
            return Integer.MIN_VALUE;
        }
        int exit;
        try {
            exit = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            destroyTree(process);
            closeWriter(writer);
            state.finishOp(op, "CANCELLED", "Interrupted.", null);
            return Integer.MIN_VALUE;
        }
        closeWriter(writer);
        op.totalModules = op.totalModules > 0 ? op.totalModules
                : (modules.size() > 0 ? modules.size() : (block.expectedModules > 0 ? block.expectedModules : 0));
        if (op.totalModules > 0) {
            op.completedModules = exit == 0 ? op.totalModules : Math.max(op.completedModules, 0);
            op.progressKnown = true;
        }
        op.phase = "Completion";
        return exit;
    }

    private static void closeWriter(java.io.BufferedWriter w) {
        if (w == null) {
            return;
        }
        try {
            w.flush();
            w.close();
        } catch (IOException ignored) {
            // best effort
        }
    }

    private void parseLine(State.Op op, String line, String kind, int expectedModules) {
        if (line.contains("Scanning for projects") || line.contains("Scanning for modules")) {
            op.phase = "Initialization";
            op.task = "Scanning modules";
        } else if (line.contains("maven-clean-plugin") || line.contains("clean:clean")) {
            op.phase = "Initialization";
            op.task = "Clean";
        } else if (line.contains("Downloading") || line.contains("Downloaded")
                || line.contains("p2.index")) {
            if (!op.phase.equals("Tests")) {
                op.phase = "Dependency resolution";
                op.task = "Resolving dependencies";
            }
        } else if (line.contains("maven-compiler-plugin") || line.contains("tycho-compiler")
                || line.contains("Compiling")) {
            op.phase = "Compilation";
            op.task = "Compiling";
        } else if (line.contains("testCompile")) {
            op.phase = "Test compilation";
            op.task = "Compiling tests";
        } else if (line.contains("surefire") || line.contains("tycho-surefire")) {
            op.phase = "Tests";
            op.task = "Running tests";
        } else if (line.contains("tycho-p2-publisher") || line.contains("maven-package-plugin")) {
            op.phase = "Packaging";
            op.task = "Packaging";
        } else if (line.contains("tycho-p2-director") || line.contains("materialize-products")) {
            op.phase = "Product assembly";
            op.task = "Assembling product";
        } else if (line.contains("Reactor Summary") || line.contains("BUILD SUCCESS")
                || line.contains("BUILD FAILURE")) {
            op.phase = "Completion";
            op.task = "Finishing";
        }
        Matcher m = BUILDING.matcher(line);
        if (m.find()) {
            String mod = m.group(1);
            if (mod.contains(":")) {
                return;
            }
            op.currentModule = mod;
            op.task = "Building " + mod;
            if (m.group(2) != null && m.group(3) != null) {
                op.completedModules = Integer.parseInt(m.group(2)) - 1;
                op.totalModules = Integer.parseInt(m.group(3));
                op.progressKnown = true;
            } else {
                op.completedModules = Math.max(op.completedModules, 0);
                if (op.totalModules == 0) {
                    op.totalModules = expectedModules;
                }
                if (op.totalModules > 0) {
                    op.progressKnown = true;
                }
            }
        }
        Matcher t = TESTS_RUN.matcher(line);
        if (t.find()) {
            if (line.contains("-- in ")) {
                op.testsRun += Integer.parseInt(t.group(1));
                op.testsFailed += Integer.parseInt(t.group(2));
                op.testsErrors += Integer.parseInt(t.group(3));
                op.testsSkipped += Integer.parseInt(t.group(4));
            } else {
                op.finalTestsRun = Integer.parseInt(t.group(1));
                op.finalTestsFailed = Integer.parseInt(t.group(2));
                op.finalTestsErrors = Integer.parseInt(t.group(3));
                op.finalTestsSkipped = Integer.parseInt(t.group(4));
            }
            op.phase = "Tests";
            op.task = op.suite.isEmpty() ? "Tests" : op.suite + ": tests";
        }
    }

    private void destroyTree(Process p) {
        try {
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
            Thread.sleep(1500);
            if (p.isAlive()) {
                p.descendants().forEach(ProcessHandle::destroyForcibly);
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void append(State.Op op, String text) {
        synchronized (op.live) {
            op.live.append(text);
            if (op.live.length() > MAX_LIVE_BUFFER) {
                op.live.delete(0, op.live.length() - MAX_LIVE_BUFFER);
            }
        }
    }

    private String lastErrorLine(State.Op op) {
        String log = op.logTail(4000);
        String[] lines = log.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String l = lines[i];
            if (l.contains("[ERROR]") || l.contains("BUILD FAILURE") || l.contains("FAILURE:")) {
                return l.trim() + "\n" + (i + 1 < lines.length ? lines[i + 1].trim() : "");
            }
        }
        return lines.length > 0 ? lines[lines.length - 1].trim() : "Unknown error.";
    }

    public boolean cancel(long opId) {
        State.Op op;
        synchronized (state.ops) {
            op = state.ops.get(opId);
        }
        if (op == null || !op.status.equals("RUNNING")) {
            return false;
        }
        if (op.type.equals("run")) {
            op.cancelRequested = true;
            State.ProjectState ps = state.projects.get(op.projectId);
            if (ps != null && ps.runtimeState.equals("STARTING")) {
                proc.stop(op.projectId, null);
            }
            return true;
        }
        op.cancelRequested = true;
        return true;
    }
}
