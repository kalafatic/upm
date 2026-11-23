package upm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;

import org.junit.jupiter.api.Test;

/** Port-conflict handling: T09 (occupied configured port) and T10 (external untouched). */
class UpmPortConflictTest {

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

    @Test
    void occupied_configured_port_falls_back_and_classifies() throws Exception {
        int base = freePort();
        // T10 setup: an external process (simulated by an unrelated socket) owns `base`.
        ServerSocket external = new ServerSocket(base);
        try (Harness h = Harness.create(base, 3)) {
            h.server.start();
            // T09: UPM must not silently bind `base`; it allocates deterministically.
            assertEquals(base + 1, h.server.boundPort(), "must fall back to the next free port");
            assertEquals(Server.Lifecycle.READY, h.server.lifecycle());

            String status = get("http://127.0.0.1:" + h.server.boundPort() + "/api/server");
            assertTrue(status.contains("\"configuredPort\":" + base), status);
            assertTrue(status.contains("\"boundPort\":" + (base + 1)), status);
            assertTrue(status.contains("occupied by external process"), status);

            // T10: the external socket is never touched.
            assertTrue(!external.isClosed(), "external socket must remain open");
            assertTrue(Probe.tcpOpen("127.0.0.1", base, 800), "external listener must remain bound");
        } finally {
            external.close();
        }
        // After the external process is gone, the configured port is free again.
        assertFalse(Probe.tcpOpen("127.0.0.1", base, 500));
    }
}
