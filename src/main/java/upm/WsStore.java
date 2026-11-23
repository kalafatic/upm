package upm;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Workspace storage: multiple independent workspaces under workspaces/<id>/.
 * Import/export produce portable configuration packages (metadata only —
 * never repositories or model files).
 */
public final class WsStore {

    private final App app;

    public WsStore(App app) {
        this.app = app;
    }

    public Path workspacesDir() {
        return app.workspacesDir;
    }

    public Path workspaceDir(String id) {
        if (!id.matches("[A-Za-z][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("Invalid workspace id: " + id);
        }
        return app.workspacesDir.resolve(id);
    }

    public Path workspaceFile(String id) {
        return workspaceDir(id).resolve("workspace.jsonc");
    }

    public synchronized List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path root = workspacesDir();
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                Path wf = dir.resolve("workspace.jsonc");
                if (!Files.isRegularFile(wf)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", dir.getFileName().toString());
                try {
                    Map<String, Object> rootMap = Json.obj(Json.parse(
                            Json.stripComments(Files.readString(wf))));
                    Map<String, Object> ws = Json.objField(rootMap, "workspace");
                    m.put("name", Json.str(ws, "name", dir.getFileName().toString()));
                    m.put("description", Json.str(ws, "description", ""));
                    m.put("projectCount", Json.objList(rootMap, "projects").size());
                    m.put("profileCount", Json.objList(rootMap, "profiles").size());
                } catch (Exception e) {
                    m.put("name", dir.getFileName().toString());
                    m.put("error", "Invalid workspace file: " + e.getMessage());
                }
                out.add(m);
            }
        } catch (IOException ignored) {
            // fall through
        }
        out.sort(Comparator.comparing(m -> (String) m.get("id")));
        return out;
    }

    public synchronized Config create(String id, String name, String description) throws IOException {
        if (Files.exists(workspaceFile(id))) {
            throw new IllegalArgumentException("Workspace '" + id + "' already exists.");
        }
        Config c = Config.create(workspaceFile(id), id, name, description);
        c.save();
        return c;
    }

    public synchronized Config load(String id) throws IOException {
        Path wf = workspaceFile(id);
        if (!Files.exists(wf)) {
            throw new IllegalArgumentException("Workspace '" + id + "' not found.");
        }
        return Config.load(wf);
    }

    public synchronized void deleteConfig(String id, boolean archive) throws IOException {
        Path dir = workspaceDir(id);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Workspace '" + id + "' not found.");
        }
        if (archive) {
            String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
            Path target = app.backupDir.resolve("archived-workspace-" + id + "-" + ts);
            Files.createDirectories(target.getParent());
            Files.move(dir, target);
        } else {
            deleteRecursively(dir);
        }
    }

    /** Recursively deletes a directory tree (used only by explicit user actions). */
    public static void deleteTree(Path dir) throws IOException {
        deleteRecursively(dir);
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            Files.deleteIfExists(dir);
            return;
        }
        try (var stream = Files.list(dir)) {
            for (Path p : (Iterable<Path>) stream::iterator) {
                deleteRecursively(p);
            }
        }
        Files.deleteIfExists(dir);
    }

    // ------------------------------------------------------------- export

    /** Exports a workspace configuration package as a zip byte array. */
    public synchronized byte[] export(String id) throws IOException {
        Path dir = workspaceDir(id);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Workspace '" + id + "' not found.");
        }
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bos)) {
            // Configuration only: workspace.jsonc, profiles/*.jsonc, projects/*.jsonc, dashboard.
            Path wf = dir.resolve("workspace.jsonc");
            zipFile(zip, wf, "workspace.jsonc");
            Path profiles = dir.resolve("profiles");
            if (Files.isDirectory(profiles)) {
                try (var stream = Files.list(profiles)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        if (Files.isRegularFile(f)) {
                            zipFile(zip, f, "profiles/" + f.getFileName());
                        }
                    }
                }
            }
            Path projects = dir.resolve("projects");
            if (Files.isDirectory(projects)) {
                try (var stream = Files.list(projects)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        if (Files.isRegularFile(f)) {
                            zipFile(zip, f, "projects/" + f.getFileName());
                        }
                    }
                }
            }
            Path dashboard = dir.resolve("dashboard.jsonc");
            if (Files.isRegularFile(dashboard)) {
                zipFile(zip, dashboard, "dashboard.jsonc");
            }
            ZipEntry e = new ZipEntry("export-info.json");
            zip.putNextEntry(e);
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("exportedAt", System.currentTimeMillis());
            info.put("sourceWorkspace", id);
            info.put("format", "upm/1");
            zip.write(Json.write(info).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return bos.toByteArray();
    }

    private static void zipFile(ZipOutputStream zip, Path f, String entry) throws IOException {
        ZipEntry e = new ZipEntry(entry);
        zip.putNextEntry(e);
        Files.copy(f, zip);
        zip.closeEntry();
    }

    // ------------------------------------------------------------- import

    /** Parses an exported zip without applying it: returns a preview. */
    public synchronized Map<String, Object> previewImport(byte[] zipBytes) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, byte[]> entries = readZip(zipBytes);
        byte[] wf = entries.get("workspace.jsonc");
        if (wf == null) {
            out.put("error", "Not a valid UPM workspace export (workspace.jsonc missing).");
            return out;
        }
        try {
            Map<String, Object> root = Json.obj(Json.parse(
                    Json.stripComments(new String(wf, java.nio.charset.StandardCharsets.UTF_8))));
            Map<String, Object> ws = Json.objField(root, "workspace");
            out.put("workspaceId", Json.str(ws, "id", ""));
            out.put("workspaceName", Json.str(ws, "name", ""));
            out.put("description", Json.str(ws, "description", ""));
            List<Map<String, Object>> projects = new ArrayList<>();
            for (Map<String, Object> p : Json.objList(root, "projects")) {
                Map<String, Object> pm = new LinkedHashMap<>();
                pm.put("id", Json.str(p, "id"));
                pm.put("name", Json.str(p, "name"));
                pm.put("repoDir", Json.str(p, "repoDir"));
                pm.put("type", Json.str(p, "type"));
                pm.put("exists", Json.str(p, "repoDir") != null
                        && Files.isDirectory(Path.of(Json.str(p, "repoDir"))));
                projects.add(pm);
            }
            out.put("projects", projects);
            out.put("profileCount", Json.objList(root, "profiles").size());
        } catch (Exception e) {
            out.put("error", "workspace.jsonc in the export is invalid: " + e.getMessage());
        }
        return out;
    }

    /**
     * Applies an imported export: creates the workspace, remaps repository
     * paths, optionally into a new workspace id. Returns the new workspace id.
     */
    public synchronized String applyImport(byte[] zipBytes, String newWorkspaceId,
            Map<String, String> pathRemaps) throws IOException {
        Map<String, byte[]> entries = readZip(zipBytes);
        byte[] wf = entries.get("workspace.jsonc");
        if (wf == null) {
            throw new IllegalArgumentException("Invalid export: workspace.jsonc missing.");
        }
        String content = new String(wf, java.nio.charset.StandardCharsets.UTF_8);
        // Remap repository paths (simple string replacement on the exact values).
        if (pathRemaps != null) {
            for (Map.Entry<String, String> rm : pathRemaps.entrySet()) {
                content = content.replace("\"" + rm.getKey() + "\"", "\"" + rm.getValue() + "\"");
            }
        }
        Map<String, Object> root = Json.obj(Json.parse(Json.stripComments(content)));
        Map<String, Object> ws = Json.objField(root, "workspace");
        String sourceId = Json.str(ws, "id", "imported");
        String targetId = newWorkspaceId != null && !newWorkspaceId.isBlank()
                ? newWorkspaceId : sourceId;
        if (Files.exists(workspaceFile(targetId))) {
            throw new IllegalArgumentException("Workspace '" + targetId + "' already exists.");
        }
        Path targetDir = workspaceDir(targetId);
        Files.createDirectories(targetDir);
        Files.writeString(targetDir.resolve("workspace.jsonc"), content);
        if (entries.containsKey("dashboard.jsonc")) {
            Files.writeString(targetDir.resolve("dashboard.jsonc"),
                    new String(entries.get("dashboard.jsonc"), java.nio.charset.StandardCharsets.UTF_8));
        }
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getKey().startsWith("profiles/") && !e.getKey().endsWith("/")) {
                Path f = targetDir.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, e.getValue());
            }
            if (e.getKey().startsWith("projects/") && !e.getKey().endsWith("/")) {
                Path f = targetDir.resolve(e.getKey());
                Files.createDirectories(f.getParent());
                Files.write(f, e.getValue());
            }
        }
        // Validate the imported workspace before returning.
        Config c = Config.load(workspaceFile(targetId));
        List<String> errors = c.validate();
        if (!errors.isEmpty()) {
            deleteRecursively(targetDir);
            throw new IllegalArgumentException("Imported workspace is invalid: "
                    + String.join("; ", errors));
        }
        return targetId;
    }

    private static Map<String, byte[]> readZip(byte[] zipBytes) {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(
                new java.io.ByteArrayInputStream(zipBytes))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) {
                    continue;
                }
                byte[] data = zin.readAllBytes();
                if (e.getName().startsWith("__MACOSX") || e.getName().startsWith(".")) {
                    continue;
                }
                if (data.length > 20 * 1024 * 1024) {
                    continue;
                }
                out.put(e.getName(), data);
            }
        } catch (IOException ignored) {
            // partial read
        }
        return out;
    }

    // ------------------------------------------------------------- backups

    public synchronized List<Map<String, Object>> listBackups() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path root = app.backupDir;
        if (!Files.isDirectory(root)) {
            return out;
        }
        try (var stream = Files.list(root)) {
            for (Path dir : (Iterable<Path>) stream::iterator) {
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", dir.getFileName().toString());
                try {
                    m.put("createdMs", Files.getLastModifiedTime(dir).toMillis());
                    m.put("workspaceId", dir.getFileName().toString()
                            .replaceFirst("^workspace-backup-", "")
                            .replaceFirst("^archived-workspace-", ""));
                } catch (IOException ignored) {
                    // skip
                }
                out.add(m);
            }
        } catch (IOException ignored) {
            // fall through
        }
        out.sort((a, b) -> Long.compare((long) (long) b.getOrDefault("createdMs", 0L),
                (long) a.getOrDefault("createdMs", 0L)));
        return out;
    }

    /** Creates a backup of a workspace (configuration only). */
    public synchronized String backup(String workspaceId) throws IOException {
        Path dir = workspaceDir(workspaceId);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException("Workspace '" + workspaceId + "' not found.");
        }
        String ts = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path target = app.backupDir.resolve("workspace-backup-" + workspaceId + "-" + ts);
        Files.createDirectories(target.getParent());
        copyTree(dir, target, 3);
        return target.getFileName().toString();
    }

    /** Restores a workspace from a backup (existing workspace is replaced after validation). */
    public synchronized void restore(String backupName, String workspaceId) throws IOException {
        Path backup = app.backupDir.resolve(backupName);
        if (!Files.isDirectory(backup)) {
            throw new IllegalArgumentException("Backup '" + backupName + "' not found.");
        }
        Path restored = backup.resolve("workspace.jsonc");
        if (!Files.isRegularFile(restored)) {
            throw new IllegalArgumentException("Backup does not contain workspace.jsonc.");
        }
        // Validate the backup before touching anything.
        Config c = Config.load(restored);
        List<String> errors = c.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Backup workspace is invalid: "
                    + String.join("; ", errors));
        }
        Path dir = workspaceDir(workspaceId);
        if (Files.isDirectory(dir)) {
            deleteRecursively(dir);
        }
        copyTree(backup, dir, 3);
    }

    private static void copyTree(Path src, Path dst, int maxDepth) throws IOException {
        Files.createDirectories(dst);
        try (var stream = Files.list(src)) {
            for (Path f : (Iterable<Path>) stream::iterator) {
                Path rel = src.relativize(f);
                if (rel.getNameCount() > maxDepth) {
                    continue;
                }
                if (Files.isDirectory(f)) {
                    copyTree(f, dst.resolve(rel), maxDepth);
                } else {
                    Files.createDirectories(dst.resolve(rel).getParent());
                    Files.copy(f, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    /** Per-workspace project config fragment dir (extra project overrides). */
    public Path projectsDir(String workspaceId) {
        return workspaceDir(workspaceId).resolve("projects");
    }
}
