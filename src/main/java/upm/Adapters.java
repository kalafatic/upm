package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.zip.ZipFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Project adapter architecture. Adapters are built-in, configuration-driven
 * execution strategies; no plugin loading, no arbitrary downloaded code.
 * The UI never contains project-specific logic — it asks the adapter.
 */
public final class Adapters {

    /** Detection result with confidence levels required by the product spec. */
    public static final class DetectionResult {
        public final String typeId;
        public final String confidence; // DETECTED | PROPOSED | USER_CONFIRMED | UNKNOWN | UNSUPPORTED
        public final List<String> evidence;
        public final List<String> alternativeTypes;
        public final List<Config.LaunchConfig> proposedLaunchConfigs;
        public final Map<String, String> notes;

        DetectionResult(String typeId, String confidence, List<String> evidence,
                List<String> alternativeTypes, List<Config.LaunchConfig> proposedLaunchConfigs,
                Map<String, String> notes) {
            this.typeId = typeId;
            this.confidence = confidence;
            this.evidence = evidence;
            this.alternativeTypes = alternativeTypes;
            this.proposedLaunchConfigs = proposedLaunchConfigs;
            this.notes = notes;
        }

        public Map<String, Object> toJson() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("typeId", typeId);
            m.put("confidence", confidence);
            m.put("evidence", evidence);
            m.put("alternativeTypes", alternativeTypes);
            List<Map<String, Object>> lcs = new ArrayList<>();
            for (Config.LaunchConfig lc : proposedLaunchConfigs) {
                lcs.add(lc.toJson());
            }
            m.put("proposedLaunchConfigs", lcs);
            m.put("notes", notes);
            return m;
        }
    }

    /** Adapter contract. */
    public interface ProjectAdapter {
        String typeId();

        String displayName();

        /** Read-only inspection of the repository; never executes repo scripts. */
        DetectionResult detect(Path repoDir);

        /** Operation keys this adapter supports (build, test, install, ...). */
        Set<String> supportedOperations();

        /** Default operation commands for a freshly registered project (Maven etc.). */
        Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env);

        /** Describes a launch kind produced by detection/proposal. */
        boolean supportsLaunchKind(String kind);
    }

    // ------------------------------------------------------------------ registry

    private static final Map<String, ProjectAdapter> REGISTRY = new LinkedHashMap<>();

    static {
        register(new MavenAdapter());
        register(new GradleAdapter());
        register(new TychoAdapter());
        register(new ExecutableJarAdapter());
        register(new NativeCmakeAdapter());
        register(new MakeAdapter());
        register(new CargoAdapter());
        register(new GoAdapter());
        register(new PythonAdapter());
        register(new NodeAdapter());
        register(new DockerAdapter());
        register(new GenericAdapter());
        register(new CustomAdapter());
    }

    private static void register(ProjectAdapter a) {
        REGISTRY.put(a.typeId(), a);
    }

    public static boolean exists(String typeId) {
        return REGISTRY.containsKey(typeId);
    }

    public static ProjectAdapter get(String typeId) {
        return REGISTRY.getOrDefault(typeId, REGISTRY.get("custom"));
    }

    public static Set<String> allTypeIds() {
        return new LinkedHashSet<>(REGISTRY.keySet());
    }

    public static Map<String, Object> registryJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (ProjectAdapter a : REGISTRY.values()) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("id", a.typeId());
            e.put("name", a.displayName());
            e.put("operations", new ArrayList<>(a.supportedOperations()));
            m.put(a.typeId(), e);
        }
        return m;
    }

    // ------------------------------------------------------------------ helpers

    static boolean hasFile(Path dir, String name) {
        return Files.exists(dir.resolve(name));
    }

    static boolean hasAnyFile(Path dir, String... names) {
        for (String n : names) {
            if (Files.exists(dir.resolve(n))) {
                return true;
            }
        }
        return false;
    }

    static String readFirstLines(Path f, int max) {
        try {
            List<String> lines = Files.readAllLines(f);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < Math.min(lines.size(), max); i++) {
                sb.append(lines.get(i)).append('\n');
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    static Config.LaunchConfig launch(String id, String name, String kind, String launcher,
            List<String> args, int port, boolean portFixed, Config.Readiness readiness,
            List<String> markers, boolean isDefault) {
        return new Config.LaunchConfig(id, name, kind, launcher, args, Map.of(), "repo",
                port, portFixed, false, readiness, markers, 20, 180_000L, isDefault);
    }

    /** Copies a launch configuration with a different environment map. */
    static Config.LaunchConfig withEnv(Config.LaunchConfig lc, Map<String, String> env) {
        return new Config.LaunchConfig(lc.id, lc.name, lc.kind, lc.launcher, lc.args, env,
                lc.workDir, lc.port, lc.portFixed, lc.requiresDisplay, lc.readiness,
                lc.readyMarkers, lc.stopGraceSeconds, lc.startupTimeoutMs, lc.isDefault);
    }

    // ------------------------------------------------------------------- Maven

    public static final class MavenAdapter implements ProjectAdapter {
        public String typeId() { return "maven"; }
        public String displayName() { return "Java Maven"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "update", "test",
                    "package", "verify", "install", "validate"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("jar") || kind.equals("http-server") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "pom.xml")) {
                return unsupported("No pom.xml found");
            }
            String pom = readFirstLines(dir.resolve("pom.xml"), 200);
            List<String> evidence = new ArrayList<>();
            evidence.add("pom.xml present");
            if (pom.contains("tycho")) {
                evidence.add("Tycho referenced in pom.xml — see the Eclipse Tycho adapter");
                // Delegate to the Tycho adapter so product/launch proposals are
                // produced by the same code path as direct Tycho detection.
                DetectionResult t = new TychoAdapter().detect(dir);
                List<String> merged = new ArrayList<>(evidence);
                for (String e : t.evidence) {
                    if (!merged.contains(e)) {
                        merged.add(e);
                    }
                }
                return new DetectionResult("eclipse-tycho", t.confidence, merged,
                        t.alternativeTypes, t.proposedLaunchConfigs, t.notes);
            }
            if (pom.contains("<packaging>pom</packaging>") && pom.contains("<module>")) {
                evidence.add("Multi-module reactor POM");
            }
            if (pom.contains("spring-boot") || pom.contains("exec-maven-plugin")) {
                evidence.add("Runnable/executable goals detected");
            }
            String confidence = "DETECTED";
            List<Config.LaunchConfig> launches = new ArrayList<>();
            // propose an executable jar launch if a target jar with Main-Class exists
            Path target = dir.resolve("target");
            if (Files.isDirectory(target)) {
                try (var stream = Files.list(target)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        String n = f.getFileName().toString();
                        if (isRunnableJarName(n)) {
                            String main = jarMainClass(f);
                            if (main != null && !main.isBlank()) {
                                launches.add(launch("run-jar", "Executable JAR (" + n + ")",
                                        "jar", f.getFileName().toString(),
                                        List.of(), 0, false, null, List.of(), launches.isEmpty()));
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
            // multi-module reactors: scan module target directories as well
            // (standalone runtimes such as CLI/server modules live below the root)
            for (String mod : detectModules(dir, "maven")) {
                Path modDir = dir.resolve(mod);
                if (!Files.isDirectory(modDir)) {
                    continue;
                }
                Path modTarget = modDir.resolve("target");
                if (!Files.isDirectory(modTarget)) {
                    continue;
                }
                try (var stream = Files.list(modTarget)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        String n = f.getFileName().toString();
                        if (!isRunnableJarName(n)) {
                            continue;
                        }
                        String main = jarMainClass(f);
                        if (main != null && !main.isBlank()) {
                            String rel = dir.relativize(f).toString();
                            launches.add(launch("run-" + mod + "-" + n.replace('.', '-'),
                                    "Module JAR: " + mod + "/" + n, "jar", rel,
                                    List.of(), 0, false, null, List.of(), launches.isEmpty()));
                        }
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
            Map<String, String> notes = new LinkedHashMap<>();
            notes.put("buildTool", "Maven (./mvnw when present)");
            String requiredJava = requiredJavaFromPom(dir);
            if (!requiredJava.isEmpty()) {
                notes.put("requiresJava", requiredJava);
                // JAR launchers built for a newer release need that JDK at runtime.
                if (!launches.isEmpty()) {
                    Map<String, String> jen = Map.of("JAVA_HOME", "{java" + requiredJava + "}");
                    for (int i = 0; i < launches.size(); i++) {
                        if (launches.get(i).kind.equals("jar")) {
                            launches.set(i, withEnv(launches.get(i), jen));
                        }
                    }
                }
            }
            return new DetectionResult("maven", confidence, evidence, List.of(),
                    launches, notes);
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            String mvn = hasFile(repoDir, "mvnw") ? "./mvnw" : "mvn";
            Map<String, String> e = javaEnv(env, repoDir);
            ops.put("build", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "clean", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "clean", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("update", new Config.CommandBlock(List.of(mvn, "-B", "-U", "-DskipTests", "clean", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("verify", new Config.CommandBlock(List.of(mvn, "-B", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of(mvn, "-B", "test"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "package"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "install"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("validate", new Config.CommandBlock(List.of(mvn, "-B", "validate"),
                    e, "repo", 30 * 60000L, 0, true));
            return ops;
        }
    }

    // ------------------------------------------------------------------ Gradle

    public static final class GradleAdapter implements ProjectAdapter {
        public String typeId() { return "gradle"; }
        public String displayName() { return "Java Gradle"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "package", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("jar") || kind.equals("http-server") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasAnyFile(dir, "build.gradle", "build.gradle.kts")) {
                return unsupported("No build.gradle found");
            }
            List<String> evidence = new ArrayList<>();
            if (hasFile(dir, "build.gradle.kts")) {
                evidence.add("build.gradle.kts present (Kotlin DSL)");
            } else {
                evidence.add("build.gradle present (Groovy DSL)");
            }
            if (hasFile(dir, "settings.gradle") || hasFile(dir, "settings.gradle.kts")) {
                evidence.add("settings.gradle present");
            }
            if (hasFile(dir, "gradlew")) {
                evidence.add("Gradle wrapper present");
            }
            return new DetectionResult("gradle", "DETECTED", evidence, List.of(),
                    List.of(), Map.of("buildTool", "Gradle (./gradlew when present)"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            String gw = hasFile(repoDir, "gradlew") ? "./gradlew" : "gradle";
            ops.put("build", new Config.CommandBlock(List.of(gw, "build", "-x", "test"),
                    env, "repo", 240 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of(gw, "clean", "build", "-x", "test"),
                    env, "repo", 240 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of(gw, "test"),
                    env, "repo", 240 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of(gw, "assemble"),
                    env, "repo", 240 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of(gw, "publishToMavenLocal"),
                    env, "repo", 240 * 60000L, 0, true));
            return ops;
        }
    }

    // ------------------------------------------------------------------- Tycho

    public static final class TychoAdapter implements ProjectAdapter {
        public String typeId() { return "eclipse-tycho"; }
        public String displayName() { return "Eclipse RCP / PDE / Tycho / OSGi"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "update", "test",
                    "package", "verify", "install", "validate",
                    "buildProduct", "validateProduct", "buildFeatures", "buildPlugins",
                    "exportProduct", "exportLinux", "exportWindows", "exportMacosx",
                    "exportAll", "buildP2Repository", "publishUpdateSite",
                    "validateUpdateRepository", "inspectVersions"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("eclipse-product") || kind.equals("eclipse-application")
                    || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            List<String> evidence = new ArrayList<>();
            boolean tycho = false;
            if (hasFile(dir, "pom.xml")) {
                String pom = readFirstLines(dir.resolve("pom.xml"), 400);
                evidence.add("pom.xml present");
                tycho = pom.contains("tycho");
            }
            boolean pluginXml = findAny(dir, 4, "plugin.xml") != null;
            boolean manifest = findAny(dir, 4, "MANIFEST.MF") != null;
            boolean product = findAny(dir, 5, "*.product") != null;
            boolean featureXml = findAny(dir, 5, "feature.xml") != null;
            if (pluginXml) {
                evidence.add("plugin.xml found");
            }
            if (manifest) {
                evidence.add("MANIFEST.MF found (OSGi bundle)");
            }
            if (product) {
                evidence.add("*.product found (Eclipse product)");
            }
            if (featureXml) {
                evidence.add("feature.xml found");
            }
            if (tycho) {
                evidence.add("Tycho build configuration detected");
            }
            if (!tycho && !pluginXml && !product && !featureXml) {
                return unsupported("No Eclipse/Tycho/OSGi markers found");
            }
            String confidence = tycho ? "DETECTED" : "PROPOSED";
            List<String> alternatives = new ArrayList<>();
            if (hasFile(dir, "pom.xml") && !tycho) {
                alternatives.add("maven");
            }
            List<Config.LaunchConfig> launches = new ArrayList<>();
            // Search for exported product launchers (already built products).
            // Any executable file directly inside a product directory is a
            // candidate; no application names are hardcoded.
            Path prodRoot = findProductRoot(dir);
            if (prodRoot != null) {
                try (var stream = Files.walk(prodRoot, 6)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        if (Files.isRegularFile(f) && Files.isExecutable(f)) {
                            String name = f.getFileName().toString();
                            if (name.endsWith(".sh") || name.endsWith(".ini")
                                    || name.contains(".app")) {
                                continue;
                            }
                            String rel = dir.relativize(f).toString();
                            launches.add(launch("product-" + name, "Product: " + name,
                                    "eclipse-product", rel, List.of("-nosplash", "-consoleLog"),
                                    0, false, null, List.of(), launches.isEmpty()));
                        }
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
            return new DetectionResult("eclipse-tycho", confidence, evidence, alternatives,
                    launches, Map.of("buildTool", "Tycho (./mvnw when present)"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            String mvn = mavenCommand(repoDir);
            Map<String, String> e = javaEnv(env, repoDir);
            ops.put("build", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "clean", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "clean", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("update", new Config.CommandBlock(List.of(mvn, "-B", "-U", "-DskipTests", "clean", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("verify", new Config.CommandBlock(List.of(mvn, "-B", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of(mvn, "-B", "verify"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "package"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "install"),
                    e, "repo", 240 * 60000L, 0, true));
            ops.put("validate", new Config.CommandBlock(List.of(mvn, "-B", "validate"),
                    e, "repo", 30 * 60000L, 0, true));
            // Product export and p2 repository operations run the full reactor
            // so the assembled update site always contains the fresh
            // feature/plugin artifacts (repository modules may lack explicit
            // Maven dependencies on the product feature).
            String repoModule = repositoryModule(repoDir);
            if (repoModule != null) {
                String m = repoModule.replace("\\", "/");
                long t = 240 * 60000L;
                ops.put("buildProduct", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "clean", "verify"), e, "repo", t, 0, true));
                ops.put("exportProduct", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "clean", "verify"), e, "repo", t, 0, true));
                ops.put("exportLinux", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "clean", "verify", "-Plinux"), e, "repo", t, 0, true));
                ops.put("exportWindows", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "clean", "verify", "-Pwindows"), e, "repo", t, 0, true));
                ops.put("exportMacosx", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "clean", "verify", "-Pmacosx"), e, "repo", t, 0, true));
                ops.put("exportAll", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "clean", "verify", "-Pall-platforms"), e, "repo", t, 0, true));
                ops.put("buildP2Repository", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests",
                        "package"), e, "repo", t, 0, true));
                // Repo-relative paths keep the stored operation valid even
                // when the project's repository directory moves.
                String updRel = "updates/" + m.replace("/", "-");
                String srcRel = m + "/target/repository";
                ops.put("publishUpdateSite", new Config.CommandBlock(List.of("sh", "-c",
                        "set -e; rm -rf '" + updRel + "'; mkdir -p '" + updRel + "'; cp -r '" + srcRel
                        + "/.' '" + updRel + "/'; ls -la '" + updRel + "'"),
                        e, "repo", 30 * 60000L, 0, true));
            }
            // Bundle/feature build operations across the reactor's plugins.
            List<String> bundles = new ArrayList<>();
            List<String> features = new ArrayList<>();
            for (String mod : detectModules(repoDir, "eclipse-tycho")) {
                String role = moduleRole(repoDir, mod);
                if (role.equals("bundle")) {
                    bundles.add(mod);
                } else if (role.equals("feature")) {
                    features.add(mod);
                }
            }
            if (!bundles.isEmpty()) {
                List<String> cmd = new ArrayList<>(List.of(mvn, "-B", "-DskipTests", "package", "-pl"));
                cmd.addAll(bundles);
                cmd.add("-am");
                ops.put("buildPlugins", new Config.CommandBlock(cmd, e, "repo", 240 * 60000L, 0, true));
            }
            if (!features.isEmpty()) {
                List<String> cmd = new ArrayList<>(List.of(mvn, "-B", "-DskipTests", "package", "-pl"));
                cmd.addAll(features);
                cmd.add("-am");
                ops.put("buildFeatures", new Config.CommandBlock(cmd, e, "repo", 240 * 60000L, 0, true));
            }
            return ops;
        }
    }

    // ----------------------------------------------------------- Executable jar

    public static final class ExecutableJarAdapter implements ProjectAdapter {
        public String typeId() { return "executable-jar"; }
        public String displayName() { return "Executable JAR / Standalone HTTP server"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "package"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("jar") || kind.equals("http-server") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            List<String> evidence = new ArrayList<>();
            List<Config.LaunchConfig> launches = new ArrayList<>();
            Path target = dir.resolve("target");
            boolean found = false;
            if (Files.isDirectory(target)) {
                try (var stream = Files.list(target)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        String n = f.getFileName().toString();
                        if (!n.endsWith(".jar") || n.endsWith("-sources.jar")
                                || n.endsWith("-javadoc.jar") || n.endsWith("-tests.jar")) {
                            continue;
                        }
                        String main = jarMainClass(f);
                        if (main != null && !main.isBlank()) {
                            found = true;
                            evidence.add("JAR with Main-Class: " + n);
                            launches.add(launch("run-" + n.replace('.', '-'),
                                    "JAR: " + n, "jar", "target/" + n,
                                    List.of(), 0, false, null, List.of(), launches.isEmpty()));
                        }
                    }
                } catch (IOException ignored) {
                    // skip
                }
            }
            if (!found && hasFile(dir, "pom.xml")) {
                return unsupported("POM present without runnable JAR — use the Maven adapter");
            }
            if (!found) {
                return unsupported("No executable JAR found");
            }
            return new DetectionResult("executable-jar", "PROPOSED", evidence, List.of("maven", "gradle"),
                    launches, Map.of());
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            String mvn = hasFile(repoDir, "mvnw") ? "./mvnw" : "mvn";
            if (hasFile(repoDir, "pom.xml")) {
                ops.put("build", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "package"),
                        env, "repo", 120 * 60000L, 0, true));
                ops.put("package", new Config.CommandBlock(List.of(mvn, "-B", "-DskipTests", "package"),
                        env, "repo", 120 * 60000L, 0, true));
            }
            return ops;
        }
    }

    // ------------------------------------------------------------------- CMake

    public static final class NativeCmakeAdapter implements ProjectAdapter {
        public String typeId() { return "native-cmake"; }
        public String displayName() { return "Native C/C++ (CMake)"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("native") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "CMakeLists.txt")) {
                return unsupported("No CMakeLists.txt found");
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("CMakeLists.txt present");
            if (hasFile(dir, "Makefile")) {
                evidence.add("Makefile present");
            }
            return new DetectionResult("native-cmake", "DETECTED", evidence, List.of("generic"),
                    List.of(), Map.of("buildTool", "CMake + system toolchain"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            ops.put("build", new Config.CommandBlock(List.of("cmake", "--build", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of("cmake", "--build", "build", "--clean-first"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of("ctest", "--test-dir", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of("cmake", "--install", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // ------------------------------------------------------------------- Make

    public static final class MakeAdapter implements ProjectAdapter {
        public String typeId() { return "make"; }
        public String displayName() { return "C/C++ (Make)"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("native") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "Makefile") && !hasFile(dir, "makefile") && !hasFile(dir, "GNUmakefile")) {
                return unsupported("No Makefile found");
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Makefile present");
            if (hasFile(dir, "CMakeLists.txt")) {
                evidence.add("CMakeLists.txt also present — see the CMake adapter");
            }
            return new DetectionResult("make", "DETECTED", evidence, List.of("native-cmake"),
                    List.of(), Map.of("buildTool", "Make + system toolchain"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            ops.put("build", new Config.CommandBlock(List.of("make", "-j" + availableCpus()),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of("make", "clean", "all"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of("make", "test"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of("make", "install"),
                    env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // ------------------------------------------------------------------ Cargo

    public static final class CargoAdapter implements ProjectAdapter {
        public String typeId() { return "cargo"; }
        public String displayName() { return "Rust (Cargo)"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "package", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("native") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "Cargo.toml")) {
                return unsupported("No Cargo.toml found");
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("Cargo.toml present");
            String toml = readFirstLines(dir.resolve("Cargo.toml"), 120);
            if (toml.contains("[workspace]")) {
                evidence.add("Cargo workspace (multiple members)");
            }
            if (toml.contains("[[bin]]")) {
                evidence.add("Binary target declared");
            }
            return new DetectionResult("cargo", "DETECTED", evidence, List.of("generic"),
                    List.of(), Map.of("buildTool", "Cargo"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            ops.put("build", new Config.CommandBlock(List.of("cargo", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of("cargo", "clean", "&&", "cargo", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of("cargo", "test"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of("cargo", "build", "--release"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of("cargo", "install", "--path", "."),
                    env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // --------------------------------------------------------------------- Go

    public static final class GoAdapter implements ProjectAdapter {
        public String typeId() { return "go"; }
        public String displayName() { return "Go"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "package", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("native") || kind.equals("http-server") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "go.mod")) {
                return unsupported("No go.mod found");
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("go.mod present");
            String mod = readFirstLines(dir.resolve("go.mod"), 60);
            Matcher m = java.util.regex.Pattern.compile("^module\\s+(\\S+)").matcher(mod);
            if (m.find()) {
                evidence.add("module: " + m.group(1));
            }
            return new DetectionResult("go", "DETECTED", evidence, List.of("generic"),
                    List.of(), Map.of("buildTool", "Go toolchain"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            ops.put("build", new Config.CommandBlock(List.of("go", "build", "./..."),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of("go", "clean", "-cache", "&&", "go", "build", "./..."),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of("go", "test", "./..."),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of("go", "build", "-o", "build/"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of("go", "install", "./..."),
                    env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // ----------------------------------------------------------------- Python

    public static final class PythonAdapter implements ProjectAdapter {
        public String typeId() { return "python"; }
        public String displayName() { return "Python"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "test", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("http-server") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            List<String> evidence = new ArrayList<>();
            if (hasFile(dir, "pyproject.toml")) {
                evidence.add("pyproject.toml present");
            }
            if (hasFile(dir, "setup.py")) {
                evidence.add("setup.py present");
            }
            if (hasFile(dir, "requirements.txt")) {
                evidence.add("requirements.txt present");
            }
            if (evidence.isEmpty()) {
                return unsupported("No Python build markers found");
            }
            return new DetectionResult("python", "DETECTED", evidence, List.of("generic"),
                    List.of(), Map.of("buildTool", "pip / setuptools"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            if (hasFile(repoDir, "requirements.txt")) {
                ops.put("build", new Config.CommandBlock(List.of("python3", "-m", "pip", "install", "-r", "requirements.txt"),
                        env, "repo", 120 * 60000L, 0, true));
            }
            ops.put("test", new Config.CommandBlock(List.of("python3", "-m", "pytest"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of("python3", "-m", "pip", "install", "."),
                    env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // ------------------------------------------------------------------- Node

    public static final class NodeAdapter implements ProjectAdapter {
        public String typeId() { return "node"; }
        public String displayName() { return "Node.js"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "package", "install"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("http-server") || kind.equals("custom-command");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "package.json")) {
                return unsupported("No package.json found");
            }
            List<String> evidence = new ArrayList<>();
            evidence.add("package.json present");
            if (hasFile(dir, "package-lock.json")) {
                evidence.add("package-lock.json (npm)");
            }
            if (hasFile(dir, "yarn.lock")) {
                evidence.add("yarn.lock (yarn)");
            }
            return new DetectionResult("node", "DETECTED", evidence, List.of("generic"),
                    List.of(), Map.of("buildTool", "npm / yarn"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            String pm = hasFile(repoDir, "yarn.lock") ? "yarn" : "npm";
            ops.put("build", new Config.CommandBlock(List.of(pm, "run", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("cleanBuild", new Config.CommandBlock(List.of(pm, "run", "clean", "&&", pm, "run", "build"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("test", new Config.CommandBlock(List.of(pm, "test"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of(pm, "pack"),
                    env, "repo", 120 * 60000L, 0, true));
            ops.put("install", new Config.CommandBlock(List.of(pm, "install"),
                    env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // ----------------------------------------------------------------- Docker

    public static final class DockerAdapter implements ProjectAdapter {
        public String typeId() { return "docker"; }
        public String displayName() { return "Docker-based project"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "package"));
        }

        public boolean supportsLaunchKind(String kind) {
            return kind.equals("custom-command") || kind.equals("http-server");
        }

        public DetectionResult detect(Path dir) {
            if (!hasFile(dir, "Dockerfile") && !hasFile(dir, "docker-compose.yml")
                    && !hasFile(dir, "docker-compose.yaml") && !hasFile(dir, "compose.yml")) {
                return unsupported("No Dockerfile or compose file found");
            }
            List<String> evidence = new ArrayList<>();
            if (hasFile(dir, "Dockerfile")) {
                evidence.add("Dockerfile present");
            }
            if (hasAnyFile(dir, "docker-compose.yml", "docker-compose.yaml", "compose.yml")) {
                evidence.add("Compose file present");
            }
            return new DetectionResult("docker", "PROPOSED", evidence, List.of("generic"),
                    List.of(), Map.of("buildTool", "Docker"));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            Map<String, Config.CommandBlock> ops = new LinkedHashMap<>();
            ops.put("build", new Config.CommandBlock(List.of("docker", "build", "-t",
                    repoDir.getFileName().toString(), "."), env, "repo", 120 * 60000L, 0, true));
            ops.put("package", new Config.CommandBlock(List.of("docker", "build", "-t",
                    repoDir.getFileName().toString(), "."), env, "repo", 120 * 60000L, 0, true));
            return ops;
        }
    }

    // ----------------------------------------------------------------- generic

    public static final class GenericAdapter implements ProjectAdapter {
        public String typeId() { return "generic"; }
        public String displayName() { return "Generic executable / script-based project"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test"));
        }

        public boolean supportsLaunchKind(String kind) {
            return true;
        }

        public DetectionResult detect(Path dir) {
            List<String> evidence = new ArrayList<>();
            for (String s : List.of("build.sh", "Makefile", "justfile", "Taskfile.yml",
                    "package.json", "requirements.txt", "Cargo.toml", "go.mod")) {
                if (hasFile(dir, s)) {
                    evidence.add(s + " present");
                }
            }
            if (evidence.isEmpty()) {
                return new DetectionResult("generic", "UNKNOWN", List.of("No recognized build markers"),
                        List.of("custom"), List.of(),
                        Map.of("hint", "Provide explicit build commands in project settings."));
            }
            return new DetectionResult("generic", "PROPOSED", evidence, List.of("custom"),
                    List.of(), Map.of("hint", "Commands can be configured explicitly."));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------ custom

    public static final class CustomAdapter implements ProjectAdapter {
        public String typeId() { return "custom"; }
        public String displayName() { return "Custom project (fully user-defined)"; }

        public Set<String> supportedOperations() {
            return new LinkedHashSet<>(List.of("build", "cleanBuild", "test", "package",
                    "verify", "install", "update"));
        }

        public boolean supportsLaunchKind(String kind) {
            return true;
        }

        public DetectionResult detect(Path dir) {
            return new DetectionResult("custom", "UNKNOWN",
                    List.of("No automatic detection performed"),
                    List.of(), List.of(),
                    Map.of("hint", "All commands and launch configurations are user-defined."));
        }

        public Map<String, Config.CommandBlock> proposeOperations(Path repoDir, Map<String, String> env) {
            return new LinkedHashMap<>();
        }
    }

    // ------------------------------------------------------------------ utils

    private static DetectionResult unsupported(String why) {
        return new DetectionResult("custom", "UNSUPPORTED", List.of(why),
                List.of(), List.of(),
                Map.of("hint", "No supported project type detected automatically."));
    }

    static String availableCpus() {
        int n = Runtime.getRuntime().availableProcessors();
        return String.valueOf(Math.max(1, n));
    }

    /**
     * Builds the JAVA_HOME environment for operation proposals: the JDK
     * matching the maximum Java release required by the project's POMs takes
     * precedence; otherwise an already-provided JAVA_HOME is honored and
     * finally Java 21 is used as the default.
     */
    static Map<String, String> javaEnv(Map<String, String> env, Path repoDir) {
        Map<String, String> e = new LinkedHashMap<>();
        if (env != null) {
            e.putAll(env);
        }
        Map<String, Object> tools = detectTools();
        String required = requiredJavaFromPom(repoDir);
        if (!required.isEmpty()) {
            String jdk = String.valueOf(tools.getOrDefault("java" + required, ""));
            if (!jdk.isBlank()) {
                e.put("JAVA_HOME", jdk);
            }
            return e;
        }
        if (e.containsKey("JAVA_HOME") && !e.get("JAVA_HOME").isBlank()) {
            return e;
        }
        String jdk = String.valueOf(tools.getOrDefault("java21", ""));
        if (!jdk.isBlank()) {
            e.put("JAVA_HOME", jdk);
        }
        return e;
    }

    /** Counts build modules for common multi-module layouts (read-only). */
    public static List<String> detectModules(Path dir, String typeId) {
        List<String> out = new ArrayList<>();
        try {
            switch (typeId) {
                case "maven", "eclipse-tycho" -> {
                    if (hasFile(dir, "pom.xml")) {
                        String pom = readFirstLines(dir.resolve("pom.xml"), 3000);
                        Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(pom);
                        while (m.find()) {
                            out.add(m.group(1).trim());
                        }
                    }
                }
                case "gradle" -> {
                    Path set = dir.resolve("settings.gradle");
                    if (!Files.exists(set)) {
                        set = dir.resolve("settings.gradle.kts");
                    }
                    if (Files.exists(set)) {
                        String content = readFirstLines(set, 2000);
                        Matcher m = Pattern.compile("['\"]([^'\"]+)['\"]").matcher(content);
                        while (m.find()) {
                            String mod = m.group(1);
                            if (mod.startsWith(":") && !mod.equals(":") && !mod.startsWith("://")) {
                                out.add(mod);
                            }
                        }
                    }
                }
                case "cargo" -> {
                    if (hasFile(dir, "Cargo.toml")) {
                        String toml = readFirstLines(dir.resolve("Cargo.toml"), 3000);
                        Matcher m = Pattern.compile("^\s*['\"]([^'\"]+)['\"]\s*[,=]").matcher(toml);
                        // workspace members only
                        int wsIdx = toml.indexOf("[workspace]");
                        if (wsIdx >= 0) {
                            String ws = toml.substring(wsIdx);
                            Matcher m2 = Pattern.compile("^\s*['\"]([^'\"]+)['\"]\s*,?\s*$", Pattern.MULTILINE).matcher(ws);
                            while (m2.find()) {
                                out.add(m2.group(1).trim());
                            }
                        }
                    }
                }
                default -> { }
            }
        } catch (Exception ignored) {
            // best effort
        }
        return out;
    }

    /**
     * Structural metadata for a repository: authoritative parent/aggregator,
     * reactor modules (with roles), feature/products/target definitions and
     * Eclipse launch configurations. Read-only; no repository scripts are run.
     */
    public static Map<String, Object> detectStructure(Path dir, String typeId) {
        Map<String, Object> s = new LinkedHashMap<>();
        Path parent = parentPom(dir);
        s.put("parentPom", parent == null ? "" : dir.relativize(parent).toString());
        s.put("parentIsRepoRoot", parent != null && parent.equals(dir.resolve("pom.xml")));
        s.put("mavenWrapper", hasFile(dir, "mvnw"));
        List<String> modules = detectModules(dir, typeId);
        List<Map<String, Object>> moduleInfo = new ArrayList<>();
        List<String> testModules = new ArrayList<>();
        for (String m : modules) {
            Path md = dir.resolve(m);
            Map<String, Object> mi = new LinkedHashMap<>();
            mi.put("name", m);
            String role = moduleRole(dir, m);
            mi.put("role", role);
            if (role.contains("test")) {
                testModules.add(m);
            }
            moduleInfo.add(mi);
        }
        s.put("modules", moduleInfo);
        s.put("moduleCount", modules.size());
        s.put("testModules", testModules);
        List<String> features = findPaths(dir, 5, "feature.xml");
        s.put("features", features);
        List<String> products = findPaths(dir, 6, "*.product");
        s.put("products", products);
        List<String> targets = findPaths(dir, 6, "*.target");
        s.put("targetPlatforms", targets);
        Map<String, String> pm = productMeta(dir, products);
        if (pm != null) {
            s.put("productUid", pm.getOrDefault("uid", ""));
            s.put("productName", pm.getOrDefault("name", ""));
            s.put("productVersion", pm.getOrDefault("version", ""));
        }
        String repoModule = repositoryModule(dir);
        if (repoModule != null) {
            Path repoDirPath = dir.resolve(repoModule);
            s.put("updateRepository", repoModule + "/target/repository");
            s.put("updateRepositoryExists",
                    Files.isDirectory(repoDirPath.resolve("target/repository")));
            s.put("productOutput", repoModule + "/target/products");
            s.put("productOutputExists", Files.isDirectory(repoDirPath.resolve("target/products")));
        }
        s.put("platforms", platformProfiles(dir));
        List<String> launches = findPaths(dir, 4, "*.launch");
        s.put("eclipseLaunchConfigs", launches);
        List<String> builtLaunchers = new ArrayList<>();
        Path prodRoot = findProductRoot(dir);
        if (prodRoot != null) {
            try (var stream = Files.walk(prodRoot, 6)) {
                for (Path f : (Iterable<Path>) stream::iterator) {
                    if (Files.isRegularFile(f) && Files.isExecutable(f)) {
                        String n = f.getFileName().toString();
                        if (n.endsWith(".sh") || n.endsWith(".ini") || n.contains(".app")) {
                            continue;
                        }
                        builtLaunchers.add(dir.relativize(f).toString());
                    }
                }
            } catch (IOException ignored) {
                // skip
            }
        }
        s.put("builtProductLaunchers", builtLaunchers);
        String requiredJava = requiredJavaFromPom(dir);
        s.put("requiresJava", requiredJava);
        return s;
    }

    /**
     * Locates the authoritative parent/aggregator POM: the repository root
     * pom.xml when present, otherwise the shallowest pom.xml with
     * <packaging>pom</packaging> and <module> entries.
     */
    public static Path parentPom(Path dir) {
        if (hasFile(dir, "pom.xml")) {
            return dir.resolve("pom.xml");
        }
        Path best = null;
        int bestDepth = Integer.MAX_VALUE;
        try (var stream = Files.walk(dir, 5)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(dir) || !p.getFileName().toString().equals("pom.xml")) {
                    continue;
                }
                String pom = readFirstLines(p, 300);
                if (pom.contains("<module>") || pom.contains("<packaging>pom</packaging>")) {
                    int depth = p.getNameCount() - dir.getNameCount();
                    if (depth < bestDepth) {
                        bestDepth = depth;
                        best = p;
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return best;
    }

    /** Selects a working Maven command: wrapper, Maven 3.9+ dist, else plain mvn. */
    static String mavenCommand(Path repoDir) {
        if (hasFile(repoDir, "mvnw")) {
            return "./mvnw";
        }
        try (var dists = Files.list(Path.of(System.getProperty("user.home"), ".m2", "wrapper", "dists"))) {
            for (Path d : (Iterable<Path>) dists::iterator) {
                if (!d.getFileName().toString().startsWith("apache-maven-3.")) {
                    continue;
                }
                try (var versions = Files.list(d)) {
                    for (Path h : (Iterable<Path>) versions::iterator) {
                        Path bin = h.resolve("bin/mvn");
                        if (Files.isExecutable(bin)) {
                            return bin.toString();
                        }
                    }
                } catch (IOException ignored) {
                    // try next
                }
            }
        } catch (IOException ignored) {
            // fall back to plain mvn
        }
        return "mvn";
    }

    /** Reactor module that assembles the product/p2 repository (eclipse-repository). */
    static String repositoryModule(Path repoDir) {
        for (String mod : detectModules(repoDir, "eclipse-tycho")) {
            Path md = repoDir.resolve(mod);
            if (!Files.isDirectory(md) || !Files.exists(md.resolve("pom.xml"))) {
                continue;
            }
            if (readFirstLines(md.resolve("pom.xml"), 200).contains("eclipse-repository")) {
                return mod;
            }
        }
        return null;
    }

    /** Parses uid/name/version from a .product file (read-only, best effort). */
    static Map<String, String> productMeta(Path dir, List<String> products) {
        String repoModule = repositoryModule(dir);
        List<String> ordered = new ArrayList<>(products);
        ordered.sort((a, b) -> Integer.compare(
                repoModule != null && a.startsWith(repoModule + "/") ? 0 : 1,
                repoModule != null && b.startsWith(repoModule + "/") ? 0 : 1));
        for (String rel : ordered) {
            Path f = dir.resolve(rel);
            if (!Files.isRegularFile(f)) {
                continue;
            }
            String xml = readFirstLines(f, 60);
            Matcher uid = Pattern.compile("<product[^>]*uid=\"([^\"]+)\"").matcher(xml);
            Matcher name = Pattern.compile("<product[^>]*name=\"([^\"]+)\"").matcher(xml);
            Matcher ver = Pattern.compile("<product[^>]*version=\"([^\"]+)\"").matcher(xml);
            if (!uid.find()) {
                continue;
            }
            Map<String, String> m = new LinkedHashMap<>();
            m.put("uid", uid.group(1));
            m.put("name", name.find() ? name.group(1) : "");
            m.put("version", ver.find() ? ver.group(1) : "");
            return m;
        }
        return null;
    }

    /** Export platform profiles/attributes declared in the root POM (read-only). */
    static List<String> platformProfiles(Path dir) {
        List<String> out = new ArrayList<>();
        Path pom = dir.resolve("pom.xml");
        if (!Files.isRegularFile(pom)) {
            return out;
        }
        String xml = readFirstLines(pom, 4000);
        Matcher m = Pattern.compile("<id>(linux|windows|macosx|all-platforms)</id>").matcher(xml);
        while (m.find() && !out.contains(m.group(1))) {
            out.add(m.group(1));
        }
        if (out.isEmpty()) {
            Matcher env = Pattern.compile("<environment><os>([^<]+)</os><ws>([^<]+)</ws><arch>([^<]+)</arch>")
                    .matcher(xml);
            while (env.find()) {
                out.add(env.group(1) + "/" + env.group(2) + "/" + env.group(3));
            }
        }
        return out;
    }

    /** Classifies a reactor module directory by its role (read-only). */
    static String moduleRole(Path repoDir, String module) {
        Path md = repoDir.resolve(module);
        if (!Files.isDirectory(md)) {
            return "unknown";
        }
        if (Files.exists(md.resolve("feature.xml"))) {
            return "feature";
        }
        if (findAny(md, 3, "*.product") != null) {
            return "product";
        }
        if (findAny(md, 3, "*.target") != null) {
            return "target-platform";
        }
        if (Files.exists(md.resolve("pom.xml"))) {
            String pom = readFirstLines(md.resolve("pom.xml"), 200);
            boolean tests = pom.contains("eclipse-test-plugin")
                    || pom.contains("<scope>test</scope>");
            if (pom.contains("eclipse-feature")) {
                return "feature";
            }
            if (pom.contains("eclipse-repository")) {
                return "repository";
            }
            if (pom.contains("eclipse-plugin") || pom.contains("eclipse-test-plugin")) {
                return tests ? "test-bundle" : "bundle";
            }
            return "jar";
        }
        if (Files.exists(md.resolve("MANIFEST.MF"))) {
            return "bundle";
        }
        return "unknown";
    }

    /** Java release required by any module POM (e.g. "25"); empty if <= 21. */
    static String requiredJavaFromPom(Path dir) {
        int max = 0;
        try (var stream = Files.walk(dir, 5)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                String rel = dir.relativize(p).toString();
                if (rel.contains("/target/") || rel.contains("/bin/")) {
                    continue;
                }
                if (!p.getFileName().toString().equals("pom.xml")) {
                    continue;
                }
                String pom = readFirstLines(p, 500);
                Matcher m = Pattern.compile(
                        "(?:<release>|<maven\\.compiler\\.release>)(\\d+)").matcher(pom);
                while (m.find()) {
                    try {
                        max = Math.max(max, Integer.parseInt(m.group(1)));
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return max >= 22 ? String.valueOf(max) : "";
    }

    /** Relative paths of files matching a name pattern (maxDepth). */
    public static List<String> findPaths(Path dir, int maxDepth, String name) {
        List<String> out = new ArrayList<>();
        try (var stream = Files.walk(dir, maxDepth)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(dir)) {
                    continue;
                }
                String rel = dir.relativize(p).toString();
                if (rel.contains("/target/") || rel.contains("/bin/")) {
                    continue;
                }
                String fn = p.getFileName() == null ? "" : p.getFileName().toString();
                if (!Files.isRegularFile(p)) {
                    continue;
                }
                if (name.contains("*")) {
                    if (matchesGlob(fn, name)) {
                        out.add(rel);
                    }
                } else if (fn.equals(name)) {
                    out.add(rel);
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        out.sort(String::compareTo);
        return out;
    }

    /** True for jar names that can be executed directly (no sources/docs/shade originals). */
    static boolean isRunnableJarName(String n) {
        return n.endsWith(".jar")
                && !n.endsWith("-sources.jar")
                && !n.endsWith("-javadoc.jar")
                && !n.endsWith("-tests.jar")
                && !n.startsWith("original-")
                && !n.endsWith("-shaded.jar");
    }

    static String jarMainClass(Path jar) {        try (JarFile jf = new JarFile(jar.toFile())) {
            Attributes attrs = jf.getManifest() != null ? jf.getManifest().getMainAttributes() : null;
            return attrs == null ? null : attrs.getValue(Attributes.Name.MAIN_CLASS);
        } catch (Exception e) {
            return null;
        }
    }

    /** Recursively finds the first file matching a name pattern within maxDepth. */
    static Path findAny(Path dir, int maxDepth, String name) {
        boolean glob = name.contains("*");
        try (var stream = Files.walk(dir, maxDepth)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.equals(dir)) {
                    continue;
                }
                String fn = p.getFileName() == null ? "" : p.getFileName().toString();
                if (glob) {
                    if (matchesGlob(fn, name)) {
                        return p;
                    }
                } else if (fn.equals(name)) {
                    return p;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    static boolean matchesGlob(String name, String pattern) {
        StringBuilder sb = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            if (c == '*') {
                sb.append(".*");
            } else if (c == '.' || c == '+') {
                sb.append('\\').append(c);
            } else {
                sb.append(c);
            }
        }
        return name.matches(sb.toString());
    }

    /** Finds a Tycho exported products root (…/target/products/…). */
    static Path findProductRoot(Path dir) {
        try (var stream = Files.walk(dir, 8)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (p.getFileName() != null && p.getFileName().toString().equals("products")
                        && p.getParent() != null && p.getParent().getFileName() != null
                        && p.getParent().getFileName().toString().equals("target")) {
                    return p;
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return null;
    }

    /** Detects using the best matching adapter. */
    public static DetectionResult detect(Path repoDir) {
        DetectionResult best = null;
        for (ProjectAdapter a : REGISTRY.values()) {
            if (a.typeId().equals("custom")) {
                continue;
            }
            DetectionResult r = a.detect(repoDir);
            if ("DETECTED".equals(r.confidence)) {
                return r;
            }
            if ("PROPOSED".equals(r.confidence) && best == null) {
                best = r;
            }
        }
        return best != null ? best : REGISTRY.get("generic").detect(repoDir);
    }

    /** Tool detection for the Settings view. */
    public static Map<String, Object> detectTools() {
        Map<String, Object> tools = new LinkedHashMap<>();
        tools.put("git", which("git"));
        tools.put("java21", javaVersion("21"));
        tools.put("java25", javaVersion("25"));
        tools.put("maven", which("mvn"));
        tools.put("gradle", which("gradle"));
        tools.put("cmake", which("cmake"));
        tools.put("make", which("make"));
        tools.put("gcc", which("gcc"));
        tools.put("xdgOpen", which("xdg-open"));
        tools.put("zenity", which("zenity"));
        return tools;
    }

    private static String which(String name) {
        String pathEnv = System.getenv("PATH");
        if (pathEnv == null) {
            return "";
        }
        for (String d : pathEnv.split(":")) {
            if (Files.isExecutable(Path.of(d, name))) {
                return Path.of(d, name).toString();
            }
        }
        return "";
    }

    private static String javaVersion(String major) {
        for (String base : List.of("/usr/lib/jvm/java-" + major + "-openjdk-amd64",
                "/usr/lib/jvm/openjdk-" + major)) {
            if (Files.isExecutable(Path.of(base, "bin", "java"))) {
                return base;
            }
        }
        return "";
    }
}
