package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live system monitoring with historical sampling. Real measurements only:
 * /proc, nvidia-smi (optional), /proc/net. History is kept in bounded
 * in-memory ring buffers (configurable retention, no unbounded growth).
 */
public final class Metrics {

    public static final int DEFAULT_INTERVAL_MS = 1000;
    public static final int DEFAULT_HISTORY_POINTS = 3600;

    private volatile int intervalMs = DEFAULT_INTERVAL_MS;
    private volatile int historyPoints = DEFAULT_HISTORY_POINTS;

    // CPU
    private long lastCpuIdle = -1;
    private long lastCpuTotal = -1;
    private double lastCpuPct = Double.NaN;
    private Map<Integer, long[]> lastCore = new ConcurrentHashMap<>(); // core -> {idle,total}
    private Map<Integer, Double> corePct = new ConcurrentHashMap<>();

    // Network
    private long lastNetRx = -1;
    private long lastNetTx = -1;
    private long lastNetTime = -1;
    private double netRxKbps = Double.NaN;
    private double netTxKbps = Double.NaN;

    // Per-process CPU
    private final Map<Long, long[]> lastProcCpu = new ConcurrentHashMap<>();

    // History ring buffers: metric name -> list of {t, v} maps
    private final Map<String, java.util.ArrayDeque<Map<String, Object>>> history = new ConcurrentHashMap<>();

    private Thread sampler;

    public synchronized void configure(int intervalMs, int historyPoints) {
        this.intervalMs = Math.max(500, intervalMs);
        this.historyPoints = Math.max(60, Math.min(historyPoints, 86400));
    }

    public int intervalMs() {
        return intervalMs;
    }

    public int historyPoints() {
        return historyPoints;
    }

    public synchronized void startSampling() {
        if (sampler != null) {
            return;
        }
        sampler = new Thread(this::sampleLoop, "upm-metrics");
        sampler.setDaemon(true);
        sampler.start();
    }

    private void sampleLoop() {
        while (true) {
            long t0 = System.currentTimeMillis();
            sample();
            long sleep = Math.max(200, intervalMs - (System.currentTimeMillis() - t0));
            try {
                Thread.sleep(sleep);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** Takes one sample and appends to history buffers. */
    public synchronized void sample() {
        long now = System.currentTimeMillis();
        Map<String, Object> cpu = cpu();
        Map<String, Object> mem = memory();
        List<Map<String, Object>> gpus = gpu();
        double[] net = network();
        put("cpu", now, cpu.get("percent"));
        put("load1", now, cpu.get("load1"));
        put("memPercent", now, mem.get("percent"));
        put("memUsedKb", now, mem.get("usedKb"));
        put("swapPercent", now, mem.get("swapPercent"));
        if (!gpus.isEmpty()) {
            Map<String, Object> g = gpus.get(0);
            put("gpuUtil", now, g.get("utilPercent"));
            put("vramUsedMb", now, g.get("memUsedMb"));
            put("gpuTemp", now, g.get("temperature"));
            put("gpuPower", now, g.get("powerW"));
        }
        put("netRxKbps", now, net[0]);
        put("netTxKbps", now, net[1]);
        // Per-process samples for managed processes.
        if (Main.currentConfig != null) {
            for (Config.Project p : Main.currentConfig.projects) {
                State.ProjectState ps = Main.getState() == null ? null
                        : Main.getState().stateFor(p.id);
                if (ps == null || ps.pid <= 0) {
                    continue;
                }
                Map<String, Object> pm = processMetrics(ps.pid);
                if (pm.containsKey("cpuPercent")) {
                    put("proc:" + p.id + ":cpu", now, pm.get("cpuPercent"));
                }
                if (pm.containsKey("rssMb")) {
                    put("proc:" + p.id + ":rss", now, pm.get("rssMb"));
                }
            }
        }
        // Inference activity (from ENR when available).
        if (Main.currentConfig != null) {
            for (Config.Project p : Main.currentConfig.projects) {
                if (!p.enrMonitor || p.apiBase == null || p.apiBase.isBlank()) {
                    continue;
                }
                String body = Probe.httpGet(p.apiBase + "/metrics", 1500);
                if (body != null) {
                    try {
                        Map<String, Object> mm = Json.obj(Json.parse(body));
                        if (mm.containsKey("tokens_per_second")) {
                            put("enr:tps", now, mm.get("tokens_per_second"));
                        }
                        if (mm.containsKey("total_requests")) {
                            put("enr:requests", now, mm.get("total_requests"));
                        }
                    } catch (Exception ignored) {
                        // not JSON
                    }
                }
            }
        }
    }

    private void put(String metric, long t, Object value) {
        if (value == null || value instanceof Double d && d.isNaN()
                || "N/A".equals(String.valueOf(value))) {
            return;
        }
        java.util.ArrayDeque<Map<String, Object>> buf = history.computeIfAbsent(metric,
                k -> new java.util.ArrayDeque<>());
        Map<String, Object> point = new LinkedHashMap<>();
        point.put("t", t);
        point.put("v", value);
        synchronized (buf) {
            buf.addLast(point);
            while (buf.size() > historyPoints) {
                buf.removeFirst();
            }
        }
    }

    public Map<String, Object> historySince(String metric, long sinceMs, int maxPoints) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("metric", metric);
        List<Map<String, Object>> out = new ArrayList<>();
        java.util.ArrayDeque<Map<String, Object>> buf = history.get(metric);
        if (buf != null) {
            synchronized (buf) {
                for (Map<String, Object> p : buf) {
                    long t = ((Number) p.get("t")).longValue();
                    if (t >= sinceMs) {
                        out.add(p);
                    }
                }
            }
        }
        if (out.size() > maxPoints) {
            int step = (int) Math.ceil(out.size() / (double) maxPoints);
            List<Map<String, Object>> down = new ArrayList<>();
            for (int i = 0; i < out.size(); i += step) {
                down.add(out.get(i));
            }
            out = down;
        }
        m.put("points", out);
        return m;
    }

    public List<String> historyMetrics() {
        return new ArrayList<>(history.keySet());
    }

    // ------------------------------------------------------------------- cpu

    public synchronized Map<String, Object> cpu() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc", "stat"));
            for (String line : lines) {
                if (line.startsWith("cpu ")) {
                    String[] f = line.trim().split("\\s+");
                    long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
                    long total = 0;
                    for (int i = 1; i < f.length; i++) {
                        total += Long.parseLong(f[i]);
                    }
                    if (lastCpuTotal > 0) {
                        long dTotal = total - lastCpuTotal;
                        long dIdle = idle - lastCpuIdle;
                        if (dTotal > 0) {
                            lastCpuPct = Math.max(0.0, Math.min(100.0, 100.0 * (dTotal - dIdle) / dTotal));
                        }
                    }
                    lastCpuIdle = idle;
                    lastCpuTotal = total;
                } else if (line.startsWith("cpu") && line.length() > 3
                        && Character.isDigit(line.charAt(3))) {
                    String[] f = line.trim().split("\\s+");
                    int core = Integer.parseInt(f[0].substring(3));
                    long idle = Long.parseLong(f[4]) + Long.parseLong(f[5]);
                    long total = 0;
                    for (int i = 1; i < f.length; i++) {
                        total += Long.parseLong(f[i]);
                    }
                    long[] prev = lastCore.get(core);
                    if (prev != null && total - prev[1] > 0) {
                        double pct = 100.0 * ((total - prev[1]) - (idle - prev[0])) / (total - prev[1]);
                        corePct.put(core, Math.round(Math.max(0, Math.min(100, pct)) * 10.0) / 10.0);
                    }
                    lastCore.put(core, new long[] { idle, total });
                }
            }
            List<String> la = Files.readAllLines(Path.of("/proc", "loadavg"));
            if (!la.isEmpty()) {
                String[] parts = la.get(0).split("\\s+");
                m.put("load1", parts[0]);
                m.put("load5", parts[1]);
                m.put("load15", parts[2]);
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through
        }
        m.put("percent", Double.isNaN(lastCpuPct) ? "N/A" : Math.round(lastCpuPct * 10.0) / 10.0);
        List<Map<String, Object>> cores = new ArrayList<>();
        List<Integer> ids = new ArrayList<>(corePct.keySet());
        java.util.Collections.sort(ids);
        for (int id : ids) {
            Map<String, Object> c = new LinkedHashMap<>();
            c.put("core", id);
            c.put("percent", corePct.get(id));
            cores.add(c);
        }
        m.put("cores", cores);
        m.put("frequencyMhz", cpuFrequency());
        return m;
    }

    private String cpuFrequency() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc", "cpuinfo"));
            long sum = 0;
            int count = 0;
            for (String line : lines) {
                if (line.startsWith("cpu MHz")) {
                    try {
                        sum += (long) Double.parseDouble(line.split(":")[1].trim());
                        count++;
                    } catch (NumberFormatException ignored) {
                        // skip
                    }
                }
            }
            return count > 0 ? String.valueOf(sum / count) : "N/A";
        } catch (IOException e) {
            return "N/A";
        }
    }

    // ------------------------------------------------------------------ mem

    public Map<String, Object> memory() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            long total = 0;
            long available = 0;
            long swapTotal = 0;
            long swapFree = 0;
            for (String line : Files.readAllLines(Path.of("/proc", "meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    total = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("MemAvailable:")) {
                    available = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("SwapTotal:")) {
                    swapTotal = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("SwapFree:")) {
                    swapFree = Long.parseLong(line.split("\\s+")[1]);
                }
            }
            if (total > 0) {
                long used = total - available;
                m.put("totalKb", total);
                m.put("usedKb", used);
                m.put("availableKb", available);
                m.put("percent", Math.round(1000.0 * used / total) / 10.0);
            }
            if (swapTotal > 0) {
                long swapUsed = swapTotal - swapFree;
                m.put("swapTotalKb", swapTotal);
                m.put("swapUsedKb", swapUsed);
                m.put("swapPercent", Math.round(1000.0 * swapUsed / swapTotal) / 10.0);
            } else {
                m.put("swapPercent", "N/A");
            }
        } catch (IOException | NumberFormatException ignored) {
            // fall through
        }
        Runtime rt = Runtime.getRuntime();
        m.put("jvmTotalMb", rt.totalMemory() / (1024 * 1024));
        m.put("jvmUsedMb", (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024));
        m.put("jvmMaxMb", rt.maxMemory() / (1024 * 1024));
        return m;
    }

    // ----------------------------------------------------------------- disk

    public Map<String, Object> disk() {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Path mp : new Path[] { Path.of("/home"), Path.of("/") }) {
            try {
                long total = mp.toFile().getTotalSpace();
                long free = mp.toFile().getUsableSpace();
                long used = total - free;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("totalGb", Math.round(total / (1024.0 * 1024 * 1024) * 10.0) / 10.0);
                e.put("usedGb", Math.round(used / (1024.0 * 1024 * 1024) * 10.0) / 10.0);
                e.put("freeGb", Math.round(free / (1024.0 * 1024 * 1024) * 10.0) / 10.0);
                e.put("percent", total > 0 ? Math.round(1000.0 * used / total) / 10.0 : 0);
                m.put(mp.toString(), e);
            } catch (Exception ignored) {
                // skip
            }
        }
        return m;
    }

    /** Repository and model directory sizes (bounded, on demand). */
    public List<Map<String, Object>> storageSizes() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (Main.currentConfig == null) {
            return out;
        }
        for (Config.Project p : Main.currentConfig.projects) {
            long size = dirSize(Path.of(p.repoDir), 6);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("label", p.id + " repository");
            e.put("path", p.repoDir);
            e.put("bytes", size);
            e.put("mib", Math.round(size / 1048576.0 * 10.0) / 10.0);
            out.add(e);
        }
        return out;
    }

    private long dirSize(Path dir, int maxDepth) {
        if (maxDepth <= 0 || !Files.isDirectory(dir)) {
            return 0;
        }
        long total = 0;
        try (var stream = Files.list(dir)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                String name = child.getFileName().toString();
                if (Scanner.DEFAULT_EXCLUSIONS.contains(name)) {
                    continue;
                }
                try {
                    if (Files.isDirectory(child)) {
                        total += dirSize(child, maxDepth - 1);
                    } else {
                        total += Files.size(child);
                    }
                } catch (IOException ignored) {
                    // skip
                }
                if (total > 20L * 1024 * 1024 * 1024) {
                    break; // bounded
                }
            }
        } catch (IOException ignored) {
            // skip
        }
        return total;
    }

    // ------------------------------------------------------------------ gpu

    public synchronized List<Map<String, Object>> gpu() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=index,name,utilization.gpu,memory.total,memory.used,memory.free,temperature.gpu,power.draw,driver_version",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                for (String line : text.split("\n")) {
                    String[] f = line.strip().split(",\\s*");
                    if (f.length < 9) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", f[0]);
                    m.put("name", f[1]);
                    m.put("utilPercent", f[2].trim());
                    m.put("memTotalMb", f[3].trim());
                    m.put("memUsedMb", f[4].trim());
                    m.put("memFreeMb", f[5].trim());
                    m.put("temperature", f[6].trim());
                    m.put("powerW", f[7].trim());
                    m.put("driver", f[8].trim());
                    out.add(m);
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            // no GPU data available
        }
        return out;
    }

    /** Compute processes using the GPU (nvidia-smi --query-compute-apps). */
    public List<Map<String, Object>> gpuProcesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-compute-apps=pid,process_name,used_memory",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                for (String line : text.split("\n")) {
                    String[] f = line.strip().split(",\\s*");
                    if (f.length < 3) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("pid", f[0].trim());
                    m.put("processName", f[1].trim());
                    m.put("usedMemoryMb", f[2].trim());
                    out.add(m);
                }
            } else {
                p.destroyForcibly();
            }
        } catch (Exception ignored) {
            // unavailable
        }
        return out;
    }

    // -------------------------------------------------------------- network

    public synchronized double[] network() {
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc", "net", "dev"));
            long rx = 0;
            long tx = 0;
            for (String line : lines) {
                if (!line.contains(":")) {
                    continue;
                }
                String[] f = line.trim().split(":\\s*");
                if (f.length < 2) {
                    continue;
                }
                String name = f[0].trim();
                if (!name.equals("lo")) {
                    String[] nums = f[1].trim().split("\\s+");
                    if (nums.length > 8) {
                        try {
                            rx += Long.parseLong(nums[0]);
                            tx += Long.parseLong(nums[8]);
                        } catch (NumberFormatException ignored) {
                            // skip
                        }
                    }
                }
            }
            long now = System.currentTimeMillis();
            if (lastNetTime > 0 && now > lastNetTime) {
                double dt = (now - lastNetTime) / 1000.0;
                netRxKbps = Math.round((rx - lastNetRx) / 1024.0 / dt * 10.0) / 10.0;
                netTxKbps = Math.round((tx - lastNetTx) / 1024.0 / dt * 10.0) / 10.0;
            }
            lastNetRx = rx;
            lastNetTx = tx;
            lastNetTime = now;
        } catch (IOException ignored) {
            // fall through
        }
        return new double[] { netRxKbps, netTxKbps };
    }

    // ------------------------------------------------------------ processes

    /** CPU % and RSS for one process (delta-based). */
    public Map<String, Object> processMetrics(long pid) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (pid <= 0) {
            return m;
        }
        try {
            String stat = Files.readString(Path.of("/proc", String.valueOf(pid), "stat"));
            int close = stat.lastIndexOf(')');
            String[] f = stat.substring(close + 2).split(" ");
            if (f.length < 22) {
                return m;
            }
            long utime = Long.parseLong(f[11]);
            long stime = Long.parseLong(f[12]);
            long rss = Long.parseLong(f[21]) * 4096;
            long ticks = 100; // CLK_TCK
            long now = System.currentTimeMillis();
            long[] prev = lastProcCpu.get(pid);
            if (prev != null && now > prev[2]) {
                double dt = (now - prev[2]) / 1000.0;
                double pct = 100.0 * ((utime + stime) - (prev[0] + prev[1])) / ticks / dt;
                m.put("cpuPercent", Math.round(Math.max(0, Math.min(100 * Runtime.getRuntime()
                        .availableProcessors(), pct)) * 10.0) / 10.0);
            }
            lastProcCpu.put(pid, new long[] { utime, stime, now });
            m.put("rssMb", Math.round(rss / 1048576.0 * 10.0) / 10.0);
            m.put("startTimeTicks", f[19]);
        } catch (Exception ignored) {
            // process gone or unreadable
        }
        return m;
    }

    public List<Map<String, Object>> managedProcesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        if (Main.currentConfig == null) {
            return out;
        }
        State st = Main.getState();
        for (Config.Project p : Main.currentConfig.projects) {
            State.ProjectState ps = st == null ? null : st.stateFor(p.id);
            if (ps == null || ps.pid <= 0 || !Proc.isAlive(ps.pid)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("project", p.id);
            m.put("pid", ps.pid);
            m.put("name", p.name);
            m.put("status", ps.runtimeState);
            m.put("startedAt", ps.startedAt);
            Map<String, Object> pm = processMetrics(ps.pid);
            m.put("cpuPercent", pm.getOrDefault("cpuPercent", "N/A"));
            m.put("rssMb", pm.getOrDefault("rssMb", "N/A"));
            m.put("gpuProcess", false);
            out.add(m);
        }
        // Annotate GPU compute processes.
        for (Map<String, Object> gp : gpuProcesses()) {
            for (Map<String, Object> m : out) {
                if (String.valueOf(m.get("pid")).equals(String.valueOf(gp.get("pid")))) {
                    m.put("gpuProcess", true);
                    m.put("vramUsedMb", gp.get("usedMemoryMb"));
                }
            }
        }
        return out;
    }

    public List<Map<String, Object>> javaProcesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : Probe.javaProcesses()) {
            Map<String, Object> e = new LinkedHashMap<>(m);
            String cmd = Json.str(m, "cmd", "");
            e.put("short", cmd.length() > 140 ? cmd.substring(0, 140) + "…" : cmd);
            out.add(e);
        }
        return out;
    }
}
