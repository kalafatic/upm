package upm;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** System monitoring: CPU, RAM, disk, JVM memory, GPU (when available). */
public final class Sys {

    private long lastIdle = -1;
    private long lastTotal = -1;
    private double lastCpu = Double.NaN;

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
                    if (lastTotal > 0) {
                        long dTotal = total - lastTotal;
                        long dIdle = idle - lastIdle;
                        if (dTotal > 0) {
                            lastCpu = Math.max(0.0,
                                    Math.min(100.0, 100.0 * (dTotal - dIdle) / dTotal));
                        }
                    }
                    lastIdle = idle;
                    lastTotal = total;
                    break;
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
        if (Double.isNaN(lastCpu)) {
            m.put("percent", "N/A");
        } else {
            m.put("percent", Math.round(lastCpu * 10.0) / 10.0);
        }
        return m;
    }

    public Map<String, Object> memory() {
        Map<String, Object> m = new LinkedHashMap<>();
        try {
            long total = 0;
            long available = 0;
            for (String line : Files.readAllLines(Path.of("/proc", "meminfo"))) {
                if (line.startsWith("MemTotal:")) {
                    total = Long.parseLong(line.split("\\s+")[1]);
                } else if (line.startsWith("MemAvailable:")) {
                    available = Long.parseLong(line.split("\\s+")[1]);
                }
            }
            if (total > 0) {
                long used = total - available;
                m.put("totalKb", total);
                m.put("usedKb", used);
                m.put("availableKb", available);
                m.put("percent", Math.round(1000.0 * used / total) / 10.0);
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

    public Map<String, Object> disk() {
        Map<String, Object> m = new LinkedHashMap<>();
        Path[] mounts = { Path.of("/home"), Path.of("/") };
        for (Path mp : mounts) {
            try {
                long total = mp.toFile().getTotalSpace();
                long free = mp.toFile().getUsableSpace();
                long used = total - free;
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("totalGb", roundGb(total));
                e.put("usedGb", roundGb(used));
                e.put("freeGb", roundGb(free));
                e.put("percent", total > 0 ? Math.round(1000.0 * used / total) / 10.0 : 0);
                m.put(mp.toString(), e);
            } catch (Exception ignored) {
                // fall through
            }
        }
        return m;
    }

    private static double roundGb(long bytes) {
        return Math.round(bytes / (1024.0 * 1024 * 1024) * 10.0) / 10.0;
    }

    public synchronized List<Map<String, Object>> gpu() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Process p = new ProcessBuilder("nvidia-smi",
                    "--query-gpu=index,name,utilization.gpu,memory.total,memory.used,memory.free,driver_version",
                    "--format=csv,noheader,nounits").redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes());
            if (p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS) && p.exitValue() == 0) {
                for (String line : text.split("\n")) {
                    String[] f = line.strip().split(",\\s*");
                    if (f.length < 7) {
                        continue;
                    }
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("index", f[0]);
                    m.put("name", f[1]);
                    m.put("utilPercent", f[2].trim());
                    m.put("memTotalMb", f[3].trim());
                    m.put("memUsedMb", f[4].trim());
                    m.put("memFreeMb", f[5].trim());
                    m.put("driver", f[6].trim());
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

    public List<Map<String, Object>> javaProcesses() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : Probe.javaProcesses()) {
            Map<String, Object> e = new LinkedHashMap<>(m);
            String cmd = Json.str(m, "cmd", "");
            e.put("short", shortCmd(cmd));
            out.add(e);
        }
        return out;
    }

    private static String shortCmd(String cmd) {
        String c = cmd.length() > 140 ? cmd.substring(0, 140) + "…" : cmd;
        return c;
    }
}
