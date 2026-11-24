package upm;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Intelligent reporting and export for functional test runs.
 *
 * <p>Reports keep measured execution facts (step statuses, captured output,
 * timings, evidence) strictly separate from rule-based diagnostic
 * interpretation (what failed, which integration stage, what to investigate
 * next). No LLM is used to fabricate interpretations: the "intelligence" is a
 * deterministic summary derived from the measured step results.
 */
public final class Report {

    private Report() {
    }

    // ---------------------------------------------------------- intelligence

    /** Rule-based diagnostic summary derived from a run's measured steps. */
    public static Map<String, Object> intelligence(FuncTest.Run run) {
        Map<String, Object> m = new LinkedHashMap<>();
        List<String> works = new ArrayList<>();
        List<String> fails = new ArrayList<>();
        List<String> partial = new ArrayList<>();
        List<String> blocked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        String failedStage = null;
        for (FuncTest.Step s : run.steps) {
            switch (s.status) {
                case PASS -> works.add(s.name);
                case FAIL -> fails.add(s.name);
                case PARTIAL -> partial.add(s.name);
                case BLOCKED -> blocked.add(s.name);
                case NOT_IMPLEMENTED -> missing.add(s.name);
                default -> { }
            }
            if (s.status == FuncTest.Status.FAIL || s.status == FuncTest.Status.BLOCKED
                    || s.status == FuncTest.Status.NOT_IMPLEMENTED) {
                if (failedStage == null) {
                    failedStage = stageOf(s);
                }
            }
        }
        m.put("whatWorks", works);
        m.put("whatFails", fails);
        m.put("partial", partial);
        m.put("blocked", blocked);
        m.put("missing", missing);
        m.put("failedStage", failedStage == null ? "none" : failedStage);
        m.put("next", nextActions(run, failedStage, fails, blocked, missing));
        m.put("secretsRedacted", true);
        m.put("interpretationIsRuleBased", true);
        return m;
    }

    private static String stageOf(FuncTest.Step s) {
        String n = s.name.toLowerCase(Locale.ROOT);
        if (n.contains("gui")) {
            return "UI / GUI automation";
        }
        if (n.contains("ollama") || n.contains("provider") || n.contains("inference")
                || n.contains("streaming") || n.contains("model")) {
            return "provider (LLM)";
        }
        if (n.contains("enr")) {
            return "ENR";
        }
        if (n.contains("launcher") || n.contains("built") || n.contains("build")
                || n.contains("display")) {
            return "product / build environment";
        }
        if (n.contains("start") || n.contains("invoke") || n.contains("readiness")
                || n.contains("health") || n.contains("status") || n.contains("web")
                || n.contains("operation")) {
            return "product / execution environment";
        }
        return "product";
    }

    private static List<String> nextActions(FuncTest.Run run, String stage,
            List<String> fails, List<String> blocked, List<String> missing) {
        List<String> next = new ArrayList<>();
        if (fails.isEmpty() && blocked.isEmpty() && missing.isEmpty()) {
            next.add("All executed steps passed. Re-run periodically to detect regressions.");
            return next;
        }
        if (blocked.contains("launcher") || blocked.contains("built") || !missing.isEmpty()
                && blocked.stream().anyMatch(s -> s.contains("built") || s.contains("launcher"))) {
            next.add("Run BUILD to produce the missing launcher/artifact.");
        }
        if (run.mode == FuncTest.Mode.RCP && blocked.stream().anyMatch(s -> s.contains("display"))) {
            next.add("Start a graphical session (X11/Xvfb) so the RCP workbench can launch.");
        }
        if (fails.stream().anyMatch(s -> s.contains("inference"))
                || blocked.stream().anyMatch(s -> s.contains("ollama"))) {
            next.add("Verify the LLM provider is configured, enabled and reachable, then re-run.");
        }
        if (!fails.isEmpty()) {
            next.add("Inspect the failed step logs and evidence in this report.");
        }
        if (!missing.isEmpty()) {
            next.add("The unverified functionality requires automation or an API contract that is "
                    + "not currently available.");
        }
        if (next.isEmpty()) {
            next.add("Investigate the blocked steps; they were not executed.");
        }
        return next;
    }

    // ----------------------------------------------------------------- export

    public static String json(List<FuncTest.Run> runs) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (FuncTest.Run r : runs) {
            Map<String, Object> item = r.toJson();
            item.put("diagnosticsSummary", intelligence(r));
            out.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("generatedAt", System.currentTimeMillis());
        root.put("kind", "upm-functional-test-export");
        root.put("runs", out);
        return Json.write(root);
    }

    public static String markdown(List<FuncTest.Run> runs, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "UPM Functional Test Report" : title).append("\n\n");
        sb.append("_Generated ").append(new java.util.Date()).append(" — measured results and "
                + "rule-based diagnostics. Secrets are redacted._\n\n");
        if (runs.isEmpty()) {
            sb.append("No test runs to report.\n");
            return sb.toString();
        }
        for (FuncTest.Run r : runs) {
            sb.append("## ").append(r.projectName).append(" — ").append(r.mode.label)
                    .append(" (").append(r.launchConfigId).append(")\n\n");
            sb.append("- **Status:** ").append(r.status).append("\n");
            sb.append("- **Scenario:** ").append(r.scenario).append("\n");
            sb.append("- **Duration:** ").append(r.durationMs()).append(" ms\n");
            sb.append("- **Summary:** ").append(r.summary).append("\n\n");
            sb.append("### Steps\n\n");
            sb.append("| # | Step | Status | Actual |\n");
            sb.append("|---|------|--------|--------|\n");
            for (FuncTest.Step s : r.steps) {
                sb.append("| ").append(s.seq).append(" | ").append(md(s.name)).append(" | ")
                        .append(s.status).append(" | ").append(md(s.actual)).append(" |\n");
            }
            sb.append("\n");
            for (FuncTest.Step s : r.steps) {
                if (s.error != null && !s.error.isBlank()) {
                    sb.append("#### ").append(s.seq).append(". ").append(s.name)
                            .append(" — error\n\n```\n").append(FuncTest.redact(s.error))
                            .append("\n```\n\n");
                }
                String log = s.log.toString();
                if (!log.isBlank()) {
                    sb.append("#### ").append(s.seq).append(". ").append(s.name)
                            .append(" — output\n\n```\n").append(FuncTest.redact(log))
                            .append("\n```\n\n");
                }
            }
            if (!r.diagnostics.isEmpty()) {
                sb.append("### Routing / diagnostics\n\n```\n")
                        .append(FuncTest.redact(Json.write(r.diagnostics))).append("\n```\n\n");
            }
            Map<String, Object> diag = intelligence(r);
            sb.append("### Diagnostic summary\n\n");
            sb.append("- **What works:** ").append(md(String.join(", ",
                    cast(diag.get("whatWorks"))))).append("\n");
            sb.append("- **What fails:** ").append(md(String.join(", ",
                    cast(diag.get("whatFails"))))).append("\n");
            sb.append("- **Failed stage:** ").append(md(String.valueOf(diag.get("failedStage"))))
                    .append("\n");
            sb.append("- **Missing functionality:** ").append(md(String.join(", ",
                    cast(diag.get("missing"))))).append("\n");
            sb.append("- **Investigate next:** ").append(md(String.join("; ",
                    cast(diag.get("next"))))).append("\n\n");
        }
        return sb.toString();
    }

    public static String html(List<FuncTest.Run> runs, String title) {
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"UTF-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
                .append("<title>").append(esc(title == null ? "UPM Functional Test Report" : title))
                .append("</title><style>")
                .append("body{font-family:-apple-system,Segoe UI,Roboto,sans-serif;margin:24px;"
                        + "color:#1c2733;background:#f7f9fc}")
                .append("h1,h2,h3,h4{margin:16px 0 8px}.badge{display:inline-block;padding:2px 10px;"
                        + "border-radius:10px;font-size:12px;font-weight:600;border:1px solid}")
                .append(".PASS{color:#1a7f37;border-color:#1a7f37;background:#e6f4ea}")
                .append(".FAIL{color:#c62828;border-color:#c62828;background:#fdecea}")
                .append(".PARTIAL,.BLOCKED{color:#b26a00;border-color:#b26a00;background:#fff4e5}")
                .append(".NOT_IMPLEMENTED,.SKIP,.NOT_RUN{color:#5f6b7a;border-color:#5f6b7a;"
                        + "background:#eef1f5}")
                .append("table{border-collapse:collapse;width:100%;font-size:13px}")
                .append("th,td{border:1px solid #dde3ea;padding:6px 10px;text-align:left;"
                        + "vertical-align:top}")
                .append("th{background:#eef1f5}pre{background:#10151f;color:#c8d3e8;padding:10px;"
                        + "border-radius:6px;overflow-x:auto;font-size:12px}")
                .append(".run{border:1px solid #dde3ea;border-radius:8px;padding:16px;margin:16px 0;"
                        + "background:#fff}")
                .append("</style></head><body>");
        sb.append("<h1>").append(esc(title == null ? "UPM Functional Test Report" : title))
                .append("</h1><p class=\"muted\">Generated ").append(new java.util.Date())
                .append(" — measured results and rule-based diagnostics. Secrets are redacted.</p>");
        if (runs.isEmpty()) {
            sb.append("<p>No test runs to report.</p></body></html>");
            return sb.toString();
        }
        for (FuncTest.Run r : runs) {
            sb.append("<div class=\"run\"><h2>").append(esc(r.projectName)).append(" — ")
                    .append(esc(r.mode.label)).append(" (").append(esc(r.launchConfigId))
                    .append(")</h2>");
            sb.append("<p><span class=\"badge ").append(r.status.name()).append("\">")
                    .append(r.status.name()).append("</span> ")
                    .append("<b>Duration:</b> ").append(r.durationMs()).append(" ms — ")
                    .append("<b>Summary:</b> ").append(esc(r.summary)).append("</p>");
            sb.append("<p class=\"muted\">").append(esc(r.scenario)).append("</p>");
            sb.append("<h3>Steps</h3><table><tr><th>#</th><th>Step</th><th>Status</th><th>Actual</th>"
                    + "</tr>");
            for (FuncTest.Step s : r.steps) {
                sb.append("<tr><td>").append(s.seq).append("</td><td>").append(esc(s.name))
                        .append("</td><td><span class=\"badge ").append(s.status.name())
                        .append("\">").append(s.status.name()).append("</span></td><td>")
                        .append(esc(s.actual)).append("</td></tr>");
            }
            sb.append("</table>");
            for (FuncTest.Step s : r.steps) {
                if (s.error != null && !s.error.isBlank()) {
                    sb.append("<h4>").append(s.seq).append(". ").append(esc(s.name))
                            .append(" — error</h4><pre>").append(esc(FuncTest.redact(s.error)))
                            .append("</pre>");
                }
                String log = s.log.toString();
                if (!log.isBlank()) {
                    sb.append("<h4>").append(s.seq).append(". ").append(esc(s.name))
                            .append(" — output</h4><pre>").append(esc(FuncTest.redact(log)))
                            .append("</pre>");
                }
            }
            if (!r.diagnostics.isEmpty()) {
                sb.append("<h3>Routing / diagnostics</h3><pre>")
                        .append(esc(FuncTest.redact(Json.write(r.diagnostics)))).append("</pre>");
            }
            Map<String, Object> diag = intelligence(r);
            sb.append("<h3>Diagnostic summary</h3><table>");
            sb.append("<tr><th>What works</th><td>").append(esc(String.join(", ",
                    cast(diag.get("whatWorks"))))).append("</td></tr>");
            sb.append("<tr><th>What fails</th><td>").append(esc(String.join(", ",
                    cast(diag.get("whatFails"))))).append("</td></tr>");
            sb.append("<tr><th>Failed stage</th><td>").append(esc(String.valueOf(
                    diag.get("failedStage")))).append("</td></tr>");
            sb.append("<tr><th>Missing functionality</th><td>").append(esc(String.join(", ",
                    cast(diag.get("missing"))))).append("</td></tr>");
            sb.append("<tr><th>Investigate next</th><td>").append(esc(String.join("; ",
                    cast(diag.get("next"))))).append("</td></tr>");
            sb.append("</table></div>");
        }
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Builds a ZIP evidence package containing reports, JSON and logs. */
    public static byte[] zip(List<FuncTest.Run> runs, String title) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            addZip(zos, "report.html", html(runs, title).getBytes(StandardCharsets.UTF_8));
            addZip(zos, "report.md", markdown(runs, title).getBytes(StandardCharsets.UTF_8));
            addZip(zos, "report.json", json(runs).getBytes(StandardCharsets.UTF_8));
            for (FuncTest.Run r : runs) {
                String prefix = "runs/" + r.id + "/";
                addZip(zos, prefix + "run.json",
                        Json.write(r.toJson()).getBytes(StandardCharsets.UTF_8));
                StringBuilder logs = new StringBuilder();
                for (FuncTest.Step s : r.steps) {
                    logs.append("=== step ").append(s.seq).append(": ").append(s.name)
                            .append(" (").append(s.status).append(") ===\n")
                            .append(FuncTest.redact(s.log.toString())).append("\n");
                    if (s.error != null && !s.error.isBlank()) {
                        logs.append("--- error ---\n").append(FuncTest.redact(s.error)).append("\n");
                    }
                }
                addZip(zos, prefix + "evidence.log",
                        logs.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
        return bos.toByteArray();
    }

    private static void addZip(ZipOutputStream zos, String name, byte[] data) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(data);
        zos.closeEntry();
    }

    @SuppressWarnings("unchecked")
    private static List<String> cast(Object o) {
        if (o instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object e : l) {
                out.add(String.valueOf(e));
            }
            return out;
        }
        return List.of();
    }

    private static String md(String s) {
        return s == null ? "" : s.replace("|", "\\|").replace("\n", " ");
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    /** Writes an export to a file (used by tests); returns the path. */
    public static Path writeTo(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }
}
