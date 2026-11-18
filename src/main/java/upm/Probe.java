package upm;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Readiness probing and port conflict detection. All port resolution is
 * configuration-driven (no hardcoded machine paths).
 */
public final class Probe {

    private Probe() {
    }

    public static boolean tcpOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Resolves the readiness port for a launch config. */
    public static int resolvePort(Config.Project p, Config.LaunchConfig lc, int requestedPort) {
        if (lc == null) {
            return requestedPort;
        }
        Config.Readiness r = lc.readiness;
        if (r == null) {
            return lc.port > 0 ? lc.port : requestedPort;
        }
        switch (r.resolve) {
            case "fixed":
                return r.port > 0 ? r.port : (lc.port > 0 ? lc.port : requestedPort);
            case "portsFile":
            case "allocationsFile":
                return readPropsPort(resolveSourceFile(p, r.sourceFile), r.sourceKey,
                        r.port > 0 ? r.port : (lc.port > 0 ? lc.port : requestedPort));
            case "configFile": {
                int fromFile = readYamlInt(resolveSourceFile(p, r.sourceFile), r.sourceKey);
                return fromFile > 0 ? fromFile : (r.port > 0 ? r.port : requestedPort);
            }
            default:
                return requestedPort > 0 ? requestedPort : lc.port;
        }
    }

    private static Path resolveSourceFile(Config.Project p, String sourceFile) {
        if (sourceFile == null || sourceFile.isBlank()) {
            return null;
        }
        Path f = Path.of(App.expandHome(sourceFile));
        if (!f.isAbsolute() && p.repoDir != null && !p.repoDir.isBlank()) {
            f = Path.of(p.repoDir, sourceFile);
        }
        return f;
    }

    /** Probes readiness for a launch config. */
    public static boolean probeReady(Config.Project p, Config.LaunchConfig lc, int requestedPort) {
        if (lc == null || lc.readiness == null || !"http".equals(lc.readiness.type)) {
            return false;
        }
        Config.Readiness r = lc.readiness;
        if ("range".equals(r.resolve)) {
            int start = r.port > 0 ? r.port : requestedPort;
            if (start <= 0) {
                start = lc.port;
            }
            if (start <= 0) {
                return false;
            }
            for (int port = start; port < start + 500; port++) {
                if (httpProbe(r.host, port, r.path, r.expect)) {
                    return true;
                }
            }
            return false;
        }
        if (requestedPort > 0) {
            if (httpProbe(r.host, requestedPort, r.path, r.expect)) {
                return true;
            }
        }
        int port = resolvePort(p, lc, requestedPort);
        if (port > 0 && port != requestedPort) {
            return httpProbe(r.host, port, r.path, r.expect);
        }
        return false;
    }

    public static boolean httpProbe(String host, int port, String path, String expect) {
        if (port <= 0) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            URL url = new URL("http://" + host + ":" + port + path);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(2000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if ("any".equals(expect)) {
                return code > 0;
            }
            return code == Integer.parseInt(expect);
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static int readPropsPort(Path f, String key, int dflt) {
        if (f == null || !Files.exists(f)) {
            return dflt;
        }
        try {
            for (String line : Files.readAllLines(f)) {
                String t = line.strip();
                if (t.startsWith("#") || !t.contains("=")) {
                    continue;
                }
                String[] kv = t.split("=", 2);
                if (kv[0].strip().equals(key)) {
                    try {
                        return Integer.parseInt(kv[1].strip());
                    } catch (NumberFormatException e) {
                        return dflt;
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return dflt;
    }

    private static int readYamlInt(Path f, String key) {
        if (f == null || !Files.exists(f)) {
            return -1;
        }
        try {
            for (String line : Files.readAllLines(f)) {
                String t = line.strip();
                if (t.startsWith(key + ":")) {
                    try {
                        return Integer.parseInt(t.substring(key.length() + 1).strip());
                    } catch (NumberFormatException ignored) {
                        return -1;
                    }
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return -1;
    }

    /** Owner info of a listening TCP port (best effort, inode-based). */
    public static String ownerOf(int port) {
        String hex = String.format("%04X", port);
        List<String> inodes = new ArrayList<>();
        for (String file : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                for (String line : Files.readAllLines(Path.of(file))) {
                    String[] f = line.trim().split("\\s+");
                    if (f.length < 10 || !f[3].equals("0A")) {
                        continue;
                    }
                    String local = f[1];
                    int idx = local.lastIndexOf(':');
                    if (idx >= 0 && local.substring(idx + 1).equalsIgnoreCase(hex)) {
                        inodes.add(f[9]);
                    }
                }
            } catch (IOException ignored) {
                // skip
            }
        }
        if (inodes.isEmpty()) {
            return "";
        }
        Path proc = Path.of("/proc");
        try (var stream = Files.list(proc)) {
            for (Path pidDir : (Iterable<Path>) stream::iterator) {
                String name = pidDir.getFileName().toString();
                if (!name.matches("\\d+")) {
                    continue;
                }
                try {
                    Path fdDir = pidDir.resolve("fd");
                    if (!Files.isReadable(fdDir)) {
                        continue;
                    }
                    try (var fds = Files.list(fdDir)) {
                        for (Path fd : (Iterable<Path>) fds::iterator) {
                            try {
                                String target = Files.readSymbolicLink(fd).toString();
                                for (String inode : inodes) {
                                    if (target.equals("socket:[" + inode + "]")) {
                                        String cmd = Files.exists(pidDir.resolve("cmdline"))
                                                ? Files.readString(pidDir.resolve("cmdline"))
                                                        .replace('\0', ' ').strip()
                                                : "";
                                        return "pid " + name
                                                + (cmd.isEmpty() ? "" : ": " + cmd);
                                    }
                                }
                            } catch (IOException ignored) {
                                // fd gone
                            }
                        }
                    }
                } catch (IOException ignored) {
                    // not readable — skip
                }
            }
        } catch (IOException ignored) {
            // fall through
        }
        return "";
    }

    /** HTTP GET helper returning body text or null. */
    public static String httpGet(String url, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            try (InputStream in = conn.getInputStream()) {
                return new String(in.readAllBytes());
            }
        } catch (IOException e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    /** Lists java processes of the current user. */
    public static List<Map<String, Object>> javaProcesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("ps", "-eo", "pid,ppid,user,etime,rss,args")
                    .redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            String me = System.getProperty("user.name");
            for (String line : text.split("\n")) {
                if (!line.contains("java") || line.contains("grep")) {
                    continue;
                }
                String[] parts = line.trim().split("\\s+", 6);
                if (parts.length < 6) {
                    continue;
                }
                if (!parts[2].equals(me)) {
                    continue;
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("pid", parts[0]);
                m.put("ppid", parts[1]);
                m.put("cmd", parts[5]);
                out.add(m);
            }
            p.destroy();
        } catch (IOException ignored) {
            // fall through
        }
        return out;
    }
}
