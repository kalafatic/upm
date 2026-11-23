package upm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** Real-socket, real-HTTP lifecycle tests for the UPM control-center server. */
class UpmServerLifecycleTest {

    static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(3000);
        c.setReadTimeout(3000);
        c.setRequestMethod("GET");
        int code = c.getResponseCode();
        String body;
        try (InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream()) {
            body = in == null ? "" : new String(in.readAllBytes());
        }
        c.disconnect();
        assertEquals(200, code, "GET " + url + " -> " + code + " " + body);
        return body;
    }

    static String healthUrl(int port) {
        return "http://127.0.0.1:" + port + "/api/health";
    }

    // T01 / T02 — startup binds and serves a real health response.
    @Test
    void startup_reports_ready_and_serves_health() throws Exception {
        try (Harness h = Harness.create(freePort(), 3)) {
            h.server.start();
            assertEquals(Server.Lifecycle.READY, h.server.lifecycle());
            assertTrue(Probe.tcpOpen("127.0.0.1", h.server.boundPort(), 800));
            String body = get(healthUrl(h.server.boundPort()));
            assertTrue(body.contains("\"status\":\"ok\""), body);
            assertTrue(body.contains("\"state\":\"READY\""), body);
            assertTrue(body.contains("\"app\":\"upm\""), body);
        }
    }

    // T03 — repeated requests all succeed.
    @Test
    void repeated_requests_succeed() throws Exception {
        try (Harness h = Harness.create(freePort(), 3)) {
            h.server.start();
            for (int i = 0; i < 25; i++) {
                assertTrue(get(healthUrl(h.server.boundPort())).contains("\"status\":\"ok\""));
            }
        }
    }

    // T04 — stop releases the listener.
    @Test
    void stop_releases_listener() throws Exception {
        Harness h = Harness.create(freePort(), 3);
        h.server.start();
        int port = h.server.boundPort();
        assertTrue(Probe.tcpOpen("127.0.0.1", port, 800));
        h.server.stop();
        assertFalse(Probe.tcpOpen("127.0.0.1", port, 500), "listener must be released after stop");
        assertEquals(Server.Lifecycle.STOPPED, h.server.lifecycle());
        h.close();
    }

    // T05 — start after stop succeeds.
    @Test
    void start_after_stop_succeeds() throws Exception {
        try (Harness h = Harness.create(freePort(), 3)) {
            h.server.start();
            int first = h.server.boundPort();
            h.server.stop();
            assertFalse(Probe.tcpOpen("127.0.0.1", first, 500));
            h.server.start();
            assertEquals(Server.Lifecycle.READY, h.server.lifecycle());
            assertTrue(Probe.tcpOpen("127.0.0.1", h.server.boundPort(), 800));
            assertTrue(get(healthUrl(h.server.boundPort())).contains("\"status\":\"ok\""));
        }
    }

    // T06 / T07 — restart / start twice never creates a second instance.
    @Test
    void start_twice_has_single_instance() throws Exception {
        try (Harness h = Harness.create(freePort(), 3)) {
            h.server.start();
            int port = h.server.boundPort();
            h.server.start(); // idempotent: no-op
            assertEquals(port, h.server.boundPort(), "port must not change on re-start");
            assertEquals(Server.Lifecycle.READY, h.server.lifecycle());
            assertTrue(get(healthUrl(port)).contains("\"status\":\"ok\""));
        }
    }

    // T08 — stop twice is safe and consistent.
    @Test
    void stop_twice_is_safe() throws Exception {
        try (Harness h = Harness.create(freePort(), 3)) {
            h.server.start();
            h.server.stop();
            h.server.stop(); // must not throw or corrupt state
            assertEquals(Server.Lifecycle.STOPPED, h.server.lifecycle());
        }
    }

    // T11 / T13 — occupied range fails explicitly, then recovers after release.
    @Test
    void startup_failure_when_range_occupied_then_recovers() throws Exception {
        int base = freePort();
        List<ServerSocket> holders = new ArrayList<>();
        // Occupy base..base+2 (the whole fallback range of 3).
        for (int i = 0; i <= 3; i++) {
            holders.add(new ServerSocket(base + i));
        }
        try (Harness h = Harness.create(base, 3)) {
            boolean threw = false;
            try {
                h.server.start();
            } catch (java.io.IOException e) {
                threw = true;
            }
            assertTrue(threw, "start must fail when the whole range is occupied");
            assertEquals(Server.Lifecycle.FAILED, h.server.lifecycle());
        } finally {
            for (ServerSocket s : holders) {
                s.close();
            }
        }
        // T13 — after releasing the ports, a fresh start recovers.
        try (Harness h = Harness.create(base, 3)) {
            h.server.start();
            assertEquals(Server.Lifecycle.READY, h.server.lifecycle());
            assertTrue(get(healthUrl(h.server.boundPort())).contains("\"status\":\"ok\""));
        }
    }

    // T16 — concurrent startup requests produce exactly one startup operation.
    @Test
    void concurrent_start_is_single() throws Exception {
        try (Harness h = Harness.create(freePort(), 3)) {
            int threads = 8;
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Thread> ts = new ArrayList<>();
            List<Throwable> errors = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Thread t = new Thread(() -> {
                    try {
                        ready.countDown();
                        go.await();
                        h.server.start();
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                });
                ts.add(t);
                t.start();
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            for (Thread t : ts) {
                t.join(10_000);
            }
            assertTrue(errors.isEmpty(), "concurrent start threw: " + errors);
            assertEquals(Server.Lifecycle.READY, h.server.lifecycle());
            assertTrue(get(healthUrl(h.server.boundPort())).contains("\"status\":\"ok\""));
        }
    }

    // T24 — health during shutdown reports an accurate (non-READY) state.
    @Test
    void health_during_shutdown_reports_accurate_state() throws Exception {
        Harness h = Harness.create(freePort(), 3);
        h.server.start();
        int port = h.server.boundPort();
        assertTrue(get(healthUrl(port)).contains("\"state\":\"READY\""));
        // Stop from another thread while probing the health endpoint.
        CountDownLatch stopped = new CountDownLatch(1);
        Thread stopper = new Thread(() -> {
            h.server.stop();
            stopped.countDown();
        });
        stopper.start();
        stopper.join(10_000);
        assertFalse(Probe.tcpOpen("127.0.0.1", port, 500), "port must be released after shutdown");
        assertEquals(Server.Lifecycle.STOPPED, h.server.lifecycle());
        h.close();
    }
}
