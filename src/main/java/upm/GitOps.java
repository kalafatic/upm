package upm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Git management via the Git CLI. Read-only by default; every mutating
 * operation requires an explicit user action and never discards changes.
 * Credentials are handled by the normal Git credential helpers / SSH agent;
 * nothing is stored in project configuration.
 */
public final class GitOps {

    public static boolean installed() {
        return exec(List.of("git", "--version")).rc == 0;
    }

    /** Executes git in dir, returns rc + combined output. */
    public static Result execIn(Path dir, List<String> args, long timeoutSeconds) {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        cmd.add("-C");
        cmd.add(dir.toString());
        cmd.addAll(args);
        return exec(cmd, timeoutSeconds);
    }

    public static Result exec(List<String> cmd) {
        return exec(cmd, 120);
    }

    public static Result exec(List<String> cmd, long timeoutSeconds) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return new Result(-1, "timeout", out.toString());
            }
            return new Result(p.exitValue(), "ok", out.toString());
        } catch (Exception e) {
            return new Result(-1, e.getMessage(), "");
        }
    }

    public record Result(int rc, String error, String output) {
        public boolean ok() {
            return rc == 0;
        }
    }

    public static boolean isRepo(Path dir) {
        return Files.isDirectory(dir.resolve(".git"))
                || execIn(dir, List.of("rev-parse", "--git-dir"), 15).rc == 0;
    }

    public static Map<String, Object> status(Path dir) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("installed", installed());
        if (!isRepo(dir)) {
            m.put("isRepo", false);
            return m;
        }
        m.put("isRepo", true);
        Result branch = execIn(dir, List.of("branch", "--show-current"), 15);
        m.put("branch", branch.ok() ? branch.output().strip() : "?");
        Result head = execIn(dir, List.of("rev-parse", "HEAD"), 15);
        m.put("head", head.ok() ? head.output().strip() : "?");
        Result status = execIn(dir, List.of("status", "--porcelain"), 30);
        List<Map<String, Object>> files = new ArrayList<>();
        int staged = 0;
        int modified = 0;
        int untracked = 0;
        if (status.ok()) {
            for (String line : status.output().split("\n")) {
                if (line.isBlank()) {
                    continue;
                }
                String state = line.substring(0, Math.min(2, line.length())).strip();
                String path = line.length() > 3 ? line.substring(3) : "";
                Map<String, Object> f = new LinkedHashMap<>();
                f.put("path", path);
                f.put("state", state.isBlank() ? "untracked" : state);
                files.add(f);
                if (line.startsWith("??")) {
                    untracked++;
                } else if (line.contains(" ")) {
                    staged++;
                } else {
                    modified++;
                }
            }
        }
        m.put("files", files);
        m.put("stagedCount", staged);
        m.put("modifiedCount", modified);
        m.put("untrackedCount", untracked);
        m.put("dirty", !files.isEmpty());
        Result ahead = execIn(dir, List.of("status", "-sb"), 15);
        if (ahead.ok()) {
            String first = ahead.output().lines().findFirst().orElse("");
            m.put("tracking", first.replace("## ", "").strip());
        }
        Result remote = execIn(dir, List.of("remote", "-v"), 15);
        if (remote.ok()) {
            List<String> remotes = new ArrayList<>();
            for (String line : remote.output().split("\n")) {
                if (!line.isBlank() && !remotes.contains(line.strip())) {
                    remotes.add(line.strip());
                }
            }
            m.put("remotes", remotes);
        }
        return m;
    }

    public static Result branches(Path dir) {
        return execIn(dir, List.of("branch", "--list"), 30);
    }

    public static Result log(Path dir, int max) {
        return execIn(dir, List.of("log", "--oneline", "-n", String.valueOf(max), "--decorate"), 30);
    }

    public static Result fetch(Path dir) {
        return execIn(dir, List.of("fetch", "--all", "--prune"), 300);
    }

    public static Result pull(Path dir) {
        return execIn(dir, List.of("pull", "--ff-only"), 600);
    }

    public static Result push(Path dir) {
        return execIn(dir, List.of("push"), 600);
    }

    public static Result switchBranch(Path dir, String branch) {
        return execIn(dir, List.of("switch", branch), 120);
    }

    public static Result createBranch(Path dir, String branch) {
        return execIn(dir, List.of("switch", "-c", branch), 120);
    }

    public static Result commit(Path dir, String message) {
        return execIn(dir, List.of("commit", "-m", message), 300);
    }

    public static Result commitAll(Path dir, String message) {
        return execIn(dir, List.of("commit", "-a", "-m", message), 300);
    }

    public static Result clone(String url, Path destDir) {
        return exec(List.of("git", "clone", url, destDir.toString()), 1800);
    }

    public static Result lsRemote(String url) {
        return exec(List.of("git", "ls-remote", "--symref", url, "HEAD"), 120);
    }

    public static boolean validUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        String t = url.strip();
        if (t.startsWith("http://") || t.startsWith("https://") || t.startsWith("git@")
                || t.startsWith("ssh://") || t.startsWith("file://")) {
            return true;
        }
        if (t.startsWith("/") || (t.length() > 2 && t.charAt(1) == ':')) {
            return true; // local path
        }
        return false;
    }

    /** Default branch name from ls-remote --symref output. */
    public static String defaultBranchFrom(String lsRemoteOutput) {
        Matcher m = Pattern.compile("ref: refs/heads/([^\\s]+)\\s+HEAD").matcher(lsRemoteOutput);
        if (m.find()) {
            return m.group(1);
        }
        return "";
    }
}
