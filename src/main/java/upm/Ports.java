package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Port Manager: the centralized inventory of every known EVO-family port plus
 * a read-only live check against actual system listeners.
 *
 * <p>Inventory entries are application knowledge (family port plan), not
 * workspace configuration. Status checking is purely observational: it reads
 * {@code /proc/net/tcp{,6}} and resolves socket inodes to owning processes.
 * Nothing here terminates processes or changes port assignments.
 *
 * <p>Status classification (exactly three states):
 * <ul>
 *   <li>{@code RUNNING} — the port is listening and the owning process
 *       matches the application the port is assigned to.</li>
 *   <li>{@code READY} — the port is free and available for its service.</li>
 *   <li>{@code OCCUPIED} — the port is listening, but the owner is not the
 *       expected service (foreign or unidentifiable process).</li>
 * </ul>
 */
public final class Ports {

    /** Inventory classification: implemented / historical / proposed. */
    public static final class Entry {
        public final String app;
        public final int port;
        public final String service;
        public final String description;
        public final String protocol;
        public final String kind;
        public final String binding;
        public final String expectedProcess;

        Entry(String app, int port, String service, String description,
                String protocol, String kind, String binding, String expectedProcess) {
            this.app = app;
            this.port = port;
            this.service = service;
            this.description = description;
            this.protocol = protocol;
            this.kind = kind;
            this.binding = binding;
            this.expectedProcess = expectedProcess;
        }
    }

    private static final String IMPLEMENTED = "implemented";
    private static final String HISTORICAL = "historical";
    private static final String PROPOSED = "proposed";

    private static final List<Entry> INVENTORY = buildInventory();

    private Ports() {
    }

    private static List<Entry> buildInventory() {
        List<Entry> l = new ArrayList<>();

        // ------------------------------------------------------------- EVO 28xxx
        l.add(new Entry("EVO", 28080, "HTTP Server",
                "Main EVO HTTP server providing access to EVO web interfaces and HTTP endpoints.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "evolution"));
        l.add(new Entry("EVO", 28180, "Secondary / Portal",
                "Secondary EVO web portal and associated web services.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "evolution"));
        l.add(new Entry("EVO", 28280, "MCP Server",
                "Model Context Protocol endpoint for external AI clients and tools.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "evolution"));
        l.add(new Entry("EVO", 28380, "DEVELOP",
                "Development interface and services for the EVO development environment.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "evolution"));
        l.add(new Entry("EVO", 28480, "Supervisor REST",
                "REST interface for monitoring and controlling the EVO Supervisor.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "evolution"));
        l.add(new Entry("EVO", 28580, "Supervisor Control TCP",
                "Dedicated TCP control channel for Supervisor communication.",
                "TCP", IMPLEMENTED, "127.0.0.1", "evolution"));
        l.add(new Entry("EVO", 28680, "Auxiliary",
                "Reserved auxiliary EVO service endpoint (disabled by default in ports.properties).",
                "HTTP", IMPLEMENTED, "127.0.0.1", "evolution"));

        // --------------------------------------------------------------- ENR
        l.add(new Entry("ENR", 8111, "HTTP Server",
                "ENR (EVO Native Runtime) HTTP API. Verified in enr.yaml and "
                        + "RuntimeConfig (default port 8111): model discovery, inference "
                        + "and orchestration requests under /api/v1.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "enr"));
        l.add(new Entry("ENR", 28780, "HTTP Server",
                "Previously configured ENR HTTP endpoint (EVO ports.properties "
                        + "ENR.port=28780, enabled=false). Historical legacy assignment.",
                "HTTP", HISTORICAL, "127.0.0.1", "enr"));
        l.add(new Entry("ENR", 28880, "Proposed HTTP",
                "Recommended dedicated ENR endpoint; not yet confirmed as implemented.",
                "HTTP", PROPOSED, "127.0.0.1", "enr"));

        // ------------------------------------------------------------ TRYST 38xxx
        l.add(new Entry("TRYST", 38080, "HTTP Server",
                "Main TRYST web interface and HTTP server. Default port; the runtime "
                        + "may allocate a dynamic fallback (see ~/.tryst/port-allocations.properties).",
                "HTTP", IMPLEMENTED, "127.0.0.1", "tryst"));
        l.add(new Entry("TRYST", 38180, "API",
                "Application API for communication with TRYST services.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "tryst"));
        l.add(new Entry("TRYST", 38280, "TCP Server",
                "Dedicated TCP communication endpoint.",
                "TCP", IMPLEMENTED, "127.0.0.1", "tryst"));
        l.add(new Entry("TRYST", 38380, "Management",
                "Management and administrative interface.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "tryst"));
        l.add(new Entry("TRYST", 38480, "WebSocket",
                "Persistent bidirectional communication with web clients.",
                "WebSocket", IMPLEMENTED, "127.0.0.1", "tryst"));
        l.add(new Entry("TRYST", 38580, "Auxiliary",
                "Auxiliary TRYST service endpoint.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "tryst"));
        l.add(new Entry("TRYST", 38680, "Debug",
                "Development and diagnostic endpoint.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "tryst"));

        // ------------------------------------------------------------- DCode 48xxx
        l.add(new Entry("DCode", 48080, "HTTP Server",
                "Main DCode web interface and HTTP services (REST + SSE + web UI). "
                        + "Verified in DCodePorts.HTTP and currently the default endpoint.",
                "HTTP", IMPLEMENTED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48081, "RCP Instance",
                "Proposed local RCP instance communication endpoint; verify implementation. "
                        + "NOTE: DStudio currently uses 48081 for its legacy headless TEIP "
                        + "server — see the DStudio discrepancy notes.",
                "HTTP", PROPOSED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48180, "Reserved",
                "Reserved for future DCode secondary services.",
                "HTTP", PROPOSED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48280, "Reserved",
                "Reserved for future DCode communication services.",
                "TCP", PROPOSED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48380, "Management",
                "Proposed administrative and monitoring endpoint (defined as "
                        + "DCodePorts.MANAGEMENT; no live listener observed yet).",
                "HTTP", PROPOSED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48480, "Reserved",
                "Reserved for future DCode services.",
                "HTTP", PROPOSED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48580, "Reserved",
                "Reserved for future DCode services.",
                "TCP", PROPOSED, "127.0.0.1", "dcode"));
        l.add(new Entry("DCode", 48680, "Auxiliary",
                "Proposed auxiliary service endpoint (defined as DCodePorts.AUX; "
                        + "no live listener observed yet).",
                "HTTP", PROPOSED, "127.0.0.1", "dcode"));

        // ----------------------------------------------------------- DStudio 58xxx
        l.add(new Entry("DStudio", 58080, "HTTP Server",
                "Main DStudio web interface and HTTP services (TEIP HTTP protocol). "
                        + "Family port plan: the DStudio codebase still defaults to the "
                        + "legacy 48xxx block (48080/48180), which collides with DCode; "
                        + "58xxx is the planned authoritative home.",
                "HTTP", PROPOSED, "127.0.0.1", "dstudio"));
        l.add(new Entry("DStudio", 58180, "Secondary / API",
                "Secondary application API and web communication endpoint (planned; "
                        + "currently implemented as diagnostics on legacy 48180).",
                "HTTP", PROPOSED, "127.0.0.1", "dstudio"));
        l.add(new Entry("DStudio", 58280, "Reserved",
                "Reserved for future DStudio communication services.",
                "TCP", PROPOSED, "127.0.0.1", "dstudio"));
        l.add(new Entry("DStudio", 58380, "Management",
                "Proposed administrative and monitoring endpoint.",
                "HTTP", PROPOSED, "127.0.0.1", "dstudio"));
        l.add(new Entry("DStudio", 58480, "Reserved",
                "Reserved for future DStudio services.",
                "HTTP", PROPOSED, "127.0.0.1", "dstudio"));
        l.add(new Entry("DStudio", 58580, "Reserved",
                "Reserved for future DStudio services.",
                "TCP", PROPOSED, "127.0.0.1", "dstudio"));
        l.add(new Entry("DStudio", 58680, "Auxiliary",
                "Proposed auxiliary service endpoint.",
                "HTTP", PROPOSED, "127.0.0.1", "dstudio"));

        // -------------------------------------------------------------- Ollama
        l.add(new Entry("Ollama", 11434, "HTTP API",
                "Local Ollama API used for model discovery, inference, chat and "
                        + "generation requests (also the model registry ENR relies on).",
                "HTTP", IMPLEMENTED, "127.0.0.1", "ollama"));

        return List.copyOf(l);
    }

    public static List<Entry> inventory() {
        return INVENTORY;
    }

    // ------------------------------------------------------------ live check

    /** Quick set of TCP ports with LISTEN sockets (no fd walk). */
    public static java.util.Set<Integer> listeningPorts() {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        for (Listener l : readListeners()) {
            set.add(l.port());
        }
        return set;
    }

    /** One observed listening socket. */
    record Listener(String address, int port, long uid, String inode) {
    }

    /**
     * Checks the full inventory against actual system listeners and returns
     * JSON-ready rows including process/PID/binding/status per port.
     */
    public static Map<String, Object> checkAll() {
        long now = System.currentTimeMillis();
        List<Listener> listeners = readListeners();
        Map<String, Long> inodeOwners = resolveInodeOwners();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Entry e : INVENTORY) {
            rows.add(checkEntry(e, listeners, inodeOwners, now));
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        for (String s : List.of("RUNNING", "READY", "OCCUPIED")) {
            long c = rows.stream().filter(r -> s.equals(r.get("status"))).count();
            summary.put(s.toLowerCase(), c);
        }
        out.put("timeMs", now);
        out.put("summary", summary);
        out.put("entries", rows);
        return out;
    }

    /** Live data for the UPM control center itself (self-identification). */
    public static Map<String, Object> controlCenter(int port, String host) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("host", host);
        m.put("port", port);
        m.put("pid", Main.selfPid);
        m.put("process", "java -jar universal-project-manager.jar");
        m.put("status", port > 0 ? "RUNNING" : "READY");
        m.put("timeMs", System.currentTimeMillis());
        return m;
    }

    private static Map<String, Object> checkEntry(Entry e, List<Listener> listeners,
            Map<String, Long> inodeOwners, long now) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("app", e.app);
        m.put("port", e.port);
        m.put("service", e.service);
        m.put("description", e.description);
        m.put("protocol", e.protocol);
        m.put("kind", e.kind);
        m.put("lastChecked", now);

        List<Listener> bound = new ArrayList<>();
        for (Listener l : listeners) {
            if (l.port() == e.port) {
                bound.add(l);
            }
        }
        if (bound.isEmpty()) {
            m.put("status", "READY");
            m.put("process", "");
            m.put("pid", 0);
            m.put("binding", e.binding);
            m.put("detail", "Port free; " + e.service + " can start on "
                    + (e.kind.equals(PROPOSED) ? "the proposed" : "its assigned") + " port.");
            return m;
        }

        // Resolve every listening socket on this port. A single foreign
        // listener already means OCCUPIED (conflict), even when the expected
        // service holds another socket on the same port.
        String detail = "";
        List<Map<String, Object>> owners = new ArrayList<>();
        for (Listener l : bound) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("binding", l.address());
            Long pid = inodeOwners.get(l.inode());
            String cmd = pid == null ? null : readCmdline(pid);
            String verified = "direct";
            if (pid == null || cmd == null || cmd.isBlank()) {
                Long inferred = findExpectedPid(e.expectedProcess, l.uid());
                if (inferred != null) {
                    pid = inferred;
                    cmd = readCmdline(inferred);
                    verified = "indirect";
                }
            }
            o.put("pid", pid);
            o.put("cmd", cmd);
            o.put("verified", verified);
            o.put("uid", l.uid());
            owners.add(o);
        }

        Map<String, Object> conflicting = null;
        Map<String, Object> running = null;
        for (Map<String, Object> o : owners) {
            Long pid = (Long) o.get("pid");
            String cmd = (String) o.get("cmd");
            if (pid == null || cmd == null || cmd.isBlank()) {
                conflicting = o;
                break;
            }
            if (matchesExpected(e.expectedProcess, cmd)) {
                if (running == null) {
                    running = o;
                }
            } else {
                conflicting = o;
                break;
            }
        }

        if (conflicting != null && running == null) {
            String cmd = (String) conflicting.get("cmd");
            Long pid = (Long) conflicting.get("pid");
            m.put("status", "OCCUPIED");
            if (cmd == null || cmd.isBlank()) {
                m.put("process", "unknown (uid " + conflicting.get("uid") + ")");
                m.put("pid", 0);
                m.put("verified", "none");
                detail = "Port is listening, but the owning process is not visible "
                        + "to this user (foreign or privileged process).";
            } else {
                m.put("process", shortCmd(cmd));
                m.put("pid", pid);
                m.put("verified", conflicting.get("verified"));
                detail = "Port occupied by an unexpected process (not the expected "
                        + e.app + " service).";
            }
        } else if (conflicting != null) {
            // Expected service present, but an additional unexpected listener
            // exists on the same port — still a conflict.
            String cmd = (String) conflicting.get("cmd");
            Long pid = (Long) conflicting.get("pid");
            m.put("status", "OCCUPIED");
            if (cmd == null || cmd.isBlank()) {
                m.put("process", "unknown (uid " + conflicting.get("uid") + ")");
                m.put("pid", 0);
                m.put("verified", "none");
                detail = "Port has multiple listeners; one is not visible to this "
                        + "user (foreign or privileged process).";
            } else {
                m.put("process", shortCmd(cmd));
                m.put("pid", pid);
                m.put("verified", conflicting.get("verified"));
                detail = "Port has multiple listeners; one belongs to an unexpected "
                        + "process (not the expected " + e.app + " service).";
            }
        } else {
            m.put("status", "RUNNING");
            m.put("process", shortCmd((String) running.get("cmd")));
            m.put("pid", running.get("pid"));
            detail = "direct".equals(running.get("verified"))
                    ? "Expected service listening on its assigned port."
                    : "Expected service detected (process name and socket UID match; "
                            + "the socket owner could not be read directly because it "
                            + "runs under another user account).";
            m.put("verified", running.get("verified"));
        }
        List<String> addresses = new ArrayList<>();
        for (Map<String, Object> o : owners) {
            String a = (String) o.get("binding");
            if (!addresses.contains(a)) {
                addresses.add(a);
            }
        }
        m.put("binding", String.join(", ", addresses));
        m.put("detail", detail);
        return m;
    }

    /**
     * Looks for a process running under the given UID whose command line
     * matches the expected service. Used when the socket owner cannot be
     * resolved through fd links (e.g. services running as another user).
     */
    private static Long findExpectedPid(String expected, long uid) {
        try (var stream = Files.list(Path.of("/proc"))) {
            for (Path pidDir : (Iterable<Path>) stream::iterator) {
                String name = pidDir.getFileName().toString();
                if (!name.matches("\\d+")) {
                    continue;
                }
                try {
                    Path status = pidDir.resolve("status");
                    if (!Files.isReadable(status)) {
                        continue;
                    }
                    long processUid = -1;
                    for (String line : Files.readAllLines(status)) {
                        if (line.startsWith("Uid:")) {
                            String[] f = line.trim().split("\\s+");
                            if (f.length >= 2) {
                                processUid = Long.parseLong(f[1]);
                            }
                            break;
                        }
                    }
                    if (processUid != uid) {
                        continue;
                    }
                    long pid = Long.parseLong(name);
                    String cmd = readCmdline(pid);
                    if (cmd != null && matchesExpected(expected, cmd)) {
                        return pid;
                    }
                } catch (IOException | NumberFormatException ignored) {
                    // unreadable process entry
                }
            }
        } catch (IOException ignored) {
            // /proc unavailable
        }
        return null;
    }

    private static boolean matchesExpected(String expected, String cmdline) {
        String cmd = cmdline.toLowerCase(Locale.ROOT);
        for (String token : expected.split(",")) {
            if (cmd.contains(token.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String shortCmd(String cmd) {
        return cmd.length() > 120 ? cmd.substring(0, 120) + "…" : cmd;
    }

    private static String readCmdline(long pid) {
        try {
            Path f = Path.of("/proc", String.valueOf(pid), "cmdline");
            if (!Files.exists(f)) {
                return null;
            }
            return Files.readString(f).replace('\0', ' ').strip();
        } catch (IOException e) {
            return null;
        }
    }

    /** Reads all LISTEN TCP sockets from /proc/net/tcp{,6}. */
    static List<Listener> readListeners() {
        List<Listener> out = new ArrayList<>();
        for (String file : List.of("/proc/net/tcp", "/proc/net/tcp6")) {
            try {
                List<String> lines = Files.readAllLines(Path.of(file));
                for (int i = 1; i < lines.size(); i++) {
                    String[] f = lines.get(i).trim().split("\\s+");
                    if (f.length < 10 || !"0A".equals(f[3])) {
                        continue; // not LISTEN
                    }
                    String local = f[1];
                    int idx = local.lastIndexOf(':');
                    if (idx < 0) {
                        continue;
                    }
                    try {
                        int port = Integer.parseInt(local.substring(idx + 1), 16);
                        long uid = Long.parseLong(f[7]);
                        out.add(new Listener(decodeAddress(local.substring(0, idx)),
                                port, uid, f[9]));
                    } catch (NumberFormatException ignored) {
                        // malformed row
                    }
                }
            } catch (IOException ignored) {
                // /proc/net unavailable
            }
        }
        return out;
    }

    /** Decodes the hex local-address field (IPv4 or IPv6, little-endian words). */
    private static String decodeAddress(String hex) {
        if (hex.length() == 8) {
            // IPv4, stored as one little-endian 32-bit word
            try {
                long v = Long.parseLong(hex, 16);
                return ((v & 0xFF)) + "." + ((v >> 8) & 0xFF) + "."
                        + ((v >> 16) & 0xFF) + "." + ((v >> 24) & 0xFF);
            } catch (NumberFormatException e) {
                return hex;
            }
        }
        if (hex.length() == 32) {
            // IPv6: four little-endian 32-bit words
            byte[] b = new byte[16];
            for (int word = 0; word < 4; word++) {
                String w = hex.substring(word * 8, word * 8 + 8);
                for (int i = 0; i < 4; i++) {
                    int hi = word * 4 + i;
                    b[hi] = (byte) Integer.parseInt(w.substring((3 - i) * 2, (3 - i) * 2 + 2), 16);
                }
            }
            // IPv4-mapped (::ffff:a.b.c.d) — format as dotted quad
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                return (b[12] & 0xFF) + "." + (b[13] & 0xFF) + "."
                        + (b[14] & 0xFF) + "." + (b[15] & 0xFF);
            }
            try {
                return java.net.InetAddress.getByAddress(b).getHostAddress();
            } catch (Exception e) {
                return hex;
            }
        }
        return hex;
    }

    /** One pass over the proc fd directories building socket-inode to pid. */
    static Map<String, Long> resolveInodeOwners() {
        Map<String, Long> map = new LinkedHashMap<>();
        try (var stream = Files.list(Path.of("/proc"))) {
            for (Path pidDir : (Iterable<Path>) stream::iterator) {
                String name = pidDir.getFileName().toString();
                if (!name.matches("\\d+")) {
                    continue;
                }
                long pid;
                try {
                    pid = Long.parseLong(name);
                } catch (NumberFormatException e) {
                    continue;
                }
                Path fdDir = pidDir.resolve("fd");
                if (!Files.isDirectory(fdDir) || !Files.isReadable(fdDir)) {
                    continue;
                }
                try (var fds = Files.list(fdDir)) {
                    for (Path fd : (Iterable<Path>) fds::iterator) {
                        try {
                            String target = Files.readSymbolicLink(fd).toString();
                            if (target.startsWith("socket:[")) {
                                String inode = target.substring(8, target.length() - 1);
                                map.putIfAbsent(inode, pid);
                            }
                        } catch (IOException ignored) {
                            // fd gone
                        }
                    }
                } catch (IOException ignored) {
                    // unreadable fd dir
                }
            }
        } catch (IOException ignored) {
            // /proc unavailable
        }
        return map;
    }
}
