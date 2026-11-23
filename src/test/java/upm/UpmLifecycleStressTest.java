package upm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Repeated-lifecycle stress test: 50 consecutive start/stop cycles (real sockets + HTTP). */
class UpmLifecycleStressTest {

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        int code = c.getResponseCode();
        String body;
        try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            body = in == null ? "" : new String(in.readAllBytes());
        }
        c.disconnect();
        assertEquals(200, code);
        return body;
    }

    private static long upmThreadCount() {
        long n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            String name = t.getName();
            if (name.startsWith("upm-http") || name.startsWith("upm-sse-broadcast")) {
                n++;
            }
        }
        return n;
    }

    @Test
    void fifty_consecutive_lifecycle_cycles_no_leak() throws Exception {
        long before = upmThreadCount();
        for (int cycle = 1; cycle <= 50; cycle++) {
            try (Harness h = Harness.create(freePort(), 3)) {
                int configured = h.app.port;
                h.server.start();
                assertEquals(Server.Lifecycle.READY, h.server.lifecycle(), "cycle " + cycle);
                int port = h.server.boundPort();
                assertTrue(Probe.tcpOpen("127.0.0.1", port, 800), "cycle " + cycle);
                assertTrue(get("http://127.0.0.1:" + port + "/api/health")
                        .contains("\"status\":\"ok\""), "cycle " + cycle);
                h.server.stop();
                assertFalse(Probe.tcpOpen("127.0.0.1", port, 500), "cycle " + cycle);
                // Configuration is never silently rewritten by lifecycle ops.
                assertEquals(configured, h.app.port, "configured port must not change");
            }
        }
        long after = upmThreadCount();
        assertTrue(after <= before,
                "server threads leaked: before=" + before + " after=" + after);
    }
}
