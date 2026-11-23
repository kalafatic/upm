package upm;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Real-JVM process lifecycle test: repeatedly launches the actual {@code upm.Main}
 * in a fresh JVM (as Eclipse Run/Debug does), verifies it serves HTTP, shuts it
 * down and confirms the listener is released every time. Maps to T19–T23.
 */
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class UpmProcessLifecycleTest {

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String javaBin() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    static Path classesDir() {
        String sep = System.getProperty("path.separator");
        for (String e : System.getProperty("java.class.path").split(sep)) {
            if (e.endsWith("/target/classes") || e.endsWith("\\target\\classes")) {
                return Path.of(e);
            }
        }
        return Path.of("target/classes").toAbsolutePath();
    }

    static int status(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        int code = c.getResponseCode();
        c.disconnect();
        return code;
    }

    static String body(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        try (InputStream in = c.getInputStream()) {
            return new String(in.readAllBytes());
        } finally {
            c.disconnect();
        }
    }

    static void post(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(2000);
        c.setReadTimeout(2000);
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.getResponseCode();
        c.disconnect();
    }

    @Test
    void repeated_process_launch_and_shutdown_releases_port() throws Exception {
        String classes = classesDir().toString();
        String java = javaBin();
        for (int cycle = 1; cycle <= 10; cycle++) {
            Path appDir = Files.createTempDirectory("upm-proc");
            Files.createDirectories(appDir.resolve("src/main/java")); // app-root marker
            Path cfg = appDir.resolve("config/application.jsonc");
            Files.createDirectories(cfg.getParent());
            int port = freePort();
            Files.writeString(cfg, "{\"application\":{\"name\":\"UPM\",\"host\":\"127.0.0.1\","
                    + "\"port\":" + port + ",\"portFallbackRange\":3,\"openBrowser\":false,"
                    + "\"defaultWorkspace\":\"default\",\"defaultRepositoryDir\":\"" + appDir
                    + "\"}}");

            ProcessBuilder pb = new ProcessBuilder(java, "-cp", classes, "upm.Main");
            pb.directory(appDir.toFile());
            pb.redirectErrorStream(true);
            pb.redirectOutput(appDir.resolve("run.log").toFile());
            Process p = pb.start();

            String health = "http://127.0.0.1:" + port + "/api/health";
            boolean up = false;
            long deadline = System.currentTimeMillis() + 15_000;
            while (System.currentTimeMillis() < deadline) {
                if (!p.isAlive()) {
                    break;
                }
                try {
                    if (status(health) == 200) {
                        up = true;
                        break;
                    }
                } catch (Exception ignored) {
                    // not up yet
                }
                Thread.sleep(250);
            }
            assertTrue(up, "cycle " + cycle + ": process did not become ready; log="
                    + readLog(appDir.resolve("run.log")));
            assertTrue(Probe.tcpOpen("127.0.0.1", port, 800), "cycle " + cycle);

            // Repeated HTTP requests before shutdown.
            for (int i = 0; i < 3; i++) {
                assertTrue(body(health).contains("\"status\":\"ok\""), "cycle " + cycle);
            }

            post("http://127.0.0.1:" + port + "/api/shutdown");
            assertTrue(p.waitFor(15, TimeUnit.SECONDS), "cycle " + cycle + ": no clean exit");
            assertFalse(Probe.tcpOpen("127.0.0.1", port, 500),
                    "cycle " + cycle + ": listener must be released");
            WsStore.deleteTree(appDir);
        }
    }

    static String readLog(Path f) {
        try {
            if (!Files.exists(f)) {
                return "(no log)";
            }
            String s = Files.readString(f);
            return s.length() > 2000 ? s.substring(s.length() - 2000) : s;
        } catch (Exception e) {
            return "(unreadable)";
        }
    }
}
