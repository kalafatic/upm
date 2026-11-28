package upm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for the Port Manager actions: browser-URL construction, process
 * identification, graceful/forced termination, protected processes and
 * post-termination verification.
 *
 * <p>Live-process tests spawn a controlled {@code python3} child that listens
 * on a free loopback port. They never touch real application processes and are
 * skipped when {@code python3} is unavailable.</p>
 */
class PortActionsTest {

    private Process child;

    @AfterEach
    void cleanup() {
        if (child != null && child.isAlive()) {
            child.destroyForcibly();
        }
        child = null;
    }

    // ---------------------------------------------------------------- browser URL

    @Test
    void browserUrl_http_fromInventory() {
        assertEquals("http://127.0.0.1:28080", Ports.browserUrl(28080)); // EVO HTTP
    }

    @Test
    void browserUrl_https_fromEntry() {
        Ports.Entry e = new Ports.Entry("X", 8443, "HTTPS", "d", "HTTPS",
                "implemented", "127.0.0.1", "x");
        assertEquals("https://127.0.0.1:8443", Ports.browserUrl(e));
    }

    @Test
    void browserUrl_rejectsNonHttp() {
        assertNull(Ports.browserUrl(38280)); // TRYST TCP
        assertNull(Ports.browserUrl(38480)); // TRYST WebSocket
        assertNull(Ports.browserUrl(9999));  // unknown
        assertNull(Ports.schemeFor("TCP"));
        assertNull(Ports.schemeFor("WebSocket"));
    }

    @Test
    void normalizeHost_mapsWildcardsToLoopback() {
        assertEquals("127.0.0.1", Ports.normalizeHost("0.0.0.0"));
        assertEquals("127.0.0.1", Ports.normalizeHost("::"));
        assertEquals("127.0.0.1", Ports.normalizeHost("0:0:0:0:0:0:0:0"));
        assertEquals("127.0.0.1", Ports.normalizeHost(""));
        assertEquals("127.0.0.1", Ports.normalizeHost(null));
        assertEquals("192.168.1.5", Ports.normalizeHost("192.168.1.5"));
    }

    // ---------------------------------------------------------------- protected

    @Test
    void protectedProcesses_areRejected() {
        long self = ProcessHandle.current().pid();
        assertNotNull(Ports.protectedReason(self));
        assertNotNull(Ports.protectedReason(1));
        assertEquals("TERMINATION_NOT_ALLOWED",
                Ports.terminateProcess(self, false).get("result"));
        assertEquals("TERMINATION_NOT_ALLOWED",
                Ports.terminateProcess(1, true).get("result"));
    }

    @Test
    void alreadyExited_returnsAlreadyExited() {
        assertEquals("PROCESS_ALREADY_EXITED",
                Ports.terminateProcess(999_999_99L, false).get("result"));
    }

    // ---------------------------------------------------------------- live process

    @Test
    void processInfo_identifiesOwner_andGracefulTerminationFreesPort() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available");
        int port = freePort();
        child = startListener(port, false);
        Map<String, Object> info = waitForOwner(port);
        assertNotNull(info, "child should be listening on " + port);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> owners = (List<Map<String, Object>>) info.get("owners");
        assertFalse(owners.isEmpty());
        Map<String, Object> o = owners.get(0);
        assertEquals(child.pid(), ((Number) o.get("pid")).longValue());
        assertFalse((Boolean) o.get("protected"));
        assertNotNull(o.get("name"));
        assertNotNull(o.get("cmd"));

        Map<String, Object> res = Ports.terminateProcess(child.pid(), false);
        assertEquals("PROCESS_TERMINATED", res.get("result"));
        assertTrue(Ports.isPortFree(port), "port should be free after graceful termination");
    }

    @Test
    void gracefulTimesOut_thenExplicitForceSucceeds() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available");
        int port = freePort();
        child = startListener(port, true); // ignores SIGTERM
        assertNotNull(waitForOwner(port));

        Map<String, Object> graceful = Ports.terminateProcess(child.pid(), false);
        assertEquals("STILL_RUNNING", graceful.get("result"),
                "a SIGTERM-ignoring child must survive graceful termination");

        Map<String, Object> forced = Ports.terminateProcess(child.pid(), true);
        assertEquals("PROCESS_TERMINATED", forced.get("result"));
        assertTrue(Ports.isPortFree(port));
    }

    @Test
    void processInfo_noOwnerWhenFree() throws Exception {
        int port = freePort();
        Map<String, Object> info = Ports.processInfo(port);
        assertEquals(0, ((Number) info.get("ownerCount")).intValue());
        assertEquals(Boolean.FALSE, info.get("listening"));
        assertTrue(Ports.isPortFree(port));
    }

    @Test
    void multipleSocketOwners_areReported() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available");
        int port = freePort();
        Process c1 = startReuseListener(port);
        Process c2 = startReuseListener(port);
        try {
            Map<String, Object> info = waitForOwners(port, 2);
            assumeTrue(info != null, "SO_REUSEPORT with two listeners not supported here");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> owners = (List<Map<String, Object>>) info.get("owners");
            assertTrue(owners.size() >= 2, "expected >= 2 owners, got " + owners.size());
            assertNotEquals(((Number) owners.get(0).get("pid")).longValue(),
                    ((Number) owners.get(1).get("pid")).longValue());
        } finally {
            c1.destroyForcibly();
            c2.destroyForcibly();
        }
    }

    @Test
    void ownershipChange_detectedWhenPidNoLongerOwns() throws Exception {
        assumeTrue(pythonAvailable(), "python3 not available");
        int port = freePort();
        child = startListener(port, false);
        assertNotNull(waitForOwner(port));
        // A wrong PID must not be accepted as the port owner.
        long wrong = child.pid() + 1;
        Map<String, Object> info = Ports.processInfo(port);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> owners = (List<Map<String, Object>>) info.get("owners");
        boolean isOwner = owners.stream()
                .anyMatch(o -> ((Number) o.get("pid")).longValue() == wrong);
        assertFalse(isOwner, "an unrelated PID must not be reported as the owner");
    }

    // ---------------------------------------------------------------- helpers

    private static boolean pythonAvailable() {
        try {
            Process p = new ProcessBuilder("python3", "-c", "print(1)").start();
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Process startListener(int port, boolean ignoreSigterm) throws IOException {
        String sig = ignoreSigterm
                ? "import signal\nsignal.signal(signal.SIGTERM, signal.SIG_IGN)\n" : "";
        String code = "import socket,time\n" + sig
                + "s=socket.socket()\n"
                + "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
                + "s.bind(('127.0.0.1'," + port + "))\n"
                + "s.listen(1)\n"
                + "time.sleep(120)\n";
        return new ProcessBuilder("python3", "-c", code).start();
    }

    private static Map<String, Object> waitForOwner(int port) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = Ports.processInfo(port);
            Object oc = info.get("ownerCount");
            if (oc instanceof Number && ((Number) oc).intValue() > 0) {
                return info;
            }
            Thread.sleep(150);
        }
        return null;
    }

    private static Process startReuseListener(int port) throws IOException {
        String code = "import socket,time\n"
                + "s=socket.socket()\n"
                + "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)\n"
                + "s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEPORT, 1)\n"
                + "s.bind(('127.0.0.1'," + port + "))\n"
                + "s.listen(1)\n"
                + "time.sleep(120)\n";
        return new ProcessBuilder("python3", "-c", code).start();
    }

    private static Map<String, Object> waitForOwners(int port, int n) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < deadline) {
            Map<String, Object> info = Ports.processInfo(port);
            Object oc = info.get("ownerCount");
            if (oc instanceof Number && ((Number) oc).intValue() >= n) {
                return info;
            }
            Thread.sleep(150);
        }
        return null;
    }
}
