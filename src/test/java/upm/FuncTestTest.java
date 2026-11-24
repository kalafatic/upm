package upm;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Unit tests for the instance-level functional test engine and reporting. */
class FuncTestTest {

    private static Config.Project project(String id, String name, String type,
            boolean enrMonitor, List<Config.LaunchConfig> lcs) {
        return new Config.Project(id, name, "", "/tmp/" + id, "", type, "USER_CONFIRMED",
                "test", List.of(), Map.of(), List.of(), List.of(), List.of(), List.of(),
                enrMonitor ? "http://127.0.0.1:8111/api/v1" : "", enrMonitor, List.of(),
                Map.of(), List.of(), false, lcs);
    }

    private static Config.LaunchConfig headless(String id, boolean httpReadiness) {
        Config.Readiness r = httpReadiness ? new Config.Readiness("http", "127.0.0.1", "fixed",
                48080, "", "", "/health", "200") : null;
        return new Config.LaunchConfig(id, id, "eclipse-product", "bin/" + id, List.of(),
                Map.of(), "repo", 48080, true, false, r, List.of(), null, 20, 180_000L, true);
    }

    private static Config.LaunchConfig rcp(String id) {
        return new Config.LaunchConfig(id, id, "eclipse-product", "bin/" + id, List.of(),
                Map.of(), "repo", 0, false, true, null, List.of(), null, 20, 180_000L, false);
    }

    @Test
    void deriveModes_rcpOnly() {
        assertEquals(List.of(FuncTest.Mode.RCP), FuncTest.deriveModes(rcp("rcp")));
    }

    @Test
    void deriveModes_headlessWithHttp_yieldsHeadlessAndWeb() {
        List<FuncTest.Mode> modes = FuncTest.deriveModes(headless("headless", true));
        assertEquals(List.of(FuncTest.Mode.HEADLESS, FuncTest.Mode.WEB), modes);
    }

    @Test
    void deriveModes_headlessNoHttp_yieldsHeadlessOnly() {
        assertEquals(List.of(FuncTest.Mode.HEADLESS), FuncTest.deriveModes(headless("cli", false)));
    }

    @Test
    void instances_mapsProductInstanceMode() {
        Config cfg = Config.create(Path.of("/tmp/ws"), "ws", "WS", "");
        cfg.projects.add(project("DCode", "DCode", "eclipse-tycho", false,
                List.of(headless("headless", true), rcp("rcp"))));
        cfg.projects.add(project("ENR", "ENR", "maven", true, List.of(headless("run", true))));

        List<FuncTest.Instance> insts = FuncTest.instances(cfg);
        // DCode: headless->HEADLESS, headless->WEB, rcp->RCP ; ENR: run->HEADLESS, run->WEB.
        assertEquals(5, insts.size());
        assertTrue(insts.stream().anyMatch(i -> i.projectId.equals("DCode")
                && i.launchConfigId.equals("headless") && i.mode == FuncTest.Mode.WEB));
        assertTrue(insts.stream().anyMatch(i -> i.projectId.equals("DCode")
                && i.launchConfigId.equals("rcp") && i.mode == FuncTest.Mode.RCP));
        assertTrue(insts.stream().anyMatch(i -> i.projectId.equals("ENR")
                && i.mode == FuncTest.Mode.WEB));
    }

    @Test
    void classify_recognizesProductFamilies() {
        assertEquals("evocode", FuncTest.classify(project("DCode", "DCode", "eclipse-tycho",
                false, List.of())));
        assertEquals("evostudio", FuncTest.classify(project("DStudio", "DStudio", "eclipse-tycho",
                false, List.of())));
        assertEquals("tryst", FuncTest.classify(project("TRYST", "TRYST", "eclipse-tycho",
                false, List.of())));
        assertEquals("enr", FuncTest.classify(project("ENR", "ENR", "maven", true, List.of())));
        assertEquals("evo", FuncTest.classify(project("EVO", "EVO", "eclipse-tycho",
                false, List.of())));
        assertEquals("generic", FuncTest.classify(project("other", "Other", "generic",
                false, List.of())));
    }

    @Test
    void redaction_hidesSecrets() {
        String s = "Authorization: Bearer abc123.def and api_key=sk-secret and password= hunter2 "
                + "and {\"api_key\": \"sk-json-secret\", \"token\":\"t-1\"}";
        String r = FuncTest.redact(s);
        assertFalse(r.contains("abc123.def"));
        assertFalse(r.contains("sk-secret"));
        assertFalse(r.contains("hunter2"));
        assertFalse(r.contains("sk-json-secret"));
        assertFalse(r.contains("t-1"));
        assertTrue(r.contains("REDACTED"));
    }

    private FuncTest.Run sampleRun() {
        FuncTest.Run run = new FuncTest.Run(42L, "ENR", "ENR", "run", FuncTest.Mode.WEB,
                "ENR web LLM mediation test");
        run.step("health", "Verify ENR health", "healthy").start()
                .pass("HTTP 200 — healthy");
        run.step("inference", "Submit a real inference request", "non-empty answer").start()
                .fail("empty assistant content", "model generated no content");
        run.step("ollama-leg", "Verify the Ollama mediation leg", "routed to Ollama").start()
                .blocked("Ollama not configured", "configure Ollama provider");
        run.finish(FuncTest.Status.FAIL, "1 passed, 1 failed, 1 blocked");
        return run;
    }

    @Test
    void intelligence_derivesRuleBasedSummary() {
        Map<String, Object> diag = Report.intelligence(sampleRun());
        assertEquals("provider (LLM)", diag.get("failedStage"));
        assertTrue(((List<?>) diag.get("whatWorks")).contains("health"));
        assertTrue(((List<?>) diag.get("whatFails")).contains("inference"));
        assertEquals(Boolean.TRUE, diag.get("secretsRedacted"));
    }

    @Test
    void report_markdownAndHtmlAndJsonAndZip_areProduced() throws Exception {
        List<FuncTest.Run> runs = List.of(sampleRun());
        String md = Report.markdown(runs, "Test Report");
        assertTrue(md.contains("ENR"));
        assertTrue(md.contains("inference"));
        String html = Report.html(runs, "Test Report");
        assertTrue(html.contains("<!DOCTYPE html>"));
        assertTrue(html.contains("ENR"));
        String json = Report.json(runs);
        assertTrue(json.contains("\"runs\""));
        assertTrue(json.contains("\"status\":\"FAIL\""));
        byte[] zip = Report.zip(runs, "Test Report");
        assertTrue(zip.length > 0);
    }

    @Test
    void run_statusWorstCombinesSteps() {
        FuncTest.Run run = new FuncTest.Run(7L, "P", "P", "lc", FuncTest.Mode.WEB, "s");
        run.step("a", "", "").pass("ok");
        run.step("b", "", "").partial("half", "partial");
        run.step("c", "", "").blocked("blocked", "reason");
        FuncTest.Status worst = FuncTest.Status.PASS;
        for (FuncTest.Step s : run.steps) {
            worst = FuncTest.Status.worst(worst, s.status);
        }
        assertEquals(FuncTest.Status.BLOCKED, worst);
    }

    // --------------------------------------------------- ENR version (backward-compat)

    @Test
    void versionStep_supported_passes() {
        assertEquals(FuncTest.Status.PASS,
                FuncTest.versionStepStatus(new FuncTest.ExecResult(0, "ENR 2.6.11\n", "")));
    }

    @Test
    void versionStep_unsupported_skips() {
        for (String out : List.of(
                "Unknown command: version\n",
                "Unknown subcommand: version\n",
                "Unrecognized command 'version'\n",
                "Unrecognized subcommand 'version'\n",
                "Unsupported command: version\n",
                "Unsupported subcommand 'version'\n",
                "No such command: version\n")) {
            assertEquals(FuncTest.Status.SKIP,
                    FuncTest.versionStepStatus(new FuncTest.ExecResult(1, out, "")), out);
        }
    }

    @Test
    void versionStep_unrelatedFailure_isNotSkip() {
        // Missing/inaccessible executable or Java runtime failure → FAIL, not SKIP.
        assertEquals(FuncTest.Status.FAIL,
                FuncTest.versionStepStatus(new FuncTest.ExecResult(1,
                        "Error: Unable to access jarfile /x/enr-cli.jar\n", "")));
        assertEquals(FuncTest.Status.FAIL,
                FuncTest.versionStepStatus(new FuncTest.ExecResult(1,
                        "Error: Could not find or load main class ai.enr.cli.EnrCli\n", "")));
        // Timeout and process-start failures carry exit -1 with an error message.
        assertEquals(FuncTest.Status.FAIL,
                FuncTest.versionStepStatus(new FuncTest.ExecResult(-1, "", "Timed out after 30000 ms")));
        assertEquals(FuncTest.Status.FAIL,
                FuncTest.versionStepStatus(new FuncTest.ExecResult(-1, "", "Failed to start: Permission denied")));
    }

    @Test
    void unsupportedVersion_doesNotTurnSuccessfulStepsIntoFail() {
        FuncTest.Run run = new FuncTest.Run(8L, "ENR", "ENR", "run", FuncTest.Mode.HEADLESS, "s");
        run.step("version", "", "").skip(FuncTest.UNSUPPORTED_VERSION_MSG, "");
        run.step("status", "", "").pass("running");
        run.step("infer", "", "").pass("PONG");
        FuncTest.Status worst = FuncTest.Status.PASS;
        for (FuncTest.Step s : run.steps) {
            worst = FuncTest.Status.worst(worst, s.status);
        }
        assertNotEquals(FuncTest.Status.FAIL, worst);
        // Execution continued past the skipped version step: status and infer passed.
        assertEquals(FuncTest.Status.PASS, run.steps.get(1).status);
        assertEquals(FuncTest.Status.PASS, run.steps.get(2).status);
    }

    @Test
    void isUnsupportedSubcommand_isCaseInsensitiveAndExplicit() {
        assertTrue(FuncTest.isUnsupportedSubcommand("Unknown Command: version"));
        assertTrue(FuncTest.isUnsupportedSubcommand("Unrecognized subcommand 'version'"));
        assertTrue(FuncTest.isUnsupportedSubcommand("Unsupported command"));
        assertFalse(FuncTest.isUnsupportedSubcommand("Error: Unable to access jarfile"));
        assertFalse(FuncTest.isUnsupportedSubcommand(""));
        assertFalse(FuncTest.isUnsupportedSubcommand(null));
        // A bare usage/help listing is NOT explicit unsupported evidence.
        assertFalse(FuncTest.isUnsupportedSubcommand("Usage: enr <command> ..."));
    }
}
