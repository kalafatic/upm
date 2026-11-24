/* UPM — frontend (vanilla JS, no frameworks) */
"use strict";

const $ = (sel) => document.querySelector(sel);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

let state = {
  app: {},
  workspace: { id: "", name: "" },
  projects: [],
  profiles: [],
  workspaces: [],
  ops: { active: [], history: [] },
  system: null,
  notifications: [],
  enr: null,
  dashboard: null,
  settings: {},
};
let selectedProject = null;
let selectedTab = "overview";
let selectedSettingsTab = "general";
let selectedOp = null;
let layoutEditing = false;
let draftLayout = null;
let activeView = "dashboard";
let lastKnownNotifs = 0;

// ------------------------------------------------------------------ api

async function api(path, opts) {
  const r = await fetch(path, opts);
  const isJson = (r.headers.get("content-type") || "").includes("json");
  const body = isJson ? await r.json().catch(() => ({})) : await r.blob().catch(() => null);
  if (!r.ok) {
    throw new Error((body && body.error) || "HTTP " + r.status);
  }
  return body;
}

async function startOp(type, project, mode, port, extra) {
  const body = { type, project };
  if (mode) body.launchConfig = mode;
  if (port) body.port = port;
  if (extra) Object.assign(body, extra);
  return api("/api/ops", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body),
  });
}

function fmtDuration(ms) {
  if (ms == null || ms <= 0) return "—";
  const s = Math.floor(ms / 1000);
  if (s < 60) return s + "s";
  const m = Math.floor(s / 60);
  if (m < 60) return m + "m " + (s % 60) + "s";
  return Math.floor(m / 60) + "h " + (m % 60) + "m";
}
function fmtTime(ms) { return !ms || ms <= 0 ? "—" : new Date(ms).toLocaleTimeString(); }
function stateBadge(s) { return '<span class="badge st-' + (s || "UNKNOWN") + '">' + (s || "UNKNOWN") + "</span>"; }
function mb(bytes) { return bytes == null || bytes < 0 ? "—" : (bytes / 1048576).toFixed(1) + " MB"; }
function esc(s) {
  return String(s == null ? "" : s).replace(/[&<>"']/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}

function project(id) { return state.projects.find((p) => p.id === id); }
function activeOp(projectId) {
  return (state.ops.active || []).find((o) => o.project === projectId);
}

// ------------------------------------------------------------------ sse

function connectSse() {
  const es = new EventSource("/api/events");
  es.addEventListener("snapshot", (e) => applySnapshot(JSON.parse(e.data)));
  es.addEventListener("op", (e) => upsertOp(JSON.parse(e.data)));
  es.addEventListener("oplog", (e) => {
    const d = JSON.parse(e.data);
    if (selectedOp && selectedOp.id === d.id) {
      const logEl = $("#op-live-log");
      if (logEl) {
        logEl.textContent += d.chunk;
        logEl.scrollTop = logEl.scrollHeight;
      }
    }
  });
  es.addEventListener("projects", (e) => {
    state.projects = JSON.parse(e.data);
    renderIfVisible();
  });
  es.addEventListener("system", (e) => { state.system = JSON.parse(e.data); });
  es.addEventListener("notifications", (e) => {
    state.notifications = JSON.parse(e.data);
    toastNotifications();
    renderNotifs();
  });
  es.addEventListener("metrics", (e) => {
    const m = JSON.parse(e.data);
    onMetricsSample(m);
  });
}

function applySnapshot(s) {
  state = Object.assign(state, s);
  if (!state.dashboard || !state.dashboard.widgets) {
    state.dashboard = { columns: 3, widgets: [] };
  }
  draftLayout = null;
  document.documentElement.dataset.theme = (state.app && state.app.theme) || "light";
  $("#ws-name").textContent = state.workspace ? state.workspace.name : "";
  lastKnownNotifs = (state.notifications || []).length;
  renderAll();
}

function upsertOp(op) {
  const act = state.ops.active;
  for (let i = 0; i < act.length; i++) {
    if (act[i].id === op.id) {
      if (op.status === "RUNNING" || op.status === "QUEUED") act[i] = op;
      else act.splice(i, 1);
      return mergedHistory(op);
    }
  }
  if (op.status === "RUNNING" || op.status === "QUEUED") act.push(op);
  mergedHistory(op);

  function mergedHistory(o) {
    let inHist = false;
    for (let i = 0; i < state.ops.history.length; i++) {
      if (state.ops.history[i].id === o.id) {
        state.ops.history[i] = o;
        inHist = true;
        break;
      }
    }
    if (!inHist && o.endMs > 0) {
      state.ops.history.unshift(o);
      state.ops.history = state.ops.history.slice(0, 200);
    }
  }
}

// ------------------------------------------------------------------ views

function switchView(name) {
  activeView = name;
  document.querySelectorAll(".view").forEach((v) => v.classList.remove("active"));
  $("#view-" + name).classList.add("active");
  document.querySelectorAll(".view-btn").forEach((b) =>
    b.classList.toggle("active", b.dataset.view === name));
  renderIfVisible();
}

function renderIfVisible() {
  switch (activeView) {
    case "dashboard": renderDashboard(); break;
    case "projects": renderProjects(); break;
    case "architecture": renderArchitecture(); break;
    case "monitoring": renderMonitoring(); break;
    case "ports": renderPorts(); break;
    case "workspaces": renderWorkspaces(); break;
    case "operations": renderOperations(); break;
    case "settings": renderSettings(); break;
  }
}

function renderAll() {
  renderDashboard();
  renderProjects();
  renderArchitecture();
  renderMonitoring();
  renderWorkspaces();
  renderOperations();
  renderSettings();
  renderNotifs();
}

// ------------------------------------------------------------------ dashboard

function renderDashboard() {
  $("#project-count").textContent = "(" + state.projects.length + ")";
  renderProjectTable();
  const grid = $("#dashboard-grid");
  const layout = (draftLayout || state.dashboard || { columns: 3, widgets: [] });
  grid.style.gridTemplateColumns = "repeat(" + (layout.columns || 3) + ", 1fr)";
  grid.classList.toggle("edit-mode", layoutEditing);
  if (!layoutEditing) {
    grid.innerHTML = "";
    for (const w of (layout.widgets || []).filter((x) => x.visible)) {
      const div = el("div", "widget");
      div.dataset.type = w.type;
      div.style.gridColumn = "span " + (w.w || 1);
      div.style.gridRow = "span " + (w.h || 1);
      div.appendChild(renderWidget(w.type));
      grid.appendChild(div);
    }
  } else {
    renderEditableGrid(grid, layout);
  }
}

function renderProjectTable() {
  const box = $("#project-table");
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Project</th><th>Type</th><th>Git</th><th>Build</th>" +
    "<th>Tests</th><th>Runtime</th><th>Last operation</th><th>Warnings</th><th></th></tr></thead>";
  const tb = el("tbody");
  for (const p of state.projects) {
    const tr = el("tr", "clickable");
    const g = p.git || {};
    const gitCell = !g.isRepo ? '<span class="muted">—</span>'
      : '<span class="mono">' + esc(g.branch || "?") + "</span>" +
        (g.dirty ? ' <span class="warn-text" title="uncommitted changes">●</span>' : ' <span class="ok-text" title="clean">●</span>');
    const lastBuild = p.lastBuild;
    const lastTest = p.lastTest;
    const buildCell = !lastBuild ? '<span class="muted">—</span>'
      : lastBuild.status === "SUCCEEDED" ? '<span class="ok-text">ok</span>'
        : '<span class="err-text">failed</span>';
    const testCell = !lastTest ? '<span class="muted">—</span>'
      : lastTest.status === "SUCCEEDED" ? '<span class="ok-text">passed</span>'
        : '<span class="err-text">failed</span>';
    const cur = p.currentOp;
    const lastOpCell = cur ? '<span class="muted">' + esc(cur.type) + ": " + esc(cur.phase) + "</span>"
      : '<span class="muted">—</span>';
    const warnings = (p.warnings || []).concat(
      !p.lastError || p.lastError === "" ? [] : ["runtime: " + p.lastError.slice(0, 60)]);
    tr.innerHTML =
      "<td><div class='proj-name'>" + esc(p.name) + "</div>" +
      "<div class='proj-sub'>" + esc(p.id) + " · " + esc(p.repoDir) + "</div></td>" +
      "<td><span class='tag'>" + esc(p.type) + "</span><span class='tag'>" + esc(p.detectionConfidence) + "</span></td>" +
      "<td>" + gitCell + "</td>" +
      "<td>" + buildCell + "</td>" +
      "<td>" + testCell + "</td>" +
      "<td>" + stateBadge(p.runtimeState) +
        (p.pid > 0 ? ' <span class="mono muted">pid ' + p.pid + "</span>" : "") + "</td>" +
      "<td>" + lastOpCell + "</td>" +
      "<td>" + (warnings.length ? '<span class="warn-text" title="' + esc(warnings.join("\n")) + '">⚠ ' + warnings.length + "</span>" : '<span class="muted">—</span>') + "</td>" +
      '<td><button class="btn btn-sm" data-open-project="' + p.id + '">Open</button> ' +
      '<button class="btn btn-sm" data-act="build" data-p="' + p.id + '">Build</button> ' +
      '<button class="btn btn-sm" data-act="run" data-p="' + p.id + '">Run</button> ' +
      '<button class="btn btn-sm" data-act="ftest" data-p="' + p.id + '">Test</button> ' +
      (p.runtimeState !== "STOPPED" && p.runtimeState !== "FAILED"
        ? '<button class="btn btn-sm btn-danger" data-act="stop" data-p="' + p.id + '">Stop</button>'
        : "") + "</td>";
    tr.dataset.openProject = p.id;
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  box.innerHTML = "";
  if (!state.projects.length) {
    box.appendChild(el("div", "empty",
      "No projects registered yet. Use “Add Project” in the Projects view."));
  } else {
    box.appendChild(t);
  }
}

function renderWidget(type) {
  const wrap = document.createElement("div");
  try {
    switch (type) {
      case "project-status": wrap.appendChild(projectStatusWidget()); break;
      case "build-progress": wrap.appendChild(buildProgressWidget()); break;
      case "test-summary": wrap.appendChild(testSummaryWidget()); break;
      case "runtime-status": wrap.appendChild(runtimeStatusWidget()); break;
      case "git-overview": wrap.appendChild(gitOverviewWidget()); break;
      case "cpu": wrap.appendChild(metricWidget("CPU", "cpu")); break;
      case "memory": wrap.appendChild(memoryWidget()); break;
      case "gpu": wrap.appendChild(gpuWidget(false)); break;
      case "vram": wrap.appendChild(gpuWidget(true)); break;
      case "active-ops": wrap.appendChild(activeOpsWidget()); break;
      case "recent-ops": wrap.appendChild(recentOpsWidget()); break;
      case "log-viewer": wrap.appendChild(logViewerWidget()); break;
      case "enr-models": wrap.appendChild(enrModelsWidget()); break;
      case "enr-inference": wrap.appendChild(enrInferenceWidget()); break;
      case "ports": wrap.appendChild(portsWidget()); break;
      case "workspace-health": wrap.appendChild(healthWidget()); break;
      default: wrap.appendChild(el("div", "muted", "Unknown widget: " + type));
    }
  } catch (e) {
    wrap.appendChild(el("div", "err-text", "Widget error: " + e.message));
  }
  return wrap;
}

function projectStatusWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Project status</h3>";
  const list = el("div", "card-list");
  for (const p of state.projects) {
    const row = el("div");
    row.style.cursor = "pointer";
    row.dataset.openProject = p.id;
    row.innerHTML = "<b>" + esc(p.name) + "</b> " + stateBadge(p.runtimeState) +
      (p.pid > 0 ? ' <span class="mono muted">' + p.pid + "</span>" : "");
    list.appendChild(row);
  }
  d.appendChild(list);
  return d;
}

function buildProgressWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Build progress</h3>";
  const builds = (state.ops.active || []).filter((o) =>
    ["build", "cleanBuild", "update", "package", "verify", "install", "buildProduct",
      "buildFeatures", "buildPlugins", "exportProduct", "exportLinux", "exportWindows",
      "exportMacosx", "exportAll", "buildP2Repository", "publishUpdateSite"].includes(o.type));
  if (!builds.length) {
    d.appendChild(el("div", "muted", "No builds running."));
    return d;
  }
  for (const op of builds) {
    const known = op.progressKnown && op.totalModules > 0;
    const pct = known ? Math.min(100, Math.round(100 * op.completedModules / op.totalModules)) : null;
    const row = document.createElement("div");
    row.style.marginBottom = "10px";
    const prog = el("div", "progress" + (known ? "" : " indeterminate"));
    const fill = el("div", "fill");
    if (known) fill.style.width = pct + "%";
    prog.appendChild(fill);
    row.appendChild(el("div", "", "<b>" + esc(op.project) + "</b> — " + esc(op.type) + " · " + esc(op.phase)));
    row.appendChild(el("div", "muted", op.currentModule ? "module: " + esc(op.currentModule) : esc(op.task || "")));
    if (known) row.appendChild(el("div", "muted", op.completedModules + "/" + op.totalModules + " modules (" + pct + "%)"));
    row.appendChild(prog);
    d.appendChild(row);
  }
  return d;
}

function testSummaryWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Test summary</h3>";
  const list = el("div", "card-list");
  let any = false;
  for (const p of state.projects) {
    const cur = activeOp(p.id);
    const last = p.lastTest;
    if (cur && cur.type === "test") {
      any = true;
      const row = el("div");
      row.innerHTML = "<b>" + esc(p.name) + "</b> — " + esc(cur.suite || "tests") +
        " <span class='mono'>" + (cur.testsRun >= 0 ? cur.testsRun + " run, " + cur.testsFailed + " failed" : "running…") + "</span>";
      list.appendChild(row);
    } else if (last) {
      any = true;
      const row = el("div");
      row.innerHTML = "<b>" + esc(p.name) + "</b> — " +
        (last.status === "SUCCEEDED" ? '<span class="ok-text">' + esc(last.summary || "passed") + "</span>"
          : '<span class="err-text">' + esc(last.summary || last.status) + "</span>");
      list.appendChild(row);
    }
  }
  if (!any) list.appendChild(el("div", "muted", "No tests run yet."));
  d.appendChild(list);
  return d;
}

function runtimeStatusWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Runtime status</h3>";
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Project</th><th>State</th><th>Launch</th><th>Uptime</th></tr></thead>";
  const tb = el("tbody");
  for (const p of state.projects) {
    const tr = el("tr");
    tr.innerHTML = "<td><b>" + esc(p.name) + "</b></td><td>" + stateBadge(p.runtimeState) + "</td>" +
      "<td class='mono'>" + esc(p.launchConfig || "—") + "</td><td>" + fmtDuration(p.uptimeMs) + "</td>";
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  d.appendChild(t);
  return d;
}

function gitOverviewWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Git overview</h3>";
  const list = el("div", "card-list");
  for (const p of state.projects) {
    const g = p.git || {};
    if (!g.isRepo) continue;
    const row = el("div");
    row.innerHTML = "<b>" + esc(p.id) + "</b> <span class='mono muted'>" + esc(g.branch || "?") + "</span>" +
      (g.dirty ? ' <span class="warn-text">' + (g.modifiedCount + g.stagedCount + g.untrackedCount) + " changes</span>"
        : ' <span class="ok-text">clean</span>');
    list.appendChild(row);
  }
  if (!list.children.length) list.appendChild(el("div", "muted", "No git repositories registered."));
  d.appendChild(list);
  return d;
}

function metricWidget(title, key) {
  const d = document.createElement("div");
  d.innerHTML = "<h3>" + title + "</h3>";
  const cpu = state.system && state.system.cpu;
  const v = cpu ? cpu.percent : null;
  const content = document.createElement("div");
  content.style.textAlign = "center";
  content.style.padding = "8px 0";
  const big = el("div", "", v == null ? "N/A" : v + "%");
  big.style.fontSize = "28px";
  big.style.fontFamily = "var(--mono)";
  content.appendChild(big);
  if (cpu && cpu.load1 != null) {
    content.appendChild(el("div", "muted", "load " + cpu.load1));
  }
  d.appendChild(content);
  return d;
}

function memoryWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Memory</h3>";
  const mem = state.system && state.system.memory;
  const content = el("div");
  if (mem && mem.percent != null) {
    const prog = el("div", "progress");
    const fill = el("div", "fill");
    fill.style.width = Math.min(100, mem.percent) + "%";
    prog.appendChild(fill);
    content.appendChild(prog);
    content.appendChild(el("div", "muted", "RAM " + mem.percent + "% · " +
      (mem.usedKb / 1048576).toFixed(1) + " / " + (mem.totalKb / 1048576).toFixed(1) + " GB"));
    content.appendChild(el("div", "muted", "JVM " + mem.jvmUsedMb + " MB"));
  } else {
    content.appendChild(el("div", "muted", "N/A"));
  }
  d.appendChild(content);
  return d;
}

function gpuWidget(vramOnly) {
  const d = document.createElement("div");
  d.innerHTML = "<h3>" + (vramOnly ? "VRAM usage" : "GPU utilization") + "</h3>";
  const gpus = (state.system && state.system.gpu) || [];
  if (!gpus.length) {
    d.appendChild(el("div", "muted", "No GPU detected (nvidia-smi unavailable)"));
    return d;
  }
  for (const g of gpus) {
    const content = el("div");
    if (vramOnly) {
      const memPct = g.memTotalMb > 0 ? Math.round(100 * g.memUsedMb / g.memTotalMb) : null;
      if (memPct != null) {
        const prog = el("div", "progress");
        const fill = el("div", "fill");
        fill.style.width = memPct + "%";
        prog.appendChild(fill);
        content.appendChild(prog);
        content.appendChild(el("div", "muted", g.memUsedMb + " / " + g.memTotalMb + " MB (" + memPct + "%)"));
      }
    } else {
      const big = el("div", "", g.utilPercent + "%");
      big.style.fontSize = "24px";
      big.style.fontFamily = "var(--mono)";
      content.appendChild(big);
      content.appendChild(el("div", "muted", esc(g.name)));
    }
    d.appendChild(content);
  }
  return d;
}

function activeOpsWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Active operations</h3>";
  if (!state.ops.active.length) {
    d.appendChild(el("div", "muted", "No operations running."));
    return d;
  }
  for (const op of state.ops.active) {
    const row = el("div", "op-item");
    const known = op.progressKnown && op.totalModules > 0;
    row.innerHTML = "<div class='row1'><b>" + esc(op.project || "workspace") + "</b>" +
      '<span class="muted">' + esc(op.type) + "</span>" + stateBadge(op.status) +
      '<span class="muted mono">' + fmtDuration(Date.now() - op.startMs) + "</span>" +
      '<span class="spacer"></span>' +
      "<button class='btn btn-sm' data-open-op='" + op.id + "'>Open</button>" +
      "<button class='btn btn-sm' data-cancel-op='" + op.id + "'>Cancel</button></div>" +
      "<div class='row2'>" + esc(op.phase) +
      (known ? " · " + op.completedModules + "/" + op.totalModules : "") + "</div>";
    const prog = el("div", "progress" + (known ? "" : " indeterminate"));
    const fill = el("div", "fill");
    if (known) fill.style.width = Math.min(100, Math.round(100 * op.completedModules / op.totalModules)) + "%";
    prog.appendChild(fill);
    row.appendChild(prog);
    d.appendChild(row);
  }
  return d;
}

function recentOpsWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Recent operations</h3>";
  const list = el("div", "card-list");
  const hist = (state.ops.history || []).slice(0, 8);
  if (!hist.length) list.appendChild(el("div", "muted", "No completed operations."));
  for (const op of hist) {
    const row = el("div");
    row.style.cursor = "pointer";
    row.dataset.openOp = op.id;
    row.innerHTML = "<b>" + esc(op.project || "workspace") + "</b> " + esc(op.type) + " — " +
      stateBadge(op.status) + ' <span class="muted">' +
      fmtDuration(op.endMs - op.startMs) + "</span>";
    list.appendChild(row);
  }
  d.appendChild(list);
  return d;
}

function logViewerWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Live log</h3>";
  const act = (state.ops.active || []).slice(0, 5);
  if (!act.length) {
    d.appendChild(el("div", "muted", "No running operations. Recent logs are in the Operations view."));
    return d;
  }
  const sel = el("select");
  for (const op of act) {
    const o = el("option");
    o.value = op.id;
    o.textContent = (op.project || "workspace") + " — " + op.type;
    sel.appendChild(o);
  }
  d.appendChild(sel);
  const logEl = el("div", "log-view small");
  d.appendChild(logEl);
  sel.onchange = () => refreshLogViewer(logEl, Number(sel.value));
  refreshLogViewer(logEl, act[0].id);
  return d;
}

async function refreshLogViewer(logEl, opId) {
  try {
    const r = await api("/api/ops/" + opId + "/log");
    logEl.textContent = r.log;
    logEl.scrollTop = logEl.scrollHeight;
  } catch (e) {
    logEl.textContent = "Log unavailable: " + e.message;
  }
}

function enrModelsWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>ENR model inventory</h3>";
  const modelsData = state.enr && state.enr.models;
  const loaded = (modelsData && modelsData.loaded && modelsData.loaded.models) || [];
  const files = (modelsData && modelsData.files) || [];
  const list = el("div", "card-list");
  if (loaded.length) {
    for (const md of loaded) {
      const row = el("div");
      row.innerHTML = "<b>" + esc(md.id) + "</b> — " + stateBadge(String(md.state).toLowerCase()) +
        ' <span class="muted mono">' + esc(md.backend || "") + " · " + (md.parameters || "?") + " params</span>";
      list.appendChild(row);
    }
  } else {
    list.appendChild(el("div", "muted", "No models loaded."));
  }
  if (files.length) {
    list.appendChild(el("div", "muted", "Model files (" + files.length + "):"));
    for (const f of files) {
      const row = el("div");
      row.innerHTML = '<span class="mono">' + esc(f.name) + "</span> <span class='muted'>" + mb(f.sizeBytes) + "</span>";
      list.appendChild(row);
    }
  }
  d.appendChild(list);
  return d;
}

function enrInferenceWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>ENR inference activity</h3>";
  const metrics = state.enr && state.enr.inference && state.enr.inference.metrics;
  if (!metrics || metrics.error) {
    d.appendChild(el("div", "muted", "ENR not reachable or no metric data."));
    return d;
  }
  const kv = el("div", "kv");
  for (const [k, v] of [["Requests", metrics.total_requests], ["Generated tokens", metrics.total_generated_tokens],
    ["Tokens/s", metrics.tokens_per_second], ["Failed", metrics.failed_requests]]) {
    kv.appendChild(el("div", "k", k));
    kv.appendChild(el("div", "v mono", v == null ? "—" : String(v)));
  }
  d.appendChild(kv);
  return d;
}

function portsWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Port status</h3>";
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Project</th><th>Launch</th><th>Port</th><th></th></tr></thead>";
  const tb = el("tbody");
  for (const p of state.projects) {
    for (const lc of p.launchConfigs || []) {
      if (!lc.port && lc.readiness && lc.readiness.port) lc.port = lc.readiness.port;
      if (!lc.port) continue;
      const open = lc._open;
      const tr = el("tr");
      tr.innerHTML = "<td>" + esc(p.id) + "</td><td class='mono'>" + esc(lc.id) + "</td>" +
        "<td class='mono'>" + lc.port + "</td><td>" +
        (open ? '<span class="warn-text">in use</span>' : '<span class="ok-text">free</span>') + "</td>";
      tb.appendChild(tr);
    }
  }
  t.appendChild(tb);
  d.appendChild(t);
  return d;
}

function healthWidget() {
  const d = document.createElement("div");
  d.innerHTML = "<h3>Workspace health</h3>";
  const list = el("div", "card-list");
  for (const p of state.projects) {
    const issues = [];
    if (p.runtimeState === "FAILED") issues.push("runtime FAILED");
    if (p.lastBuild && p.lastBuild.status === "FAILED") issues.push("last build failed");
    const row = el("div");
    row.innerHTML = "<b>" + esc(p.name) + "</b> — " + (issues.length
      ? '<span class="err-text">' + esc(issues.join(", ")) + "</span>"
      : '<span class="ok-text">OK</span>');
    list.appendChild(row);
  }
  d.appendChild(list);
  const btn = el("button", "btn btn-sm", "Run diagnostics");
  btn.onclick = () => openDiagnostics();
  d.appendChild(btn);
  return d;
}

// ------------------------------------------------------------- designer

const PALETTE = [
  ["project-status", "Project status"], ["build-progress", "Build progress"],
  ["test-summary", "Test summary"], ["runtime-status", "Runtime status"],
  ["git-overview", "Git overview"], ["cpu", "CPU"], ["memory", "Memory"],
  ["gpu", "GPU"], ["vram", "VRAM"], ["active-ops", "Active ops"],
  ["recent-ops", "Recent ops"], ["log-viewer", "Log viewer"],
  ["enr-models", "ENR models"], ["enr-inference", "ENR inference"],
  ["ports", "Port status"], ["workspace-health", "Health"],
];

function renderEditableGrid(grid, layout) {
  grid.innerHTML = "";
  const widgets = layout.widgets || [];
  widgets.forEach((w, idx) => {
    const div = el("div", "widget");
    div.dataset.idx = idx;
    div.style.gridColumn = "span " + (w.w || 1);
    div.style.gridRow = "span " + (w.h || 1);
    div.style.opacity = w.visible ? 1 : 0.4;
    div.appendChild(renderWidget(w.type));
    const tools = el("div", "widget-tools");
    const vis = el("button", "", w.visible ? "👁" : "🚫");
    vis.title = "Toggle visibility";
    vis.onclick = (e) => {
      e.stopPropagation();
      layout.widgets[idx].visible = !layout.widgets[idx].visible;
      renderEditableGrid(grid, layout);
    };
    const up = el("button", "", "↑");
    up.title = "Move left";
    up.onclick = (e) => {
      e.stopPropagation();
      if (idx > 0) {
        const [x] = layout.widgets.splice(idx, 1);
        layout.widgets.splice(idx - 1, 0, x);
        renderEditableGrid(grid, layout);
      }
    };
    const down = el("button", "", "↓");
    down.title = "Move right";
    down.onclick = (e) => {
      e.stopPropagation();
      if (idx < widgets.length - 1) {
        const [x] = layout.widgets.splice(idx, 1);
        layout.widgets.splice(idx + 1, 0, x);
        renderEditableGrid(grid, layout);
      }
    };
    const rm = el("button", "", "✕");
    rm.title = "Remove widget";
    rm.onclick = (e) => {
      e.stopPropagation();
      layout.widgets.splice(idx, 1);
      renderEditableGrid(grid, layout);
    };
    tools.append(vis, up, down, rm);
    div.appendChild(tools);
    const handle = el("div", "resize-handle");
    div.appendChild(handle);
    div.draggable = true;
    div.addEventListener("dragstart", (e) => {
      e.dataTransfer.setData("text/plain", String(idx));
      div.classList.add("dragging");
    });
    div.addEventListener("dragend", () => div.classList.remove("dragging"));
    div.addEventListener("dragover", (e) => {
      e.preventDefault();
      div.classList.add("drop-target");
    });
    div.addEventListener("dragleave", () => div.classList.remove("drop-target"));
    div.addEventListener("drop", (e) => {
      e.preventDefault();
      div.classList.remove("drop-target");
      const from = Number(e.dataTransfer.getData("text/plain"));
      if (!isNaN(from) && from !== idx) {
        const [w] = layout.widgets.splice(from, 1);
        layout.widgets.splice(idx, 0, w);
        renderEditableGrid(grid, layout);
      }
    });
    attachResize(handle, div, w);
    grid.appendChild(div);
  });
}

function attachResize(handle, div, w) {
  handle.onmousedown = (e) => {
    e.preventDefault();
    e.stopPropagation();
    const startX = e.clientX;
    const startY = e.clientY;
    const startW = w.w;
    const startH = w.h;
    const onMove = (ev) => {
      w.w = Math.max(1, Math.min(3, startW + Math.round((ev.clientX - startX) / 180)));
      w.h = Math.max(1, Math.min(3, startH + Math.round((ev.clientY - startY) / 120)));
      div.style.gridColumn = "span " + w.w;
      div.style.gridRow = "span " + w.h;
    };
    const onUp = () => {
      document.removeEventListener("mousemove", onMove);
      document.removeEventListener("mouseup", onUp);
    };
    document.addEventListener("mousemove", onMove);
    document.addEventListener("mouseup", onUp);
  };
}

function enterEditMode() {
  layoutEditing = true;
  draftLayout = JSON.parse(JSON.stringify(state.dashboard));
  $("#designer-toolbar").classList.remove("hidden");
  const palette = $("#palette");
  palette.innerHTML = "";
  for (const [type, label] of PALETTE) {
    const b = el("button", "btn btn-sm", "+ " + label);
    b.onclick = () => {
      draftLayout.widgets.push({ type, w: 1, h: 1, visible: true });
      renderDashboard();
    };
    palette.appendChild(b);
  }
  $("#btn-edit-layout").textContent = "Exit Edit Mode";
  renderDashboard();
}

function exitEditMode(save) {
  if (!layoutEditing) return;
  if (save) {
    api("/api/dashboard", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(draftLayout),
    }).then((layout) => {
      state.dashboard = layout;
      finishEdit();
    }).catch((e) => toast("error", "Save layout failed", e.message));
  } else {
    finishEdit();
  }
  function finishEdit() {
    layoutEditing = false;
    draftLayout = null;
    $("#designer-toolbar").classList.add("hidden");
    $("#btn-edit-layout").textContent = "Edit Layout";
    renderDashboard();
  }
}

// ------------------------------------------------------------------ projects

function renderProjects() {
  const list = $("#project-list");
  list.innerHTML = "";
  if (!state.projects.length) {
    list.appendChild(el("div", "empty", "No projects. Click “Add Project”."));
  }
  for (const p of state.projects) {
    const row = el("div", "pl-item" + (selectedProject === p.id ? " active" : ""));
    row.innerHTML = "<div class='pl-name'>" + esc(p.name) + "</div>" +
      "<div class='pl-sub'>" + esc(p.id) + " · " + esc(p.type) + " · " + stateBadge(p.runtimeState) + "</div>";
    row.onclick = () => { selectedProject = p.id; selectedTab = "overview"; renderProjects(); };
    list.appendChild(row);
  }
  const p = project(selectedProject);
  if (!p) {
    $("#project-detail-title").textContent = "Select a project";
    $("#project-tab-body").innerHTML = '<p class="muted">Select a project from the list.</p>';
    return;
  }
  $("#project-detail-title").innerHTML = esc(p.name) +
    ' <span class="muted">' + esc(p.id) + "</span>";
  const tabs = $("#project-tabs");
  const tabDefs = [["overview", "Overview"], ["structure", "Structure"], ["git", "Git"],
    ["build", "Build"], ["launch", "Launch"], ["ftest", "Functional Tests"], ["logs", "Logs"],
    ["config", "Configuration"]];
  if (tabs.dataset.project !== p.id) {
    tabs.dataset.project = p.id;
    tabs.innerHTML = "";
    for (const [id, label] of tabDefs) {
      const t = el("button", "tab" + (selectedTab === id ? " active" : ""), label);
      t.onclick = () => { selectedTab = id; renderProjects(); };
      tabs.appendChild(t);
    }
  } else {
    tabs.querySelectorAll(".tab").forEach((t, i) => {
      t.classList.toggle("active", selectedTab === tabDefs[i][0]);
    });
  }
  const body = $("#project-tab-body");
  switch (selectedTab) {
    case "overview": renderOverviewTab(p, body); break;
    case "structure": renderStructureTab(p, body); break;
    case "git": renderGitTab(p, body); break;
    case "build": renderBuildTab(p, body); break;
    case "launch": renderLaunchTab(p, body); break;
    case "ftest": renderFTestTab(p, body); break;
    case "logs": renderLogsTab(p, body); break;
    case "config": renderConfigTab(p, body); break;
  }
}

function projectActions(p) {
  const d = el("div", "row");
  const build = el("button", "btn", "Build");
  build.onclick = () => startOp("build", p.id).then(() => toast("info", p.id, "Build started"))
    .catch((e) => toast("error", p.id, e.message));
  const test = el("button", "btn", "Test");
  test.onclick = () => startOp("test", p.id).then(() => toast("info", p.id, "Tests started"))
    .catch((e) => toast("error", p.id, e.message));
  const edit = el("button", "btn", "Edit");
  edit.onclick = () => openEditProject(p.id);
  const dup = el("button", "btn", "Duplicate");
  dup.onclick = () => openDuplicateProject(p.id);
  const more = el("button", "btn", "More ▾");
  d.append(build, test, edit, dup, more);
  const menu = buildContextMenu(more, p);
  return d;
}

function buildContextMenu(anchor, p) {
  const menu = el("div", "ctx-menu hidden");
  menu.style.cssText = "position:absolute;z-index:400;background:var(--panel2);border:1px solid var(--border);border-radius:6px;padding:4px;min-width:220px";
  const items = [];
  if (p.operations && p.operations.length) {
    for (const op of p.operations) {
      items.push([opLabel(op), () => startOp(op, p.id).then(() => toast("info", p.id, opLabel(op) + " started"))
        .catch((e) => toast("error", p.id, e.message))]);
    }
  }
  items.push(["Git fetch", () => startOp("gitFetch", p.id).then(() => toast("info", p.id, "git fetch started"))
    .catch((e) => toast("error", p.id, e.message))]);
  const modules = (p.structure && p.structure.modules) || [];
  if (modules.length) {
    items.push(["Build module…", () => {
      const m = window.prompt("Module to build (with dependencies):", modules[0]);
      if (m) startOp("buildModule", p.id, null, null, { module: m.trim() })
        .then(() => toast("info", p.id, "Module build started"))
        .catch((e) => toast("error", p.id, e.message));
    }]);
  }
  items.push(["Unregister project…", () => openUnregister(p)]);
  items.push(["Delete local repository…", () => openDeleteRepo(p)]);
  for (const [label, fn] of items) {
    const b = el("button", "btn btn-sm", label);
    b.style.cssText = "display:block;width:100%;text-align:left;background:transparent;border:none;padding:6px 10px;cursor:pointer";
    b.onclick = (e) => {
      e.stopPropagation();
      menu.classList.add("hidden");
      fn();
    };
    menu.appendChild(b);
  }
  anchor.style.position = "relative";
  anchor.appendChild(menu);
  anchor.onclick = (e) => {
    e.stopPropagation();
    menu.classList.toggle("hidden");
  };
  document.addEventListener("click", () => menu.classList.add("hidden"), { once: true });
  return menu;
}

function opLabel(k) {
  return { build: "Build", cleanBuild: "Clean Build", update: "Resolve Dependencies",
    test: "Test", package: "Package", verify: "Verify", install: "Install",
    validate: "Validate", buildModule: "Build Module…", checkUpdates: "Check Updates",
    updateAll: "Update All",
    buildProduct: "Build Product", validateProduct: "Validate Product",
    buildFeatures: "Build Features", buildPlugins: "Build Plugins",
    exportProduct: "Export Product", exportLinux: "Export Linux",
    exportWindows: "Export Windows", exportMacosx: "Export macOS",
    exportAll: "Export All Platforms", buildP2Repository: "Build p2 Repository",
    publishUpdateSite: "Publish Update Site", validateUpdateRepository: "Validate Update Repository",
    inspectVersions: "Inspect Published Versions" }[k] || k;
}

function renderOverviewTab(p, body) {
  body.innerHTML = "";
  body.appendChild(projectActions(p));
  const kv = el("div", "kv");
  for (const [k, v] of [
    ["ID", p.id], ["Type", p.type + " (" + p.detectionConfidence + ")"],
    ["Repository", p.repoDir], ["Repository URL", p.repoUrl || "—"],
    ["Runtime state", p.runtimeState + (p.pid > 0 ? " (pid " + p.pid + ")" : "")],
    ["Launch config", p.launchConfig || "—"],
    ...(p.runtimeState === "EXTERNAL" ? [["External process", p.externalDetail || "—"]] : []),
    ["Uptime", fmtDuration(p.uptimeMs)],
    ["Last error", p.lastError || "—"],
  ]) {
    kv.appendChild(el("div", "k", k));
    kv.appendChild(el("div", "v", v));
  }
  body.appendChild(kv);
  if ((p.warnings || []).length) {
    body.appendChild(el("h4", "", "Warnings"));
    for (const w of p.warnings) body.appendChild(el("div", "warn-text", "⚠ " + esc(w)));
  }
  body.appendChild(el("h4", "", "Launch configurations"));
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Name</th><th>Kind</th><th>Ready</th><th></th></tr></thead>";
  const tb = el("tbody");
  for (const lc of p.launchConfigs || []) {
    const tr = el("tr");
    tr.innerHTML = "<td><b>" + esc(lc.name) + "</b>" + (lc.default ? " <span class='tag'>default</span>" : "") + "</td>" +
      "<td class='mono'>" + esc(lc.kind) + "</td>" +
      "<td>" + (lc.launcherExists ? '<span class="ok-text">built</span>' : '<span class="warn-text">not built</span>') + "</td>" +
      '<td><button class="btn btn-sm" data-act="run-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Run</button> ' +
      '<button class="btn btn-sm" data-act="ftest-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Test</button></td>';
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  body.appendChild(t);
}

function renderStructureTab(p, body) {
  body.innerHTML = "";
  const s = p.structure || {};
  const hist = (state.ops.history || []).filter((o) => o.project === p.id);
  const lastOk = (types, label) => {
    for (const o of hist) {
      if (types.includes(o.type)) {
        return (o.status === "SUCCEEDED" ? "succeeded " : o.status.toLowerCase() + " ") + fmtTime(o.endMs || o.startMs);
      }
    }
    return "not run";
  };
  const kv = el("div", "kv");
  const rows = [
    ["Product", s.productName ? s.productName + " (" + s.productUid + ")" : "—"],
    ["Version", s.productVersion || "—"],
    ["Build status", p.lastBuild ? p.lastBuild.status + " · " + fmtTime(p.lastBuild.endMs || p.lastBuild.startMs) : lastOk(["build", "cleanBuild"], "build")],
    ["Export status", p.lastExport ? p.lastExport.status + " · " + fmtTime(p.lastExport.endMs || p.lastExport.startMs) : lastOk(["exportProduct", "exportAll"], "export")],
    ["Target platform", (s.platforms || []).join(", ") || (s.targetPlatforms || []).join(", ") || "—"],
    ["Output location", s.productOutput ? s.productOutput + (s.productOutputExists ? " (built)" : " (not built yet)") : "—"],
    ["Update repository", s.updateRepository ? s.updateRepository + (s.updateRepositoryExists ? " (exists)" : " (not built yet)") : "—"],
    ["Parent / aggregator", s.parentPom ? (s.parentPom === "pom.xml" ? "pom.xml (repository root)" : s.parentPom) : "—"],
    ["Module count", (s.modules || []).length],
    ["Features", (s.features || []).join(", ") || "—"],
    ["Products", (s.products || []).join(", ") || "—"],
    ["Eclipse launch configs", (s.eclipseLaunchConfigs || []).join(", ") || "—"],
    ["Required Java", s.requiresJava ? "Java " + s.requiresJava : "Java 21 (default)"],
    ["Structure snapshot", s.structureAt ? new Date(s.structureAt).toLocaleString() : "—"],
  ];
  for (const [k, v] of rows) {
    kv.appendChild(el("div", "k", k));
    kv.appendChild(el("div", "v", v));
  }
  body.appendChild(kv);
  const refresh = el("button", "btn", "Refresh structure");
  refresh.onclick = () => api("/api/projects/" + encodeURIComponent(p.id) + "/refresh", { method: "POST" })
    .then(() => toast("info", p.id, "Structure refreshed"))
    .catch((e) => toast("error", p.id, e.message));
  body.appendChild(refresh);
  if ((s.modules || []).length) {
    body.appendChild(el("h4", "", "Reactor modules"));
    const t = el("table", "tbl");
    t.innerHTML = "<thead><tr><th>Module</th><th>Role</th></tr></thead>";
    const tb = el("tbody");
    for (const m of s.modules) {
      const tr = el("tr");
      tr.innerHTML = "<td class='mono'>" + esc(m.name || m) + "</td><td>" + esc(m.role || "—") + "</td>";
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    body.appendChild(t);
  }
}

function renderGitTab(p, body) {
  body.innerHTML = "";
  const g = p.git || {};
  if (!g.isRepo) {
    body.appendChild(el("div", "muted", "Not a git repository."));
    return;
  }
  const row = el("div", "row");
  for (const [label, type] of [["Fetch", "gitFetch"], ["Pull", "gitPull"], ["Push", "gitPush"],
    ["Check Updates", "checkUpdates"], ["Update All…", "updateAll"]]) {
    const b = el("button", "btn", label);
    b.onclick = () => {
      const run = (extra) => startOp(type, p.id, null, null, extra)
        .then(() => toast("info", p.id, "git " + label + " started"))
        .catch((e) => toast("error", p.id, e.message));
      if (type === "gitPush") {
        confirmDialog("Push " + p.id,
          "Push commits to the remote? This is externally visible and requires your confirmation.",
          () => run());
      } else if (type === "updateAll") {
        confirmDialog("Update All — " + p.id,
          "Fetch and analyze remote changes, then integrate them ONLY if a fast-forward is safe. " +
          "Local changes are never discarded. Proceed?",
          () => run({ confirm: true }));
      } else {
        run();
      }
    };
    row.appendChild(b);
  }
  const commit = el("button", "btn", "Commit…");
  commit.onclick = () => openCommitDialog(p);
  row.appendChild(commit);
  body.appendChild(row);
  const kv = el("div", "kv");
  for (const [k, v] of [
    ["Branch", g.branch], ["HEAD", g.head], ["Tracking", g.tracking || "—"],
    ["Modified", g.modifiedCount || 0], ["Staged", g.stagedCount || 0],
    ["Untracked", g.untrackedCount || 0],
  ]) {
    kv.appendChild(el("div", "k", k));
    kv.appendChild(el("div", "v", String(v)));
  }
  body.appendChild(kv);
  body.appendChild(el("h4", "", "Changed files"));
  const list = el("div", "card-list");
  const files = (g.files || []).slice(0, 60);
  if (!files.length) list.appendChild(el("div", "muted", "Working tree clean."));
  for (const f of files) {
    const row = el("div");
    row.innerHTML = '<span class="mono">' + esc(f.state) + "</span> " + esc(f.path);
    list.appendChild(row);
  }
  body.appendChild(list);
  if ((g.branches || []).length) {
    body.appendChild(el("h4", "", "Branches"));
    const bl = el("div", "card-list");
    for (const b of g.branches.slice(0, 20)) {
      const row = el("div", "row");
      row.innerHTML = '<span class="mono">' + esc(b.name) + "</span>" +
        (b.current ? ' <span class="tag">current</span>' : "") +
        '<span class="spacer"></span>';
      if (!b.current) {
        const sw = el("button", "btn btn-sm", "Switch");
        sw.onclick = () => {
          if (g.dirty) {
            confirmDialog("Switch branch",
              "Uncommitted changes are present. Switching may fail or carry them over — nothing will be discarded automatically.",
              () => doSwitch(p.id, b.name));
          } else {
            doSwitch(p.id, b.name);
          }
        };
        row.appendChild(sw);
      }
      bl.appendChild(row);
    }
    body.appendChild(bl);
  }
  if ((g.recentCommits || []).length) {
    body.appendChild(el("h4", "", "Recent commits"));
    const cl = el("div", "log-view small");
    cl.textContent = g.recentCommits.join("\n");
    body.appendChild(cl);
  }
}

async function doSwitch(projectId, branch) {
  try {
    const r = await api("/api/projects/" + projectId + "/git-switch", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ branch }),
    });
    if (!r.ok) throw new Error(r.error);
    toast("success", projectId, "Switched to branch " + branch);
    refreshProject(projectId);
  } catch (e) {
    toast("error", "Branch switch failed", e.message);
  }
}

function renderBuildTab(p, body) {
  body.innerHTML = "";
  body.appendChild(projectActions(p));
  const cur = activeOp(p.id);
  if (cur && ["build", "cleanBuild", "update", "package", "verify", "install", "buildProduct",
    "buildFeatures", "buildPlugins", "exportProduct", "exportLinux", "exportWindows",
    "exportMacosx", "exportAll", "buildP2Repository", "publishUpdateSite"].includes(cur.type)) {
    body.appendChild(el("h4", "", "In progress: " + cur.type));
    const known = cur.progressKnown && cur.totalModules > 0;
    const prog = el("div", "progress" + (known ? "" : " indeterminate"));
    const fill = el("div", "fill");
    if (known) fill.style.width = Math.min(100, Math.round(100 * cur.completedModules / cur.totalModules)) + "%";
    prog.appendChild(fill);
    body.appendChild(prog);
    body.appendChild(el("div", "muted", cur.phase + (cur.currentModule ? " — " + cur.currentModule : "")));
  }
  for (const kind of ["build", "test", "update"]) {
    const last = kind === "build" ? p.lastBuild : kind === "test" ? p.lastTest : p.lastUpdate;
    body.appendChild(el("h4", "", kind === "build" ? "Last build"
      : kind === "test" ? "Last test run" : "Last dependency update"));
    if (!last) {
      body.appendChild(el("div", "muted", "Not run in this session."));
      continue;
    }
    body.appendChild(el("div", "", stateBadge(last.status) + " · " + fmtTime(last.endMs) +
      " · " + fmtDuration(last.endMs - last.startMs)));
    body.appendChild(el("div", "muted", last.summary || ""));
    if (last.error) {
      const box = el("div", "log-view small", last.error);
      box.style.color = "var(--err)";
      body.appendChild(box);
    }
  }
}

function renderLaunchTab(p, body) {
  body.innerHTML = "";
  const row = el("div", "row");
  const add = el("button", "btn", "Add launch configuration");
  add.onclick = () => openLaunchConfigEditor(p.id, null);
  row.appendChild(add);
  body.appendChild(row);
  body.appendChild(el("h4", "", "Launch configurations"));
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Name</th><th>Kind</th><th>Port</th><th>Status</th><th></th></tr></thead>";
  const tb = el("tbody");
  for (const lc of p.launchConfigs || []) {
    const tr = el("tr");
    const portInfo = lc.port ? '<span class="mono">' + lc.port + "</span>"
      : (lc.readiness && lc.readiness.port ? '<span class="mono">' + lc.readiness.port + "</span>" : "—");
    tr.innerHTML = "<td><b>" + esc(lc.name) + "</b>" + (lc.default ? " <span class='tag'>default</span>" : "") + "</td>" +
      "<td class='mono'>" + esc(lc.kind) + "</td>" +
      "<td>" + portInfo + "</td>" +
      "<td>" + (lc.launcherExists ? '<span class="ok-text">ready</span>' : '<span class="warn-text">not built</span>') + "</td>" +
      '<td><button class="btn btn-sm" data-act="run-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Run</button> ' +
      '<button class="btn btn-sm" data-act="ftest-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Test</button> ' +
      '<button class="btn btn-sm" data-act="edit-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Edit</button> ' +
      '<button class="btn btn-sm" data-act="dup-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Duplicate</button> ' +
      '<button class="btn btn-sm" data-act="def-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Default</button> ' +
      '<button class="btn btn-sm" data-act="del-lc" data-p="' + p.id + '" data-lc="' + lc.id + '">Delete</button></td>';
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  body.appendChild(t);
  if (p.runtimeState !== "STOPPED" && p.runtimeState !== "FAILED") {
    const row2 = el("div", "row");
    const stop = el("button", "btn btn-danger", "Stop");
    stop.onclick = () => startOp("stop", p.id).then(() => toast("info", p.id, "Stop requested"));
    const restart = el("button", "btn", "Restart");
    restart.onclick = () => startOp("restart", p.id).then(() => toast("info", p.id, "Restart requested"));
    row2.append(stop, restart);
    body.appendChild(row2);
  }
}

function renderLogsTab(p, body) {
  body.innerHTML = "";
  const row = el("div", "row");
  const refresh = el("button", "btn", "Refresh");
  const box = el("div", "log-view large");
  const load = () => api("/api/projects/" + p.id + "/logs").then((r) => {
    box.textContent = (r.log || "(empty)") + (r.crashed ? "\n\n=== crash marker present ===" : "");
    box.scrollTop = box.scrollHeight;
  }).catch((e) => box.textContent = e.message);
  refresh.onclick = load;
  row.appendChild(refresh);
  load();
  body.append(row, box);
}

function renderConfigTab(p, body) {
  body.innerHTML = "";
  const kv = el("div", "kv");
  for (const [k, v] of [
    ["ID", p.id], ["Type (adapter)", p.type],
    ["Detection", p.detectionConfidence + " (" + p.detectionSource + ")"],
    ["Repository", p.repoDir],
    ["Dependencies (startupAfter)", (p.startupAfter || []).join(", ") || "—"],
    ["ENR monitor", p.enrMonitor ? "yes" : "no"],
    ["API base", p.apiBase || "—"],
  ]) {
    kv.appendChild(el("div", "k", k));
    kv.appendChild(el("div", "v", v));
  }
  body.appendChild(kv);
  body.appendChild(el("h4", "", "Operations"));
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Operation</th><th>Command</th></tr></thead>";
  const tb = el("tbody");
  for (const [name, block] of Object.entries(p._opDetails || {})) {
    const tr = el("tr");
    tr.innerHTML = "<td>" + esc(name) + "</td><td class='mono'>" + esc((block.cmd || []).join(" ")) + "</td>";
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  body.appendChild(t);
  body.appendChild(el("div", "muted",
    "Configuration lives in workspaces/" + state.workspace.id + "/workspace.jsonc (JSONC). " +
      "Commands are validated by the backend; arbitrary commands cannot be issued through the web API."));
}

// ------------------------------------------------------ functional tests

function ftestBadge(status) {
  return '<span class="badge st-' + (status || "NOT_RUN") + '">' + (status || "NOT_RUN") + "</span>";
}

async function runFTest(projectId, launchConfigId, mode) {
  try {
    const r = await api("/api/ftests", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ project: projectId, launchConfig: launchConfigId, mode: mode || "web" }),
    });
    toast("info", projectId, "Functional test started (run #" + r.opId + ")");
    refreshState();
    return r.opId;
  } catch (e) {
    toast("error", projectId, e.message);
    return null;
  }
}

function renderFTestTab(p, body) {
  body.innerHTML = "";
  const row = el("div", "row");
  const runAll = el("button", "btn btn-primary", "Run all tests");
  runAll.onclick = async () => {
    const insts = (p.funcTestInstances || []).filter((i) => i.mode !== "RCP");
    if (!insts.length) { toast("error", p.id, "No non-RCP instances to test"); return; }
    for (const i of insts) {
      await runFTest(p.id, i.launchConfig, i.mode);
    }
  };
  row.appendChild(runAll);
  for (const fmt of ["html", "json", "md", "zip"]) {
    const b = el("button", "btn btn-sm", "Export " + fmt.toUpperCase());
    b.onclick = () => downloadFTest(fmt, "project", { project: p.id });
    row.appendChild(b);
  }
  body.appendChild(row);
  body.appendChild(el("h4", "", "Functional test instances"));
  const insts = p.funcTestInstances || [];
  if (!insts.length) {
    body.appendChild(el("div", "muted", "No launch configurations to test for this project."));
    return;
  }
  const t = el("table", "tbl");
  t.innerHTML = "<thead><tr><th>Instance</th><th>Mode</th><th>Last result</th><th></th></tr></thead>";
  const tb = el("tbody");
  for (const i of insts) {
    const last = i.lastResult;
    const tr = el("tr");
    const lastCell = last
      ? ftestBadge(last.status) + " · " + fmtTime(last.endMs) + '<br><span class="muted">' + esc(last.summary || "") + "</span>"
      : '<span class="muted">not run</span>';
    tr.innerHTML = "<td><b>" + esc(i.launchConfigName || i.launchConfig) + "</b></td>" +
      "<td>" + esc(i.modeLabel || i.mode) + "</td>" +
      "<td>" + lastCell + "</td>" +
      '<td><button class="btn btn-sm" data-ftest-run="' + p.id + "|" + i.launchConfig + "|" + i.mode + '">Run</button> ' +
      (last ? '<button class="btn btn-sm" data-ftest-view="' + last.id + '">View</button>' : "") + "</td>";
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  body.appendChild(t);
  body.appendChild(el("div", "muted",
    "TEST verifies that the instance actually works in the selected execution mode — each row " +
      "runs independently. Results can be exported individually or collectively."));
}

function openFTestChooser(projectId, lcId) {
  const p = project(projectId);
  if (!p) return;
  let insts = p.funcTestInstances || [];
  if (lcId) insts = insts.filter((i) => i.launchConfig === lcId);
  if (!insts.length) {
    toast("error", projectId, "No testable instances" + (lcId ? " for " + lcId : "") + ".");
    return;
  }
  let html = '<table class="tbl"><thead><tr><th>Instance</th><th>Mode</th><th>Last</th><th></th></tr></thead><tbody>';
  for (const i of insts) {
    const last = i.lastResult;
    html += "<tr><td><b>" + esc(i.launchConfigName || i.launchConfig) + "</b></td>" +
      "<td>" + esc(i.modeLabel || i.mode) + "</td>" +
      "<td>" + (last ? ftestBadge(last.status) : '<span class="muted">—</span>') + "</td>" +
      '<td><button class="btn btn-sm btn-primary" data-ftest-run="' + p.id + "|" + i.launchConfig + "|" + i.mode + '">Run</button></td></tr>';
  }
  html += "</tbody></table>";
  html += '<div class="muted">Each mode is tested independently — testing one mode does not validate another.</div>';
  openModal("Test — " + esc(p.name), html);
}

async function openFTestRun(runId) {
  let run;
  try {
    run = await api("/api/ftests/" + runId);
  } catch (e) {
    openModal("Functional test #" + runId, '<p class="err-text">' + esc(e.message) + "</p>");
    return;
  }
  renderFTestRunModal(run);
}

function renderFTestRunModal(run) {
  let html = '<div class="kv">';
  for (const [k, v] of [
    ["Project", run.projectName + " (" + run.project + ")"],
    ["Instance", run.launchConfig],
    ["Mode", run.modeLabel + " (" + run.mode + ")"],
    ["Status", ftestBadge(run.status)],
    ["Duration", fmtDuration(run.durationMs)],
    ["Started", fmtTime(run.startMs)],
    ["Scenario", run.scenario],
  ]) {
    html += '<div class="k">' + k + '</div><div class="v">' + (k === "Status" ? v : esc(v)) + "</div>";
  }
  html += "</div>";
  if (run.summary) html += '<p class="muted">' + esc(run.summary) + "</p>";
  html += "<h4>Steps</h4><table class='tbl'><thead><tr><th>#</th><th>Step</th><th>Status</th><th>Actual</th></tr></thead><tbody>";
  for (const s of run.steps || []) {
    html += "<tr><td>" + s.seq + "</td><td><b>" + esc(s.name) + "</b>" +
      (s.description ? '<br><span class="muted">' + esc(s.description) + "</span>" : "") +
      "</td><td>" + ftestBadge(s.status) + "</td><td>" + esc(s.actual || "") +
      (s.error ? '<br><span class="err-text">' + esc(s.error) + "</span>" : "") +
      "</td></tr>";
  }
  html += "</tbody></table>";
  for (const s of run.steps || []) {
    if (s.log && s.log.trim()) {
      html += "<h4>" + s.seq + ". " + esc(s.name) + " — output</h4>" +
        '<div class="log-view small">' + esc(s.log.slice(-4000)) + "</div>";
    }
  }
  if (run.diagnostics && Object.keys(run.diagnostics).length) {
    html += "<h4>Routing / diagnostics</h4>" +
      '<div class="log-view small">' + esc(JSON.stringify(run.diagnostics, null, 2)) + "</div>";
  }
  html += "<h4>Export</h4><div class='row'>";
  for (const fmt of ["html", "json", "md", "zip"]) {
    html += '<button class="btn btn-sm" data-ftest-export="' + fmt + "|run|" + run.id + '">' + fmt.toUpperCase() + "</button> ";
  }
  html += "</div>";
  openModal("Functional test #" + run.id + " — " + esc(run.project), html);
}

function downloadFTest(format, scope, opts) {
  const q = new URLSearchParams({ format, scope });
  if (opts) {
    if (opts.project) q.set("project", opts.project);
    if (opts.mode) q.set("mode", opts.mode);
    if (opts.runId) q.set("runId", opts.runId);
  }
  const a = document.createElement("a");
  a.href = "/api/ftests/export?" + q.toString();
  a.download = "upm-ftests." + (format === "md" ? "md" : format);
  document.body.appendChild(a);
  a.click();
  a.remove();
}

// ------------------------------------------------------ add/edit/remove

async function openAddProjectWizard() {
  openModal("Add Project", "<div id='add-wizard'><p class='muted'>Loading…</p></div>");
  const box = $("#add-wizard");
  box.innerHTML = "";
  const field = (label, input) => {
    const f = el("div", "field");
    f.appendChild(el("label", "", label));
    f.appendChild(input);
    return f;
  };
  const mode = el("select");
  mode.appendChild(new Option("Existing local directory", "local"));
  mode.appendChild(new Option("Git repository URL", "git"));
  const pathInput = el("input");
  pathInput.type = "text";
  pathInput.placeholder = "/path/to/repository";
  const urlInput = el("input");
  urlInput.type = "text";
  urlInput.placeholder = "https://github.com/user/repo.git or git@host:repo.git";
  const destInput = el("input");
  destInput.type = "text";
  destInput.placeholder = (state.settings.defaultRepositoryDir || "~/git") + "/<name>";
  const destField = field("Destination directory (for clone)", destInput);
  destField.classList.add("hidden");
  mode.onchange = () => {
    pathInput.parentElement.classList.toggle("hidden", mode.value !== "local");
    urlInput.parentElement.classList.toggle("hidden", mode.value !== "git");
    destField.classList.toggle("hidden", mode.value !== "git");
  };
  box.append(field("Source", mode), field("Directory", pathInput), field("Git URL", urlInput), destField);
  const result = el("div");
  result.style.marginTop = "10px";
  box.appendChild(result);
  const detect = el("button", "btn btn-primary", "Detect");
  const apply = el("button", "btn btn-primary hidden", "Register Project");
  const detectBox = el("div");
  detectBox.style.marginTop = "10px";
  box.append(detect, apply, detectBox);
  let detection = null;
  let pending = null;
  detect.onclick = async () => {
    detectBox.innerHTML = '<p class="muted">Detecting…</p>';
    try {
      const body = { source: mode.value };
      if (mode.value === "local") body.path = pathInput.value;
      else {
        body.url = urlInput.value;
        body.destDir = destInput.value;
      }
      const r = await api("/api/detect", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (mode.value === "git" && r.exists === false) {
        // Not cloned yet: offer cloning.
        detectBox.innerHTML = '<p class="ok-text">Repository reachable. Default branch: ' +
          esc(r.defaultBranch || "?") + "</p>" +
          '<p class="muted">It will be cloned into:</p><p class="mono">' + esc(r.destDir) + "</p>";
        pending = { kind: "git", url: urlInput.value, destDir: r.destDir };
        const cloneBtn = el("button", "btn btn-primary", "Clone and register");
        cloneBtn.onclick = async () => {
          cloneBtn.disabled = true;
          try {
            const c = await api("/api/projects/clone", {
              method: "POST",
              headers: { "Content-Type": "application/json" },
              body: JSON.stringify({ url: urlInput.value, destDir: r.destDir }),
            });
            if (!c.ok) throw new Error(c.error);
            showDetection(c.detection, c.proposedOperations || {});
          } catch (e2) {
            toast("error", "Clone failed", e2.message);
            cloneBtn.disabled = false;
          }
        };
        detectBox.appendChild(cloneBtn);
        return;
      }
      if (mode.value === "git" && r.exists === true) {
        detectBox.innerHTML = '<p class="warn-text">Destination already exists.</p>' +
          (r.isRepo
            ? '<p class="muted">It is a git repository; it will be registered without cloning.</p>'
            : '<p class="err-text">It is not a git repository. Choose another destination.</p>');
        if (r.isRepo) showDetection(r.detection, {});
        return;
      }
      showDetection(r.detection, r.proposedOperations || {});
      pending = { kind: "local", path: pathInput.value };
    } catch (e) {
      detectBox.innerHTML = '<p class="err-text">' + esc(e.message) + "</p>";
    }
  };
  function showDetection(d, ops) {
    detection = d;
    let opsHtml = "";
    for (const [k, b] of Object.entries(ops)) {
      opsHtml += "<div class='kv'><div class='k'>" + esc(opLabel(k)) + "</div>" +
        "<div class='v'>" + esc((b.cmd || []).join(" ")) + "</div></div>";
    }
    detectBox.innerHTML =
      "<h4>Detection result</h4>" +
      "<p>Type: <b>" + esc(d.typeId) + "</b> — " + stateBadge(d.confidence) + "</p>" +
      (d.evidence && d.evidence.length ? "<p class='muted'>Evidence: " + esc(d.evidence.join(", ")) + "</p>" : "") +
      (d.alternativeTypes && d.alternativeTypes.length ? "<p class='muted'>Alternatives: " + esc(d.alternativeTypes.join(", ")) + "</p>" : "") +
      (d.hint ? "<p class='muted'>" + esc(d.hint) + "</p>" : "") +
      (opsHtml ? "<h4>Proposed operations</h4>" + opsHtml : "") +
      (d.proposedLaunchConfigs && d.proposedLaunchConfigs.length
        ? "<h4>Proposed launch configurations</h4><div class='mono'>" +
          esc(d.proposedLaunchConfigs.map((l) => l.id).join(", ")) + "</div>" : "");
    if (d.confidence === "UNSUPPORTED") {
      detectBox.appendChild(el("div", "warn-text", "No build system detected — you can still register it as a custom project with explicit commands."));
    }
    apply.classList.remove("hidden");
  }
  apply.onclick = async () => {
    const repoDir = mode.value === "local" ? pathInput.value
      : pending && pending.destDir ? pending.destDir : destInput.value;
    const id = repoDir.split("/").filter(Boolean).pop() || "project";
    const body = {
      id: id.replace(/[^A-Za-z0-9_-]/g, "-"),
      name: id,
      repoDir,
      repoUrl: mode.value === "git" ? urlInput.value : "",
      type: detection ? detection.typeId : "custom",
      detectionConfidence: "USER_CONFIRMED",
      launchConfigs: detection ? detection.proposedLaunchConfigs : [],
      warnings: [],
    };
    try {
      const r = await api("/api/projects", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!r.ok) throw new Error(r.error);
      toast("success", body.id, "Project registered");
      closeModal();
      await refreshState();
      selectedProject = body.id;
      switchView("projects");
    } catch (e) {
      toast("error", "Registration failed", e.message);
    }
  };
}

async function openEditProject(id) {
  const p = project(id);
  openModal("Edit Project — " + p.name, '<div id="edit-form"><p class="muted">Loading…</p></div>');
  const box = $("#edit-form");
  box.innerHTML = "";
  const basic = el("div");
  const adv = el("div");
  adv.classList.add("hidden");
  const f = (container, label, input) => {
    const d = el("div", "field");
    d.appendChild(el("label", "", label));
    d.appendChild(input);
    container.appendChild(d);
  };
  const name = el("input"); name.type = "text"; name.value = p.name;
  const desc = el("input"); desc.type = "text"; desc.value = p.description || "";
  const repoDir = el("input"); repoDir.type = "text"; repoDir.value = p.repoDir;
  const repoUrl = el("input"); repoUrl.type = "text"; repoUrl.value = p.repoUrl || "";
  const typeSel = el("select");
  try {
    const adapters = await api("/api/adapters");
    for (const [tid, a] of Object.entries(adapters)) {
      const o = new Option(a.name + " (" + tid + ")", tid);
      typeSel.appendChild(o);
    }
  } catch (e) { /* fall back to current */ }
  const cur = new Option(p.type, p.type);
  cur.selected = true;
  typeSel.appendChild(cur);
  typeSel.value = p.type;
  f(basic, "Name", name);
  f(basic, "Description", desc);
  f(basic, "Repository directory", repoDir);
  f(basic, "Repository URL", repoUrl);
  f(basic, "Project type (adapter)", typeSel);
  const startupAfter = el("input"); startupAfter.type = "text";
  startupAfter.value = (p.startupAfter || []).join(", ");
  f(adv, "Startup dependencies (project ids, comma-separated)", startupAfter);
  const warnings = el("textarea"); warnings.rows = 3; warnings.value = (p.warnings || []).join("\n");
  f(adv, "Warnings (one per line)", warnings);
  const opsArea = el("textarea");
  opsArea.rows = 12;
  opsArea.value = JSON.stringify(p._opDetails || {}, null, 1);
  f(adv, "Operations (JSON: op -> {cmd, env, timeoutMinutes})", opsArea);
  const lcsArea = el("textarea");
  lcsArea.rows = 10;
  lcsArea.value = JSON.stringify((p.launchConfigs || []).map((l) => ({
    id: l.id, name: l.name, kind: l.kind, launcher: l.launcher, args: l.args,
    port: l.port, portFixed: l.portFixed, requiresDisplay: l.requiresDisplay,
    stopGraceSeconds: l.stopGraceSeconds, startupTimeoutSeconds: l.startupTimeoutSeconds / 1000,
    default: l.default,
    readiness: l.readiness,
    readyMarkers: l.readyMarkers,
  })), null, 1);
  f(adv, "Launch configurations (JSON)", lcsArea);
  const toggle = el("button", "btn btn-sm", "Show advanced settings");
  toggle.onclick = () => {
    adv.classList.toggle("hidden");
    toggle.textContent = adv.classList.contains("hidden") ? "Show advanced settings" : "Hide advanced settings";
  };
  box.append(basic, toggle, adv);
  const saveBtn = el("button", "btn btn-primary", "Save Changes");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    const body = {
      name: name.value,
      description: desc.value,
      repoDir: repoDir.value,
      repoUrl: repoUrl.value,
      type: typeSel.value,
      startupAfter: startupAfter.value.split(",").map((s) => s.trim()).filter(Boolean),
      warnings: warnings.value.split("\n").map((s) => s.trim()).filter(Boolean),
    };
    if (!adv.classList.contains("hidden")) {
      try {
        body.operations = JSON.parse(opsArea.value || "{}");
        body.launchConfigs = JSON.parse(lcsArea.value || "[]");
      } catch (e) {
        toast("error", "Invalid JSON", e.message);
        return;
      }
    }
    try {
      const r = await api("/api/projects/" + id, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
      });
      if (!r.ok) throw new Error(r.error);
      toast("success", id, "Project updated");
      closeModal();
      await refreshState();
      renderProjects();
    } catch (e) {
      toast("error", "Update failed", e.message);
    }
  };
}

async function openDuplicateProject(id) {
  const p = project(id);
  openModal("Duplicate Project — " + p.name,
    "<p class='muted'>Creates a new registry entry. The repository is NOT copied.</p>" +
    "<div id='dup-form'></div>");
  const box = $("#dup-form");
  const f = (label, input) => {
    const d = el("div", "field");
    d.appendChild(el("label", "", label));
    d.appendChild(input);
    box.appendChild(d);
  };
  const newId = el("input"); newId.type = "text"; newId.value = p.id + "-copy";
  const name = el("input"); name.type = "text"; name.value = p.name + " (copy)";
  const repoDir = el("input"); repoDir.type = "text"; repoDir.value = p.repoDir;
  f("New project ID", newId);
  f("Display name", name);
  f("Repository directory (may point elsewhere)", repoDir);
  const saveBtn = el("button", "btn btn-primary", "Duplicate");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    try {
      const r = await api("/api/projects/" + id + "/duplicate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ newId: newId.value, name: name.value, repoDir: repoDir.value }),
      });
      if (!r.ok) throw new Error(r.error);
      toast("success", newId.value, "Project duplicated");
      closeModal();
      await refreshState();
    } catch (e) {
      toast("error", "Duplicate failed", e.message);
    }
  };
}

function openUnregister(p) {
  confirmDialog("Unregister " + p.name,
    "Remove the project from the workspace registry?<br><br>" +
    "<b>The repository at " + esc(p.repoDir) + " will NOT be deleted</b> and no processes are touched.",
    async () => {
      try {
        const r = await api("/api/projects/" + p.id + "/unregister", { method: "POST" });
        if (!r.ok) throw new Error(r.error);
        toast("success", p.id, "Unregistered (repository kept)");
        await refreshState();
      } catch (e) {
        toast("error", "Unregister failed", e.message);
      }
    });
}

function openDeleteRepo(p) {
  confirmDialog("Delete local repository — " + p.name,
    "This will permanently delete the directory:<br><br>" +
    "<span class='mono'>" + esc(p.repoDir) + "</span><br><br>" +
    "The project is unregistered first. This cannot be undone.",
    async () => {
      try {
        const r = await api("/api/projects/" + p.id + "/delete-repo", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ confirm: true }),
        });
        if (!r.ok) throw new Error(r.error);
        toast("success", p.id, "Repository deleted");
        await refreshState();
      } catch (e) {
        toast("error", "Delete failed", e.message);
      }
    });
}

function openCommitDialog(p) {
  openModal("Commit — " + p.name, "<div id='commit-form'></div>");
  const box = $("#commit-form");
  const msg = el("textarea");
  msg.rows = 3;
  msg.placeholder = "Commit message";
  const f = el("div", "field");
  f.appendChild(el("label", "", "Message"));
  f.appendChild(msg);
  box.appendChild(f);
  box.appendChild(el("div", "muted", "Only staged changes are committed. Nothing is pushed automatically."));
  const saveBtn = el("button", "btn btn-primary", "Commit");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    if (!msg.value.trim()) {
      toast("error", "Commit", "Message required");
      return;
    }
    try {
      const r = await startOp("gitCommit", p.id, null, null, { message: msg.value.trim() });
      toast("info", p.id, "Commit started (op #" + r.opId + ")");
      closeModal();
    } catch (e) {
      toast("error", "Commit failed", e.message);
    }
  };
}

function openLaunchConfigEditor(projectId, lcId) {
  const p = project(projectId);
  const lc = lcId ? (p.launchConfigs || []).find((l) => l.id === lcId) : null;
  openModal((lc ? "Edit" : "Add") + " launch configuration — " + p.name, "<div id='lc-form'></div>");
  const box = $("#lc-form");
  const f = (label, input) => {
    const d = el("div", "field");
    d.appendChild(el("label", "", label));
    d.appendChild(input);
    box.appendChild(d);
  };
  const id = el("input"); id.type = "text"; id.value = lc ? lc.id : "";
  if (lc) id.disabled = true;
  const name = el("input"); name.type = "text"; name.value = lc ? lc.name : "";
  const kind = el("select");
  for (const k of ["eclipse-product", "eclipse-application", "jar", "native", "http-server", "custom-command"]) {
    kind.appendChild(new Option(k, k));
  }
  kind.value = lc ? lc.kind : "custom-command";
  const launcher = el("input"); launcher.type = "text";
  launcher.value = lc ? lc.launcher : "";
  launcher.placeholder = "repo-relative or absolute path";
  const args = el("input"); args.type = "text";
  args.value = lc ? (lc.args || []).join(" ") : "";
  args.placeholder = "-arg1 -arg2 --port {port}";
  const port = el("input"); port.type = "number"; port.value = lc ? (lc.port || "") : "";
  const portFixed = el("input"); portFixed.type = "checkbox"; portFixed.checked = lc ? lc.portFixed !== false : true;
  const display = el("input"); display.type = "checkbox"; display.checked = lc ? !!lc.requiresDisplay : false;
  const markers = el("input"); markers.type = "text";
  markers.value = lc ? (lc.readyMarkers || []).join("|") : "";
  markers.placeholder = "READY marker regex fragments separated by |";
  const rPath = el("input"); rPath.type = "text"; rPath.value = lc && lc.readiness ? lc.readiness.path : "/";
  const rExpect = el("select");
  rExpect.appendChild(new Option("HTTP 200", "200"));
  rExpect.appendChild(new Option("any HTTP response", "any"));
  rExpect.value = lc && lc.readiness ? lc.readiness.expect : "200";
  const rResolve = el("select");
  for (const k of ["fixed", "portsFile", "allocationsFile", "range", "configFile"]) {
    rResolve.appendChild(new Option(k, k));
  }
  rResolve.value = lc && lc.readiness ? lc.readiness.resolve : "fixed";
  const rSourceFile = el("input"); rSourceFile.type = "text";
  rSourceFile.value = lc && lc.readiness ? (lc.readiness.sourceFile || "") : "";
  rSourceFile.placeholder = "e.g. ~/.app/ports.properties or enr.yaml (relative to repo)";
  const rSourceKey = el("input"); rSourceKey.type = "text";
  rSourceKey.value = lc && lc.readiness ? (lc.readiness.sourceKey || "") : "";
  const rPort = el("input"); rPort.type = "number";
  rPort.value = lc && lc.readiness ? (lc.readiness.port || "") : "";
  const stopGrace = el("input"); stopGrace.type = "number";
  stopGrace.value = lc ? lc.stopGraceSeconds : 20;
  const startupTimeout = el("input"); startupTimeout.type = "number";
  startupTimeout.value = lc ? Math.round(lc.startupTimeoutSeconds / 1000) : 180;
  f("ID", id);
  f("Name", name);
  f("Kind", kind);
  f("Launcher", launcher);
  f("Arguments", args);
  f("Port (0 = none)", port);
  const pf = el("div", "field checkbox");
  pf.appendChild(portFixed);
  pf.appendChild(el("span", "", "Port is deterministic (fail fast on conflict)"));
  box.appendChild(pf);
  const pd = el("div", "field checkbox");
  pd.appendChild(display);
  pd.appendChild(el("span", "", "Requires graphical display"));
  box.appendChild(pd);
  f("Ready markers (| separated)", markers);
  f("Readiness: port resolve", rResolve);
  f("Readiness: source file", rSourceFile);
  f("Readiness: source key", rSourceKey);
  f("Readiness: probe port / range start", rPort);
  f("Readiness: HTTP path", rPath);
  f("Readiness: expect", rExpect);
  f("Stop grace (s)", stopGrace);
  f("Startup timeout (s)", startupTimeout);
  const saveBtn = el("button", "btn btn-primary", "Save");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    const lcJson = {
      id: id.value || "run-" + Date.now(),
      name: name.value || id.value,
      kind: kind.value,
      launcher: launcher.value,
      args: args.value.split(/\s+/).filter(Boolean),
      port: Number(port.value) || 0,
      portFixed: portFixed.checked,
      requiresDisplay: display.checked,
      stopGraceSeconds: Number(stopGrace.value) || 20,
      startupTimeoutSeconds: Number(startupTimeout.value) || 180,
      default: lc ? lc.default : false,
      readyMarkers: markers.value.split("|").map((s) => s.trim()).filter(Boolean),
      readiness: {
        type: "http", host: "127.0.0.1",
        resolve: rResolve.value,
        sourceFile: rSourceFile.value,
        sourceKey: rSourceKey.value,
        port: Number(rPort.value) || 0,
        path: rPath.value || "/",
        expect: rExpect.value,
      },
    };
    try {
      let r;
      if (lc) {
        const body = { launchConfigs: (p.launchConfigs || []).map((l) =>
          l.id === lc.id ? lcJson : l) };
        r = await api("/api/projects/" + projectId, {
          method: "PUT",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(body),
        });
      } else {
        r = await api("/api/projects/" + projectId + "/launchconfigs", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify(lcJson),
        });
      }
      if (!r.ok) throw new Error(r.error);
      toast("success", projectId, "Launch configuration saved");
      closeModal();
      await refreshState();
    } catch (e) {
      toast("error", "Save failed", e.message);
    }
  };
}

async function refreshProject(id) {
  await refreshState();
  selectedProject = id;
  renderProjects();
}

// ------------------------------------------------------------------ workspaces

async function renderWorkspaces() {
  const cards = $("#workspace-cards");
  cards.innerHTML = "";
  for (const w of state.workspaces || []) {
    const card = el("div", "ws-card" + (w.current ? " current" : ""));
    card.innerHTML = "<div class='name'>" + esc(w.name) + "</div>" +
      "<div class='sub mono'>" + esc(w.id) + " · " + (w.projectCount || 0) + " projects · " +
      (w.profileCount || 0) + " profiles" + (w.current ? " · current" : "") + "</div>" +
      (w.error ? '<div class="err-text">' + esc(w.error) + "</div>" : "");
    const row = el("div", "row");
    if (!w.current) {
      const sw = el("button", "btn btn-sm btn-primary", "Switch");
      sw.onclick = () => switchWorkspace(w.id);
      row.appendChild(sw);
    }
    const ren = el("button", "btn btn-sm", "Rename");
    ren.onclick = () => openRenameWorkspace(w);
    const dup = el("button", "btn btn-sm", "Duplicate");
    dup.onclick = () => openDuplicateWorkspace(w);
    const exp = el("button", "btn btn-sm", "Export");
    exp.onclick = () => exportWorkspace(w.id);
    const bak = el("button", "btn btn-sm", "Backup");
    bak.onclick = () => backupWorkspace(w.id);
    const del = el("button", "btn btn-sm btn-danger", "Delete");
    del.onclick = () => openDeleteWorkspace(w);
    row.append(sw, ren, dup, exp, bak, del);
    card.appendChild(row);
    cards.appendChild(card);
  }
  if (!(state.workspaces || []).length) {
    cards.appendChild(el("div", "empty", "No workspaces."));
  }
  // profiles
  const pc = $("#profile-cards");
  pc.innerHTML = "";
  for (const pr of state.profiles || []) {
    const card = el("div", "profile-card");
    card.innerHTML = "<div class='name'>" + esc(pr.name) + "</div>" +
      "<div class='sub mono'>" + esc(pr.id) + "</div>" +
      "<div class='plist muted'>" + esc((pr.projects || []).map((pid) => pid).join(" → ")) + "</div>";
    const row = el("div", "row");
    const launch = el("button", "btn btn-sm btn-primary", "Launch");
    launch.onclick = () => startOp("profileLaunch", "", pr.id).then((r) =>
      toast("info", pr.name, "Profile launch started (op #" + r.opId + ")"))
      .catch((e) => toast("error", pr.name, e.message));
    const stop = el("button", "btn btn-sm btn-danger", "Stop");
    stop.onclick = () => startOp("profileStop", "", pr.id).then((r) =>
      toast("info", pr.name, "Profile stop started"))
      .catch((e) => toast("error", pr.name, e.message));
    const edit = el("button", "btn btn-sm", "Edit");
    edit.onclick = () => openEditProfile(pr);
    const del = el("button", "btn btn-sm", "Delete");
    del.onclick = () => confirmDialog("Delete profile " + pr.name,
      "Remove the profile definition? Projects are not touched.", async () => {
        await api("/api/profiles/" + pr.id + "/delete", { method: "POST" });
        await refreshState();
        toast("success", pr.id, "Profile deleted");
      });
    row.append(launch, stop, edit, del);
    card.appendChild(row);
    pc.appendChild(card);
  }
  if (!(state.profiles || []).length) {
    pc.appendChild(el("div", "empty", "No profiles. Create one to define a startup set."));
  }
  // backups
  let backups = [];
  try {
    backups = await api("/api/backups");
  } catch (e) { /* ignore */ }
  const bl = $("#backup-list");
  bl.innerHTML = "";
  if (!backups.length) bl.appendChild(el("div", "muted", "No backups yet."));
  for (const b of backups.slice(0, 10)) {
    const row = el("div", "row");
    row.innerHTML = '<span class="mono">' + esc(b.name) + "</span>" +
      '<span class="muted">' + fmtTime(b.createdMs) + '</span><span class="spacer"></span>';
    const rest = el("button", "btn btn-sm", "Restore");
    rest.onclick = () => confirmDialog("Restore backup",
      "Restore workspace '" + esc(state.workspace.id) + "' from backup '" + esc(b.name) + "'? " +
      "The current workspace configuration is replaced (after validation).",
      async () => {
        try {
          await api("/api/backups/" + encodeURIComponent(b.name) + "/restore", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ workspaceId: state.workspace.id }),
          });
          toast("success", "Backup", "Workspace restored");
          await refreshState();
        } catch (e) {
          toast("error", "Restore failed", e.message);
        }
      });
    row.appendChild(rest);
    bl.appendChild(row);
  }
}

async function switchWorkspace(id) {
  try {
    const r = await api("/api/workspaces/" + id + "/switch", { method: "POST" });
    if (!r.ok) throw new Error(r.error);
    await refreshState();
    toast("success", "Workspace", "Switched to " + id);
  } catch (e) {
    toast("error", "Switch failed", e.message);
  }
}

function openRenameWorkspace(w) {
  openModal("Rename workspace — " + w.name, "<div id='ren-form'></div>");
  const box = $("#ren-form");
  const name = el("input"); name.type = "text"; name.value = w.name;
  const desc = el("input"); desc.type = "text";
  const f1 = el("div", "field"); f1.appendChild(el("label", "", "Name")); f1.appendChild(name);
  const f2 = el("div", "field"); f2.appendChild(el("label", "", "Description")); f2.appendChild(desc);
  box.append(f1, f2);
  const saveBtn = el("button", "btn btn-primary", "Save");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    try {
      const r = await api("/api/workspaces/" + w.id + "/rename", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ name: name.value, description: desc.value }),
      });
      if (!r.ok) throw new Error(r.error);
      closeModal();
      await refreshState();
    } catch (e) {
      toast("error", "Rename failed", e.message);
    }
  };
}

function openDuplicateWorkspace(w) {
  openModal("Duplicate workspace — " + w.name, "<div id='dupws-form'></div>");
  const box = $("#dupws-form");
  const id = el("input"); id.type = "text"; id.value = w.id + "-copy";
  const f = el("div", "field"); f.appendChild(el("label", "", "New workspace ID")); f.appendChild(id);
  box.appendChild(f);
  const saveBtn = el("button", "btn btn-primary", "Duplicate");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    try {
      const r = await api("/api/workspaces/" + w.id + "/duplicate", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ targetId: id.value }),
      });
      if (!r.ok) throw new Error(r.error);
      closeModal();
      await refreshState();
      toast("success", id.value, "Workspace duplicated");
    } catch (e) {
      toast("error", "Duplicate failed", e.message);
    }
  };
}

async function exportWorkspace(id) {
  try {
    const r = await fetch("/api/workspaces/" + id + "/export", { method: "POST" });
    if (!r.ok) {
      const b = await r.json().catch(() => ({}));
      throw new Error(b.error || "export failed");
    }
    const blob = await r.blob();
    const a = document.createElement("a");
    a.href = URL.createObjectURL(blob);
    a.download = "workspace-" + id + ".zip";
    a.click();
    toast("success", "Export", "workspace-" + id + ".zip downloaded");
  } catch (e) {
    toast("error", "Export failed", e.message);
  }
}

async function backupWorkspace(id) {
  try {
    const r = await api("/api/workspaces/" + id + "/backup", { method: "POST" });
    if (!r.ok) throw new Error(r.error);
    toast("success", "Backup", r.backup + " created");
    renderWorkspaces();
  } catch (e) {
    toast("error", "Backup failed", e.message);
  }
}

function openDeleteWorkspace(w) {
  confirmDialog("Delete workspace — " + w.name,
    "Delete the workspace configuration?" + (w.current ? "<br><br>You cannot delete the active workspace; switch first." : "") +
    "<br>Repositories are never deleted.",
    async () => {
      try {
        const r = await api("/api/workspaces/" + w.id + "/delete", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ archive: true }),
        });
        if (!r.ok) throw new Error(r.error);
        await refreshState();
        toast("success", w.id, "Workspace archived");
      } catch (e) {
        toast("error", "Delete failed", e.message);
      }
    });
}

function openNewWorkspace() {
  openModal("New Workspace", "<div id='newws-form'></div>");
  const box = $("#newws-form");
  const id = el("input"); id.type = "text"; id.placeholder = "development";
  const name = el("input"); name.type = "text"; name.placeholder = "Development";
  const f1 = el("div", "field"); f1.appendChild(el("label", "", "ID")); f1.appendChild(id);
  const f2 = el("div", "field"); f2.appendChild(el("label", "", "Name")); f2.appendChild(name);
  box.append(f1, f2);
  const saveBtn = el("button", "btn btn-primary", "Create");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    try {
      const r = await api("/api/workspaces", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ id: id.value, name: name.value || id.value }),
      });
      if (!r.ok) throw new Error(r.error);
      closeModal();
      await refreshState();
    } catch (e) {
      toast("error", "Create failed", e.message);
    }
  };
}

function openImportWorkspace() {
  openModal("Import Workspace", "<div id='imp-form'></div>");
  const box = $("#imp-form");
  const file = el("input");
  file.type = "file";
  file.accept = ".zip";
  const f = el("div", "field");
  f.appendChild(el("label", "", "Exported workspace package (.zip)"));
  f.appendChild(file);
  box.appendChild(f);
  const preview = el("div");
  box.appendChild(preview);
  let zipB64 = null;
  file.onchange = async () => {
    if (!file.files.length) return;
    zipB64 = await toBase64(file.files[0]);
    try {
      const p = await api("/api/import", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ zipBase64: zipB64, preview: true }),
      });
      if (p.error) {
        preview.innerHTML = '<p class="err-text">' + esc(p.error) + "</p>";
        return;
      }
      let missing = (p.projects || []).filter((x) => !x.exists);
      preview.innerHTML =
        "<h4>Preview</h4>" +
        "<p>Workspace: <b>" + esc(p.workspaceName || "") + "</b> (" + esc(p.workspaceId) + ")</p>" +
        "<p class='muted'>" + (p.projects || []).length + " projects, " + (p.profileCount || 0) + " profiles</p>" +
        (missing.length ? "<p class='warn-text'>Missing local paths: " +
          esc(missing.map((x) => x.repoDir).join(", ")) + "</p>" : "");
      preview.appendChild(el("h4", "", "Path remapping (old → new)"));
      const remaps = el("textarea");
      remaps.rows = 4;
      remaps.placeholder = "/old/path/repo=/new/path/repo\n(one per line)";
      preview.appendChild(remaps);
      const targetId = el("input");
      targetId.type = "text";
      targetId.placeholder = "New workspace id (default: imported id)";
      preview.appendChild(targetId);
      const applyBtn = el("button", "btn btn-primary", "Import");
      applyBtn.onclick = async () => {
        const pathRemaps = {};
        for (const line of remaps.value.split("\n")) {
          const [a, b] = line.split("=", 2);
          if (a && b) pathRemaps[a.trim()] = b.trim();
        }
        try {
          const r = await api("/api/import", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ zipBase64: zipB64, workspaceId: targetId.value || null, pathRemaps }),
          });
          if (!r.ok) throw new Error(r.error);
          toast("success", "Import", "Workspace imported as " + r.workspaceId);
          closeModal();
          await refreshState();
        } catch (e) {
          toast("error", "Import failed", e.message);
        }
      };
      preview.appendChild(applyBtn);
    } catch (e) {
      preview.innerHTML = '<p class="err-text">' + esc(e.message) + "</p>";
    }
  };
}

function toBase64(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(String(r.result).split(",")[1]);
    r.onerror = reject;
    r.readAsDataURL(file);
  });
}

function openNewProfile() {
  openModal("New Profile", "<div id='np-form'></div>");
  const box = $("#np-form");
  const id = el("input"); id.type = "text"; id.placeholder = "development";
  const name = el("input"); name.type = "text"; name.placeholder = "Development";
  const f1 = el("div", "field"); f1.appendChild(el("label", "", "ID")); f1.appendChild(id);
  const f2 = el("div", "field"); f2.appendChild(el("label", "", "Name")); f2.appendChild(name);
  box.append(f1, f2);
  const projBox = el("div");
  box.appendChild(el("label", "", "Projects"));
  for (const p of state.projects) {
    const cb = el("div", "field checkbox");
    const c = el("input"); c.type = "checkbox"; c.value = p.id;
    cb.append(c, el("span", "", p.name + " (" + p.id + ")"));
    projBox.appendChild(cb);
  }
  box.appendChild(projBox);
  const order = el("input"); order.type = "text";
  order.placeholder = "startup order, e.g. ENR,EVO,DCoder";
  const f3 = el("div", "field"); f3.appendChild(el("label", "", "Startup order (project ids, comma-separated)")); f3.appendChild(order);
  box.appendChild(f3);
  const saveBtn = el("button", "btn btn-primary", "Create");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    const projects = [...projBox.querySelectorAll("input:checked")].map((c) => c.value);
    try {
      const r = await api("/api/profiles", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id: id.value, name: name.value || id.value, projects,
          startupOrder: order.value.split(",").map((s) => s.trim()).filter(Boolean),
          stopOrder: [], environment: {},
        }),
      });
      if (!r.ok) throw new Error(r.error);
      closeModal();
      await refreshState();
    } catch (e) {
      toast("error", "Create failed", e.message);
    }
  };
}

function openEditProfile(pr) {
  openModal("Edit profile — " + pr.name, "<div id='ep-form'></div>");
  const box = $("#ep-form");
  const projBox = el("div");
  for (const p of state.projects) {
    const cb = el("div", "field checkbox");
    const c = el("input"); c.type = "checkbox"; c.value = p.id;
    c.checked = (pr.projects || []).includes(p.id);
    cb.append(c, el("span", "", p.name + " (" + p.id + ")"));
    projBox.appendChild(cb);
  }
  box.appendChild(projBox);
  const order = el("input"); order.type = "text";
  order.value = (pr.startupOrder || []).join(",");
  const f3 = el("div", "field");
  f3.appendChild(el("label", "", "Startup order (project ids, comma-separated; missing ids are ordered by dependencies)"));
  f3.appendChild(order);
  box.appendChild(f3);
  const saveBtn = el("button", "btn btn-primary", "Save");
  $("#modal-actions").appendChild(saveBtn);
  saveBtn.onclick = async () => {
    const projects = [...projBox.querySelectorAll("input:checked")].map((c) => c.value);
    try {
      const r = await api("/api/profiles/" + pr.id, {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          projects,
          startupOrder: order.value.split(",").map((s) => s.trim()).filter(Boolean),
          stopOrder: pr.stopOrder || [], environment: pr.environment || {},
        }),
      });
      if (!r.ok) throw new Error(r.error);
      closeModal();
      await refreshState();
    } catch (e) {
      toast("error", "Save failed", e.message);
    }
  };
}

// ------------------------------------------------------------------ operations

function renderOperations() {
  const activeList = $("#active-ops-list");
  activeList.innerHTML = "";
  if (!(state.ops.active || []).length) {
    activeList.appendChild(el("div", "muted", "No operations running."));
  }
  for (const op of state.ops.active || []) {
    activeList.appendChild(opItem(op));
  }
  const histList = $("#history-ops-list");
  histList.innerHTML = "";
  for (const op of (state.ops.history || []).slice(0, 60)) {
    histList.appendChild(opItem(op));
  }
  if (selectedOp) renderOpDetail(selectedOp.id);
}

function opItem(op) {
  const row = el("div", "op-item");
  const known = op.progressKnown && op.totalModules > 0;
  row.innerHTML = "<div class='row1'><b>" + esc(op.project || "workspace") + "</b> — " +
    esc(op.type) + (op.mode ? "/" + esc(op.mode) : "") + " " + stateBadge(op.status) +
    ' <span class="muted">' + fmtDuration(op.endMs > 0 ? op.endMs - op.startMs : Date.now() - op.startMs) + "</span></div>" +
    "<div class='row2'>" + esc(op.phase) +
    (known ? " · " + op.completedModules + "/" + op.totalModules + " modules" : "") +
    (op.testsRun >= 0 ? " · tests: " + op.testsRun + " run / " + op.testsFailed + " failed" : "") +
    " · " + fmtTime(op.startMs) + "</div>";
  row.onclick = () => openOpDetail(op.id);
  return row;
}

async function openOpDetail(opId) {
  selectedOp = { id: opId };
  activeView = "operations";
  switchView("operations");
  renderOpDetail(opId);
}

async function renderOpDetail(opId) {
  try {
    const op = await api("/api/ops/" + opId);
    selectedOp = op;
    $("#op-detail-title").textContent = (op.project || "workspace") + " — " + op.type +
      (op.mode ? "/" + op.mode : "") + " #" + op.id;
    const body = $("#op-detail-body");
    body.innerHTML = "";
    const meta = el("div", "kv");
    for (const [k, v] of [
      ["Status", op.status], ["Started", fmtTime(op.startMs)],
      ["Duration", fmtDuration((op.endMs > 0 ? op.endMs : Date.now()) - op.startMs)],
      ["Phase", op.phase + (op.currentModule ? " — " + op.currentModule : "")],
      ["Tests", op.testsRun >= 0 ? op.testsRun + " run, " + op.testsFailed + " failed, " +
        op.testsErrors + " errors, " + op.testsSkipped + " skipped" : "—"],
      ["Result", op.summary || "—"],
    ]) {
      meta.appendChild(el("div", "k", k));
      const vv = el("div", "v");
      vv.innerHTML = k === "Status" ? stateBadge(v) : esc(v);
      meta.appendChild(vv);
    }
    body.appendChild(meta);
    if (op.error) {
      const errBox = el("div", "log-view small", op.error);
      errBox.style.color = "var(--err)";
      body.appendChild(el("h4", "", "Error details"));
      body.appendChild(errBox);
    }
    if (op.status === "RUNNING" || op.status === "QUEUED") {
      const cancel = el("button", "btn btn-danger", "Cancel operation");
      cancel.onclick = async () => {
        try {
          await api("/api/ops/" + opId + "/cancel", { method: "POST" });
        } catch (e) {
          toast("error", "Cancel failed", e.message);
        }
      };
      body.appendChild(cancel);
    }
    body.appendChild(el("h4", "", "Full log"));
    const logEl = el("div", "log-view large");
    logEl.id = "op-live-log";
    logEl.textContent = op.log || "";
    logEl.scrollTop = logEl.scrollHeight;
    body.appendChild(logEl);
  } catch (e) {
    $("#op-detail-title").textContent = "Operation unavailable";
    $("#op-detail-body").innerHTML = '<p class="err-text">' + esc(e.message) + "</p>";
  }
}

// ------------------------------------------------------------------ settings

const SETTINGS_TABS = [
  ["general", "General"], ["paths", "Paths"], ["runtime", "Runtime"],
  ["tools", "Tools"], ["monitoring", "Monitoring"],
];

function renderSettings() {
  const tabs = $("#settings-tabs");
  if (tabs.dataset.init !== "1") {
    tabs.dataset.init = "1";
    tabs.innerHTML = "";
    for (const [id, label] of SETTINGS_TABS) {
      const t = el("button", "tab" + (selectedSettingsTab === id ? " active" : ""), label);
      t.onclick = () => { selectedSettingsTab = id; renderSettings(); };
      tabs.appendChild(t);
    }
  } else {
    tabs.querySelectorAll(".tab").forEach((t, i) => {
      t.classList.toggle("active", selectedSettingsTab === SETTINGS_TABS[i][0]);
    });
  }
  const s = state.settings || {};
  const body = $("#settings-body");
  body.innerHTML = "";
  const f = (label, input) => {
    const d = el("div", "field");
    d.appendChild(el("label", "", label));
    d.appendChild(input);
    body.appendChild(d);
  };
  if (selectedSettingsTab === "general") {
    const theme = el("select");
    theme.appendChild(new Option("Dark", "dark"));
    theme.appendChild(new Option("Light", "light"));
    theme.value = (state.app && state.app.theme) || "light";
    const defWs = el("input"); defWs.type = "text"; defWs.value = (state.app && state.app.defaultWorkspace) || "default";
    f("Theme", theme);
    f("Default workspace", defWs);
  } else if (selectedSettingsTab === "paths") {
    const repoDir = el("input"); repoDir.type = "text"; repoDir.value = s.defaultRepositoryDir || "~/git";
    f("Default repository directory", repoDir);
    const appDir = el("input"); appDir.type = "text"; appDir.value = s.appDir || ""; appDir.disabled = true;
    f("Application directory (read-only)", appDir);
  } else if (selectedSettingsTab === "runtime") {
    const port = el("input"); port.type = "number"; port.value = state.app.port || 29080;
    const browser = el("input"); browser.type = "checkbox"; browser.checked = state.app.openBrowser !== false;
    const openBrowser = el("div", "field checkbox");
    openBrowser.append(browser, el("span", "", "Open the browser on startup"));
    const wsEnabled = el("input"); wsEnabled.type = "checkbox";
    wsEnabled.checked = state.app.webServerEnabled !== false;
    const wsEnabledBox = el("div", "field checkbox");
    wsEnabledBox.append(wsEnabled, el("span", "", "Web server enabled (applies after restart)"));
    const wsAutoStart = el("input"); wsAutoStart.type = "checkbox";
    wsAutoStart.checked = state.app.webServerAutoStart !== false;
    const wsAutoStartBox = el("div", "field checkbox");
    wsAutoStartBox.append(wsAutoStart, el("span", "", "Start the web server automatically at startup (applies after restart)"));
    f("HTTP port (applies after restart)", port);
    body.appendChild(openBrowser);
    body.appendChild(wsEnabledBox);
    body.appendChild(wsAutoStartBox);
  } else if (selectedSettingsTab === "tools") {
    const bodyHolder = body;
    api("/api/tools").catch(() => ({}))
      .then((tools) => {
        const t = el("table", "tbl");
        t.innerHTML = "<thead><tr><th>Tool</th><th>Detected</th></tr></thead>";
        const tb = el("tbody");
        for (const [name, path] of Object.entries(tools)) {
          const tr = el("tr");
          tr.innerHTML = "<td>" + esc(name) + "</td><td class='mono'>" +
            (path ? '<span class="ok-text">' + esc(path) + "</span>" : '<span class="err-text">not found</span>') + "</td>";
          tb.appendChild(tr);
        }
        t.appendChild(tb);
        bodyHolder.appendChild(t);
        const re = el("button", "btn btn-sm", "Re-detect");
        re.onclick = () => renderSettings();
        bodyHolder.appendChild(re);
      });
  } else if (selectedSettingsTab === "monitoring") {
    const refresh = el("input"); refresh.type = "number"; refresh.value = s.monitorRefreshMs || 3000;
    const hist = el("input"); hist.type = "number"; hist.value = s.historyRetention || 200;
    const retention = el("input"); retention.type = "number"; retention.value = s.logRetentionDays || 7;
    f("Monitor refresh (ms)", refresh);
    f("Operation history retention (entries)", hist);
    f("Log retention (days)", retention);
  }
  body.dataset.tab = selectedSettingsTab;
}

async function saveSettings() {
  const body = {};
  const tab = $("#settings-body").dataset.tab;
  const inputs = $("#settings-body").querySelectorAll("input, select");
  if (tab === "general") {
    body.theme = inputs[0].value;
    body.defaultWorkspace = inputs[1].value;
  } else if (tab === "paths") {
    body.defaultRepositoryDir = inputs[0].value;
  } else if (tab === "runtime") {
    body.port = Number(inputs[0].value) || 29080;
    body.openBrowser = inputs[1].checked;
    body.webServerEnabled = inputs[2].checked;
    body.webServerAutoStart = inputs[3].checked;
  } else if (tab === "monitoring") {
    body.monitorRefreshMs = Number(inputs[0].value) || 3000;
    body.historyRetention = Number(inputs[1].value) || 200;
    body.logRetentionDays = Number(inputs[2].value) || 7;
  } else {
    return;
  }
  try {
    const r = await api("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (!r.ok) throw new Error(r.error);
    $("#settings-save-status").textContent = "Saved.";
    if (body.theme) {
      document.documentElement.dataset.theme = body.theme;
      state.app.theme = body.theme;
    }
    setTimeout(() => $("#settings-save-status").textContent = "", 3000);
  } catch (e) {
    $("#settings-save-status").textContent = e.message;
    $("#settings-save-status").className = "err-text";
  }
}

// ----------------------------------------------------------- notifications

function toast(level, title, detail) {
  const box = $("#toasts");
  const t = el("div", "toast " + level);
  t.innerHTML = "<div class='t'>" + esc(title) + "</div>" +
    (detail ? "<div class='d'>" + esc(detail) + "</div>" : "");
  box.appendChild(t);
  setTimeout(() => t.remove(), 8000);
}

function toastNotifications() {
  const items = state.notifications || [];
  const current = items.length;
  if (current > lastKnownNotifs) {
    for (let i = 0; i < current - lastKnownNotifs && i < 4; i++) {
      const n = items[i];
      toast(n.level, (n.project || "workspace") + ": " + n.title, n.detail);
    }
    lastKnownNotifs = current;
  }
  renderNotifs();
}

function renderNotifs() {
  const list = $("#notif-list");
  const badge = $("#notif-badge");
  const items = state.notifications || [];
  badge.textContent = items.length > 0 ? items.length : "";
  badge.classList.toggle("hidden", !items.length);
  list.innerHTML = "";
  for (const n of items.slice(0, 30)) {
    const row = el("div", "notif-item");
    row.innerHTML = "<div class='t'>" + stateBadge(n.level) + " " + esc(n.project || "workspace") +
      " — " + esc(n.title) + "</div>" +
      (n.detail ? "<div class='d'>" + esc(n.detail) + "</div>" : "") +
      (n.nextStep ? "<div class='n'>next: " + esc(n.nextStep) + "</div>" : "") +
      "<div class='d' style='font-size:10px'>" + fmtTime(n.timeMs) + "</div>";
    list.appendChild(row);
  }
}

// ------------------------------------------------------------------ modals

function openModal(title, contentHtml) {
  $("#modal-title").textContent = title;
  $("#modal-body").innerHTML = contentHtml;
  $("#modal-actions").innerHTML = '<button class="btn" id="modal-close">Close</button>';
  $("#modal-close").onclick = closeModal;
  $("#modal-backdrop").classList.remove("hidden");
  document.addEventListener("keydown", escCloses, { once: false });
}

function escCloses(e) {
  if (e.key === "Escape" && !$("#modal-backdrop").classList.contains("hidden")) closeModal();
}

function closeModal() {
  $("#modal-backdrop").classList.add("hidden");
  $("#modal-actions").innerHTML = "";
  document.removeEventListener("keydown", escCloses);
}

function confirmDialog(title, bodyHtml, onYes) {
  $("#confirm-title").textContent = title;
  $("#confirm-body").innerHTML = bodyHtml;
  $("#confirm-backdrop").classList.remove("hidden");
  const yes = $("#confirm-yes");
  const no = $("#confirm-no");
  const close = () => {
    $("#confirm-backdrop").classList.add("hidden");
    yes.onclick = null;
    no.onclick = null;
  };
  yes.onclick = () => { close(); onYes(); };
  no.onclick = close;
}

async function openDiagnostics(projectId) {
  const title = projectId ? projectId + " — diagnostics" : "Workspace diagnostics";
  openModal(title, '<div id="modal-diag"><p class="muted">Running checks…</p></div>');
  const box = $("#modal-diag");
  const url = projectId ? "/api/projects/" + projectId + "/diagnostics" : "/api/diagnostics";
  try {
    const checks = await api(url);
    box.innerHTML = "";
    const list = el("div", "card-list");
    for (const c of checks) {
      const row = el("div");
      row.innerHTML = (c.status === "OK" ? '<span class="ok-text">OK</span>' : '<span class="err-text">FAIL</span>') +
        " <b>" + esc(c.label) + "</b>" +
        (c.detail ? ' <span class="muted">— ' + esc(c.detail) + "</span>" : "");
      list.appendChild(row);
    }
    box.appendChild(list);
  } catch (e) {
    box.innerHTML = '<p class="err-text">' + esc(e.message) + "</p>";
  }
}

// ------------------------------------------------------------------ state

async function refreshState() {
  try {
    const s = await api("/api/state");
    applySnapshot(s);
  } catch (e) {
    toast("error", "Refresh failed", e.message);
  }
}

// ------------------------------------------------------------------ wiring

document.addEventListener("upm-navigate-project", (e) => {
  selectedProject = e.detail.project;
  selectedTab = "overview";
  switchView("projects");
});

document.addEventListener("click", (e) => {
  const openProject = e.target.closest("[data-open-project]");
  if (openProject) {
    selectedProject = openProject.dataset.openProject;
    selectedTab = "overview";
    switchView("projects");
    return;
  }
  const openOp = e.target.closest("[data-open-op]");
  if (openOp) {
    openOpDetail(Number(openOp.dataset.openOp));
    return;
  }
  const cancelOp = e.target.closest("[data-cancel-op]");
  if (cancelOp) {
    const id = Number(cancelOp.dataset.cancelOp);
    api("/api/ops/" + id + "/cancel", { method: "POST" }).then(() =>
      toast("info", "Operation #" + id, "Cancellation requested")).catch((er) =>
      toast("error", "Cancel failed", er.message));
    return;
  }
  const ftestRun = e.target.closest("[data-ftest-run]");
  if (ftestRun) {
    const [pid, lcId, mode] = ftestRun.dataset.ftestRun.split("|");
    closeModal();
    runFTest(pid, lcId, mode);
    return;
  }
  const ftestView = e.target.closest("[data-ftest-view]");
  if (ftestView) {
    openFTestRun(Number(ftestView.dataset.ftestView));
    return;
  }
  const ftestExport = e.target.closest("[data-ftest-export]");
  if (ftestExport) {
    const [fmt, scope, runId] = ftestExport.dataset.ftestExport.split("|");
    downloadFTest(fmt, scope, { runId: runId ? Number(runId) : undefined });
    return;
  }
  const act = e.target.closest("[data-act]");
  if (act) {
    const p = act.dataset.p;
    const a = act.dataset.act;
    const lc = act.dataset.lc;
    if (a === "build") startOp("build", p).then(() => toast("info", p, "Build started")).catch((er) => toast("error", p, er.message));
    else if (a === "stop") startOp("stop", p).then(() => toast("info", p, "Stop requested")).catch((er) => toast("error", p, er.message));
    else if (a === "run") {
      const pj = project(p);
      const def = pj && pj.launchConfigs && pj.launchConfigs.find((l) => l.default);
      const lcId = def ? def.id : (pj && pj.launchConfigs && pj.launchConfigs.length ? pj.launchConfigs[0].id : null);
      if (!lcId) {
        toast("error", p, "No launch configuration defined");
        return;
      }
      startOp("run", p, lcId).then((r) => toast("info", p, "Run started (op #" + r.opId + ")"))
        .catch((er) => toast("error", p, er.message));
    } else if (a === "run-lc") {
      startOp("run", p, lc).then((r) => toast("info", p, "Run " + lc + " started (op #" + r.opId + ")"))
        .catch((er) => toast("error", p, er.message));
    } else if (a === "ftest") {
      openFTestChooser(p);
    } else if (a === "ftest-lc") {
      openFTestChooser(p, lc);
    } else if (a === "edit-lc") openLaunchConfigEditor(p, lc);
    else if (a === "dup-lc") {
      api("/api/projects/" + p + "/launchconfigs/" + lc + "/duplicate", { method: "POST" })
        .then(() => refreshState()).catch((er) => toast("error", p, er.message));
    } else if (a === "def-lc") {
      api("/api/projects/" + p + "/launchconfigs/" + lc + "/default", { method: "POST" })
        .then(() => refreshState()).catch((er) => toast("error", p, er.message));
    } else if (a === "del-lc") {
      confirmDialog("Delete launch configuration",
        "Delete launch configuration '" + lc + "' of project " + p + "?",
        () => api("/api/projects/" + p + "/launchconfigs/" + lc + "/delete", { method: "POST" })
          .then(() => refreshState()).catch((er) => toast("error", p, er.message)));
    }
  }
});

document.querySelectorAll(".view-btn").forEach((b) => {
  b.onclick = () => switchView(b.dataset.view);
});

$("#btn-refresh").onclick = () => refreshState();
$("#btn-launch-all").onclick = () => startOp("launchAll", "workspace").then((r) =>
  toast("info", "Launch All", "Started (op #" + r.opId + ")")).catch((e) => toast("error", "Launch All", e.message));
$("#btn-stop-all").onclick = () => confirmDialog("Stop All",
  "Stop every running project in this workspace?", () =>
  startOp("stopAll", "workspace").then((r) => toast("info", "Stop All", "Started (op #" + r.opId + ")"))
    .catch((e) => toast("error", "Stop All", e.message)));
$("#btn-theme").onclick = () => {
  const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
  document.documentElement.dataset.theme = next;
  state.app.theme = next;
  api("/api/settings", {
    method: "PUT",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ theme: next }),
  }).catch(() => {});
};
$("#btn-notifs").onclick = () => $("#notif-panel").classList.toggle("hidden");
$("#btn-edit-layout").onclick = () => (layoutEditing ? exitEditMode(true) : enterEditMode());
$("#btn-save-layout").onclick = () => exitEditMode(true);
$("#btn-cancel-layout").onclick = () => exitEditMode(false);
$("#btn-reset-layout").onclick = async () => {
  try {
    const layout = await api("/api/dashboard/reset", { method: "POST" });
    state.dashboard = layout;
    if (draftLayout) draftLayout = JSON.parse(JSON.stringify(layout));
    renderDashboard();
    toast("success", "Layout", "Reset to default");
  } catch (e) {
    toast("error", "Reset failed", e.message);
  }
};
$("#btn-add-project").onclick = () => openAddProjectWizard();
$("#btn-new-workspace").onclick = () => openNewWorkspace();
$("#btn-export-workspace").onclick = () => exportWorkspace(state.workspace.id);
$("#btn-import-workspace").onclick = () => openImportWorkspace();
$("#btn-backup-workspace").onclick = () => backupWorkspace(state.workspace.id);
$("#btn-new-profile").onclick = () => openNewProfile();
$("#btn-discover").onclick = () => openDiscover();
$("#btn-save-settings").onclick = () => saveSettings();
initPortsControls();
$("#modal-backdrop").addEventListener("click", (e) => {
  if (e.target.id === "modal-backdrop") closeModal();
});
$("#confirm-backdrop").addEventListener("click", (e) => {
  if (e.target.id === "confirm-backdrop") {
    $("#confirm-backdrop").classList.add("hidden");
  }
});

// ------------------------------------------------------------- boot

(async function boot() {
  try {
    const s = await api("/api/state");
    applySnapshot(s);
  } catch (e) {
    document.body.innerHTML = '<p style="padding:40px;color:#f0655a">UPM backend unavailable: ' +
      esc(e.message) + "<br>Start it with: launch.sh</p>";
    return;
  }
  connectSse();
  setInterval(async () => {
    if (activeView === "dashboard" || document.hidden) {
      try {
        const rt = await api("/api/enr/runtime");
        const models = await api("/api/enr/models");
        const inf = await api("/api/enr/inference");
        state.enr = Object.assign(rt, { models, inference: inf });
        if (activeView === "dashboard") renderDashboard();
      } catch (e) { /* ENR offline */ }
    }
  }, 8000);
})();

// ================================================================ ARCHITECTURE

let archEngine = null;
let archPerspective = "workspace-overview";
let archSavedLayout = {};
let archSelectedNode = null;

async function renderArchitecture() {
  const perspSel = $("#arch-perspective");
  if (!perspSel.options.length) {
    for (const [id, label] of [
      ["workspace-overview", "Workspace Overview"],
      ["runtime-topology", "Runtime Topology"],
      ["execution-flow", "Execution Flow"],
      ["ai-topology", "AI / LLM Topology"],
      ["dependency-graph", "Project Dependencies"],
      ["infrastructure", "Infrastructure"],
    ]) {
      const o = document.createElement("option");
      o.value = id;
      o.textContent = label;
      perspSel.appendChild(o);
    }
    perspSel.value = archPerspective;
    perspSel.onchange = () => {
      archPerspective = perspSel.value;
      loadArchGraph();
    };
    const legend = $("#arch-legend");
    legend.innerHTML = "";
    for (const [cls, label] of [["VERIFIED_RUNTIME", "verified runtime"], ["VERIFIED_STATIC", "verified static"],
      ["INFERRED", "inferred"], ["USER_CONFIRMED", "user-confirmed"], ["UNKNOWN", "unknown"]]) {
      legend.innerHTML += '<span class="rel"><span class="badge st-' + cls + '">' + cls + "</span> " + label + "</span>";
    }
    $("#arch-search").addEventListener("input", () => {
      if (archEngine) {
        archEngine.searchText = $("#arch-search").value;
        archEngine.draw();
      }
    });
    $("#arch-filter-project").onchange = () => {
      if (archEngine) {
        archEngine.filterProject = $("#arch-filter-project").value;
        archEngine.draw();
      }
    };
    $("#arch-filter-type").onchange = () => {
      if (archEngine) {
        const v = $("#arch-filter-type").value;
        archEngine.filterTypes = v ? new Set([v]) : new Set();
        archEngine.draw();
      }
    };
    $("#arch-highlight").onclick = () => {
      if (archEngine && archSelectedNode) archEngine.setHighlight(archSelectedNode.id);
    };
    $("#arch-fit").onclick = () => archEngine && archEngine.fit();
    $("#arch-reset").onclick = () => archEngine && archEngine.resetLayout();
    $("#arch-refresh").onclick = () => loadArchGraph();
    $("#arch-save-layout").onclick = saveArchLayout;
    $("#arch-export-svg").onclick = () => archEngine &&
      GraphEngine.download("architecture-" + archPerspective + ".svg",
        archEngine.exportSVG(), "image/svg+xml");
    $("#arch-export-png").onclick = () => archEngine &&
      archEngine.exportPNG((dataUrl) => {
        const a = document.createElement("a");
        a.href = dataUrl;
        a.download = "architecture-" + archPerspective + ".png";
        a.click();
      });
    $("#arch-export-json").onclick = () => archEngine &&
      GraphEngine.download("architecture-" + archPerspective + ".json",
        archEngine.exportJSON(), "application/json");
    $("#arch-export-csv").onclick = () => archEngine &&
      GraphEngine.download("architecture-" + archPerspective + ".csv",
        archEngine.exportCSV(), "text/csv");
    $("#arch-print").onclick = () => archEngine && archEngine.print();
    // load saved layouts
    try {
      archSavedLayout = await api("/api/graph/layout");
    } catch (e) {
      archSavedLayout = {};
    }
    await loadArchGraph();
  }
  // refresh project filter options
  const fp = $("#arch-filter-project");
  fp.innerHTML = '<option value="">all projects</option>';
  for (const p of state.projects) {
    const o = document.createElement("option");
    o.value = p.id;
    o.textContent = p.name;
    fp.appendChild(o);
  }
}

async function loadArchGraph() {
  try {
    const graph = await api("/api/graph?perspective=" + encodeURIComponent(archPerspective));
    if (!archEngine) {
      archEngine = new GraphEngine.Engine($("#arch-canvas"), {
        onSelect: (sel) => {
          archSelectedNode = sel.id ? sel : null;
          renderArchDetails(sel);
        },
      });
    }
    const saved = archSavedLayout[archPerspective];
    archEngine.load(graph, saved || null);
    const types = [...new Set(graph.nodes.map((n) => n.type))].sort();
    const ft = $("#arch-filter-type");
    ft.innerHTML = '<option value="">all types</option>';
    for (const t of types) {
      const o = document.createElement("option");
      o.value = t;
      o.textContent = t;
      ft.appendChild(o);
    }
    archEngine.fit();
  } catch (e) {
    $("#arch-canvas").innerHTML = '<div class="err-text">' + esc(e.message) + "</div>";
  }
}

async function saveArchLayout() {
  if (!archEngine) return;
  archSavedLayout[archPerspective] = archEngine.layoutState();
  try {
    await api("/api/graph/layout", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(archSavedLayout),
    });
    toast("success", "Architecture", "Layout saved for perspective " + archPerspective);
  } catch (e) {
    toast("error", "Save failed", e.message);
  }
}

function renderArchDetails(sel) {
  const box = $("#arch-details");
  if (sel.id && sel.label !== undefined) {
    // node
    const rows = [];
    rows.push(["ID", sel.id]);
    rows.push(["Type", sel.type]);
    if (sel.project) rows.push(["Project", sel.project]);
    rows.push(["Status", sel.status || "—"]);
    rows.push(["Classification", sel.classification]);
    if (sel.evidence && sel.evidence.length) {
      rows.push(["Evidence", sel.evidence.join("; ")]);
    }
    for (const [k, v] of Object.entries(sel.props || {})) {
      if (v !== null && v !== undefined && v !== "") {
        rows.push([k, typeof v === "object" ? JSON.stringify(v) : v]);
      }
    }
    box.innerHTML = "<h4>" + esc(sel.label) + "</h4><div class='kv'>" +
      rows.map(([k, v]) => "<div class='k'>" + esc(k) + "</div><div class='v'>" + esc(v) + "</div>").join("") +
      "</div>" +
      (sel.project
        ? '<button class="btn btn-sm" data-nav-project="' + esc(sel.project) + '">Open project</button>'
        : "");
  } else {
    // edge
    box.innerHTML = "<h4>" + esc(sel.type || "relationship") + "</h4><div class='kv'>" +
      "<div class='k'>From</div><div class='v'>" + esc(sel.from) + "</div>" +
      "<div class='k'>To</div><div class='v'>" + esc(sel.to) + "</div>" +
      "<div class='k'>Classification</div><div class='v'>" + esc(sel.classification) + "</div>" +
      (sel.evidence && sel.evidence.length
        ? "<div class='k'>Evidence</div><div class='v'>" + esc(sel.evidence.join("; ")) + "</div>" : "") +
      "</div>";
  }
}

// ================================================================ MONITORING

let monPaused = false;
let monCharts = {};
let monLastSampleMs = 0;

function renderMonitoring() {
  // metric cards
  const sys = state.system || {};
  const cards = $("#metric-cards");
  const cpu = sys.cpu || {};
  const mem = sys.memory || {};
  const gpus = sys.gpu || [];
  const gpu = gpus[0] || {};
  const net = sys.network || {};
  const items = [
    ["CPU", cpu.percent != null ? cpu.percent + "%" : "N/A", "load " + (cpu.load1 || "N/A") + (cpu.frequencyMhz ? " · " + cpu.frequencyMhz + " MHz" : "")],
    ["RAM", mem.percent != null ? mem.percent + "%" : "N/A", (mem.usedKb / 1048576).toFixed(1) + " / " + (mem.totalKb / 1048576).toFixed(1) + " GB" + (mem.swapPercent && mem.swapPercent !== "N/A" ? " · swap " + mem.swapPercent + "%" : "")],
    ["GPU", gpu.utilPercent != null ? gpu.utilPercent + "%" : "N/A", gpu.name || "not detected"],
    ["VRAM", gpu.memUsedMb != null ? (gpu.memUsedMb / 1024).toFixed(2) + " / " + (gpu.memTotalMb / 1024).toFixed(2) + " GB" : "N/A", gpu.temperature != null ? gpu.temperature + " °C" + (gpu.powerW != null ? " · " + gpu.powerW + " W" : "") : ""],
    ["Network", net.rxKbps != null ? net.rxKbps + " kB/s ↓" : "N/A", net.txKbps != null ? net.txKbps + " kB/s ↑" : ""],
    ["Managed processes", (state.projects || []).filter((p) => p.pid > 0).length, ""],
  ];
  cards.innerHTML = items.map(([title, value, sub]) =>
    '<div class="panel metric-card"><div class="m-title">' + esc(title) + '</div>' +
    '<div class="m-value">' + esc(String(value)) + '</div>' +
    (sub ? '<div class="m-sub">' + esc(sub) + "</div>" : "") + "</div>").join("");

  // charts (created once)
  const grid = $("#chart-grid");
  if (!grid.dataset.init) {
    grid.dataset.init = "1";
    const defs = [
      ["chart-cpu", "CPU utilization", "cpu", "%", { ranges: RANGES, formatY: (v) => Math.round(v) + "%" }],
      ["chart-mem", "RAM utilization", "memPercent", "%", { ranges: RANGES, formatY: (v) => Math.round(v) + "%" }],
      ["chart-gpu", "GPU utilization", "gpuUtil", "%", { ranges: RANGES, formatY: (v) => Math.round(v) + "%" }],
      ["chart-vram", "VRAM usage", "vramUsedMb", "MB", { ranges: RANGES, formatY: (v) => Math.round(v) + " MB" }],
      ["chart-net", "Network throughput", "netRxKbps", "kB/s", { ranges: RANGES, formatY: (v) => Math.round(v * 10) / 10 }],
      ["chart-sessions", "ENR inference requests (total)", "enr:requests", "req", { ranges: RANGES, formatY: (v) => Math.round(v) }],
    ];
    for (const [id, title, metric, unit, opts] of defs) {
      const box = document.createElement("div");
      box.className = "chart-box";
      box.id = id;
      grid.appendChild(box);
      monCharts[metric] = new Charts.TimeChart(box, Object.assign({ title, ranges: RANGES }, opts));
      monCharts[metric]._metric = metric;
    }
  }
  if (monLastSampleMs) {
    $("#mon-last-sample").textContent = "last sample " + new Date(monLastSampleMs).toLocaleTimeString();
  }
  renderProcessTable();
  renderLlmView();
}

const RANGES = { "5 min": 300000, "15 min": 900000, "1 hour": 3600000, "6 hours": 21600000 };

function onMetricsSample(m) {
  monLastSampleMs = m.timeMs || Date.now();
  if (monPaused) return;
  const now = Date.now();
  const cpu = m.cpu || {};
  const mem = m.memory || {};
  const gpus = m.gpu || [];
  const gpu = gpus[0] || {};
  const net = m.network || {};
  const enrMetrics = (m.enrMetrics) || {};
  const add = (metric, value) => {
    const chart = monCharts[metric];
    if (chart && value !== undefined && value !== null && value !== "N/A") {
      chart.addPoint(metric, now, value);
    }
  };
  add("cpu", cpu.percent);
  add("memPercent", mem.percent);
  add("gpuUtil", gpu.utilPercent);
  add("vramUsedMb", gpu.memUsedMb);
  add("netRxKbps", net.rxKbps);
  add("netTxKbps", net.txKbps);
  if (activeView === "monitoring" && !document.hidden) {
    renderMonitoring();
    for (const chart of Object.values(monCharts)) {
      if (!chart.paused) chart.draw();
    }
  }
}

function renderProcessTable() {
  const box = $("#process-table");
  box.innerHTML = "";
  const rows = [];
  for (const p of state.projects) {
    if (!p.pid || p.pid <= 0) continue;
    rows.push({
      project: p.id, name: p.name, pid: p.pid, status: p.runtimeState,
      startedAt: p.startedAt, cpu: "…", rss: "…", gpu: false,
    });
  }
  // enrich from last metrics sample
  api("/api/metrics/current").then((m) => {
    const t = document.createElement("table");
    t.className = "tbl";
    t.innerHTML = "<thead><tr><th>Project</th><th>PID</th><th>Status</th><th>CPU %</th>" +
      "<th>RSS</th><th>GPU</th><th>VRAM</th><th>Start</th><th>Uptime</th><th></th></tr></thead>";
    const tb = document.createElement("tbody");
    for (const proc of m.processes || []) {
      const tr = document.createElement("tr", "clickable");
      tr.innerHTML = "<td><b>" + esc(proc.name) + "</b></td>" +
        "<td class='mono'>" + esc(String(proc.pid)) + "</td>" +
        "<td>" + stateBadge(proc.status) + "</td>" +
        "<td class='mono'>" + esc(String(proc.cpuPercent)) + "</td>" +
        "<td class='mono'>" + esc(String(proc.rssMb)) + " MB</td>" +
        "<td>" + (proc.gpuProcess ? '<span class="ok-text">yes</span>' : '<span class="muted">—</span>') + "</td>" +
        "<td class='mono'>" + (proc.vramUsedMb ? proc.vramUsedMb + " MB" : "—") + "</td>" +
        "<td>" + fmtTime(proc.startedAt) + "</td>" +
        "<td>" + fmtDuration(Date.now() - proc.startedAt) + "</td>" +
        '<td><button class="btn btn-sm" data-open-project="' + proc.project + '">Open</button></td>';
      tr.dataset.openProject = proc.project;
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    box.innerHTML = "";
    box.appendChild(t);
    if (!tb.children.length) {
      box.innerHTML = '<div class="muted">No managed processes running.</div>';
    }
  }).catch(() => {});
}

async function renderLlmView() {
  const box = $("#llm-view");
  box.innerHTML = "";
  try {
    const m = await api("/api/llm");
    const t = document.createElement("table");
    t.className = "tbl";
    t.innerHTML = "<thead><tr><th>Provider</th><th>Kind</th><th>Endpoint</th><th>Health</th>" +
      "<th>Models</th><th>Active sessions</th><th>Requests</th><th>Tokens/s</th></tr></thead>";
    const tb = document.createElement("tbody");
    for (const pr of m.providers || []) {
      const enrStatus = m.enr && m.enr.status && !m.enr.status.error ? m.enr.status : {};
      const metrics = m.enrInference && m.enrInference.metrics && !m.enrInference.metrics.error
        ? m.enrInference.metrics : {};
      const sessions = m.enrInference && m.enrInference.sessions && m.enrInference.sessions.sessions
        ? m.enrInference.sessions.sessions : [];
      const loaded = m.enrModels && m.enrModels.loaded && m.enrModels.loaded.models
        ? m.enrModels.loaded.models : [];
      const tr = document.createElement("tr");
      tr.innerHTML = "<td><b>" + esc(pr.name) + "</b>" +
        (pr.runtimeState ? " " + stateBadge(pr.runtimeState) : "") + "</td>" +
        "<td class='mono'>" + esc(pr.kind) + "</td>" +
        "<td class='mono'>" + esc(pr.endpoint) + "</td>" +
        "<td>" + (pr.health === "healthy" || pr.health === "reachable"
          ? '<span class="ok-text">' + esc(pr.health) + "</span>"
          : '<span class="err-text">' + esc(pr.health || "—") + "</span>") + "</td>" +
        "<td>" + (pr.kind === "enr"
          ? (loaded.length ? loaded.map((x) => esc(x.id) + " <span class='tag'>" + esc(x.state) + "</span>").join(" ") : "none loaded")
          : (pr.models || []).map((x) => esc(x.id)).join(", ") || "—") + "</td>" +
        "<td class='mono'>" + (pr.kind === "enr" ? (enrStatus.sessions_active != null ? enrStatus.sessions_active : "—") : "—") + "</td>" +
        "<td class='mono'>" + (pr.kind === "enr" && metrics.total_requests != null ? metrics.total_requests : "—") + "</td>" +
        "<td class='mono'>" + (pr.kind === "enr" && metrics.tokens_per_second != null ? metrics.tokens_per_second : "—") + "</td>";
      tb.appendChild(tr);
    }
    t.appendChild(tb);
    box.appendChild(t);
    // ENR sessions detail (no prompts/output).
    if (m.enrInference && m.enrInference.sessions && m.enrInference.sessions.sessions
      && m.enrInference.sessions.sessions.length) {
      box.appendChild(document.createElement("h4")).textContent = "Active inference sessions (ENR)";
      const st = document.createElement("table");
      st.className = "tbl";
      st.innerHTML = "<thead><tr><th>Session</th><th>Model</th><th>Context length</th><th>Created</th></tr></thead>";
      const stb = document.createElement("tbody");
      for (const s of m.enrInference.sessions.sessions.slice(0, 12)) {
        const tr = document.createElement("tr");
        tr.innerHTML = "<td class='mono'>" + esc(s.id) + "</td><td class='mono'>" + esc(s.model) + "</td>" +
          "<td class='mono'>" + esc(String(s.context_length)) + "</td><td>" + fmtTime(s.created_at) + "</td>";
        stb.appendChild(tr);
      }
      st.appendChild(stb);
      box.appendChild(st);
    }
    box.appendChild(document.createElement("p")).className = "section-note";
    box.lastChild.textContent =
      "Model artifacts (files) are distinct from loaded runtime instances. GPU/VRAM values are system-wide measurements; " +
      "no per-model attribution is invented. Inference prompts and generated output are never displayed.";
  } catch (e) {
    box.innerHTML = '<div class="err-text">' + esc(e.message) + "</div>";
  }
}

// ================================================================ PORTS

let portsData = null;
let portsRefreshing = false;
let portsSort = { key: "port", dir: 1 };
let portsFilterApp = "";
let portsFilterStatus = "";
let portsSearch = "";

function portBadge(status) {
  return '<span class="badge pm-' + esc(status.toLowerCase()) + '">' + esc(status) + "</span>";
}

async function refreshPorts(force) {
  if (portsRefreshing) return;
  portsRefreshing = true;
  try {
    const m = await api("/api/ports/inventory" + (force ? "?refresh=1" : ""));
    portsData = m;
    if (activeView === "ports") renderPorts();
  } catch (e) {
    if (activeView === "ports") {
      $("#ports-table").innerHTML = '<div class="err-text">' + esc(e.message) + "</div>";
    }
  } finally {
    portsRefreshing = false;
  }
}

async function recheckPort(port) {
  try {
    const m = await api("/api/ports/inventory?port=" + port);
    const entry = (m.entries || [])[0];
    if (entry && portsData) {
      const list = portsData.entries || [];
      const idx = list.findIndex((x) => x.port === port);
      if (idx >= 0) list[idx] = entry;
      else list.push(entry);
      const s = { running: 0, ready: 0, occupied: 0 };
      for (const x of list) s[x.status.toLowerCase()] = (s[x.status.toLowerCase()] || 0) + 1;
      portsData.summary = s;
      portsData.timeMs = m.timeMs;
      renderPorts();
    }
  } catch (e) {
    toast("error", "Port check failed", e.message);
  }
}

function renderPorts() {
  if (!portsData) {
    $("#ports-table").innerHTML = '<div class="empty">Loading port inventory…</div>';
    refreshPorts(false);
    return;
  }
  const s = portsData.summary || {};
  const cc = portsData.controlCenter || {};
  const cards = $("#ports-summary");
  cards.innerHTML =
    '<div class="panel metric-card"><div class="m-title">Running</div>' +
    '<div class="m-value pm-running-text">' + (s.running || 0) + "</div>" +
    '<div class="m-sub">expected services listening</div></div>' +
    '<div class="panel metric-card"><div class="m-title">Ready</div>' +
    '<div class="m-value pm-ready-text">' + (s.ready || 0) + "</div>" +
    '<div class="m-sub">ports free for their services</div></div>' +
    '<div class="panel metric-card"><div class="m-title">Occupied</div>' +
    '<div class="m-value pm-occupied-text">' + (s.occupied || 0) + "</div>" +
    '<div class="m-sub">unexpected processes on the port</div></div>' +
    '<div class="panel metric-card"><div class="m-title">UPM control center</div>' +
    '<div class="m-value" style="font-size:15px">' + esc(cc.host || "127.0.0.1") + ":" + (cc.port || 0) + "</div>" +
    '<div class="m-sub">pid ' + (cc.pid || "—") + "</div></div>";
  $("#ports-last-checked").textContent = "last checked " + fmtTime(portsData.timeMs);
  renderPortsTable();
}

function renderPortsTable() {
  const box = $("#ports-table");
  box.innerHTML = "";
  const all = (portsData.entries || []).slice();
  const apps = [...new Set(all.map((x) => x.app))];
  const sel = $("#ports-filter-app");
  if (sel.options.length <= 1) {
    for (const a of apps) {
      const o = document.createElement("option");
      o.value = a;
      o.textContent = a;
      sel.appendChild(o);
    }
  }
  const filtered = all.filter((x) =>
    (!portsFilterApp || x.app === portsFilterApp) &&
    (!portsFilterStatus || x.status === portsFilterStatus) &&
    (!portsSearch || (x.service + " " + x.port + " " + (x.process || "") + " " + x.description)
      .toLowerCase().includes(portsSearch.toLowerCase())));
  const sorted = filtered.slice().sort((a, b) => {
    const k = portsSort.key;
    let va = a[k];
    let vb = b[k];
    if (k === "port") { va = Number(va) || 0; vb = Number(vb) || 0; }
    if (typeof va === "string") { va = va.toLowerCase(); vb = String(vb).toLowerCase(); }
    return va < vb ? -portsSort.dir : va > vb ? portsSort.dir : 0;
  });
  const t = el("table", "tbl");
  const th = (label, key, title) =>
    '<th class="sortable" data-port-sort="' + key + '"' +
    (title ? ' title="' + esc(title) + '"' : "") + ">" + esc(label) +
    (portsSort.key === key ? (portsSort.dir > 0 ? " ▲" : " ▼") : "") + "</th>";
  t.innerHTML = "<thead><tr>" +
    "<th>Application</th>" + th("Port", "port", "Click to sort by port") +
    "<th>Service</th>" +
    "<th>Purpose / Description</th>" + th("Protocol", "protocol") +
    th("Status", "status", "Click to sort by status") +
    "<th>Process</th><th>PID</th><th>Binding</th>" +
    th("Last checked", "lastChecked", "Click to sort by last check time") +
    "<th></th></tr></thead>";
  const tb = document.createElement("tbody");
  let lastApp = "";
  for (const e of sorted) {
    if (e.app !== lastApp) {
      lastApp = e.app;
      const grp = el("tr", "pm-group");
      const cnt = {};
      for (const x of all.filter((y) => y.app === e.app)) {
        cnt[x.status] = (cnt[x.status] || 0) + 1;
      }
      grp.innerHTML = '<td colspan="11"><b>' + esc(e.app) + "</b>" +
        ' <span class="muted">' + (cnt.RUNNING ? cnt.RUNNING + " running · " : "") +
        (cnt.READY ? cnt.READY + " ready · " : "") +
        (cnt.OCCUPIED ? cnt.OCCUPIED + " occupied" : "") + "</span></td>";
      tb.appendChild(grp);
    }
    const tr = el("tr", e.kind !== "implemented" ? "pm-dim" : "");
    const kindTag = e.kind === "implemented"
      ? "" : ' <span class="tag pm-kind-' + esc(e.kind) + '">' + esc(e.kind) + "</span>";
    const title = esc(e.description) +
      (e.detail ? "\n\nLast check: " + esc(e.detail) : "");
    tr.innerHTML =
      "<td>" + esc(e.app) + "</td>" +
      '<td class="mono" title="Assigned ' + esc(e.app) + ' port — ' + esc(e.service) + '">' + e.port + "</td>" +
      '<td title="' + esc(e.description) + '">' + esc(e.service) + kindTag + "</td>" +
      '<td class="pm-purpose" title="' + esc(e.description) + '">' + esc(e.description) + "</td>" +
      "<td class='mono'>" + esc(e.protocol) + "</td>" +
      '<td title="' + esc(e.detail || "") + '">' + portBadge(e.status) + "</td>" +
      '<td class="mono pm-proc" title="' + esc(e.process || "") + '">' +
      (e.process ? esc(e.process) : '<span class="muted">—</span>') + "</td>" +
      '<td class="mono">' + (e.pid ? e.pid : '<span class="muted">—</span>') + "</td>" +
      '<td class="mono" title="' + (e.status === "READY"
        ? "Configured binding address (port free)" : "Listening address of the socket") + '">' +
      esc(e.binding || "—") + "</td>" +
      '<td class="mono" title="' + esc(e.detail || "") + '">' + fmtTime(e.lastChecked) + "</td>" +
      '<td><button class="btn btn-sm" data-port-refresh="' + e.port + '" title="Re-check port ' + e.port + ' now">↻</button></td>';
    tb.appendChild(tr);
  }
  t.appendChild(tb);
  box.appendChild(t);
  if (!sorted.length) {
    box.appendChild(el("div", "empty", "No ports match the current filter."));
  }
}

function initPortsControls() {
  $("#ports-refresh").onclick = () => refreshPorts(true);
  $("#ports-filter-app").onchange = (e) => { portsFilterApp = e.target.value; renderPortsTable(); };
  $("#ports-filter-status").onchange = (e) => { portsFilterStatus = e.target.value; renderPortsTable(); };
  $("#ports-search").addEventListener("input", (e) => { portsSearch = e.target.value; renderPortsTable(); });
  $("#ports-table").addEventListener("click", (e) => {
    const h = e.target.closest("[data-port-sort]");
    if (h) {
      const k = h.dataset.portSort;
      if (portsSort.key === k) portsSort.dir = -portsSort.dir;
      else portsSort = { key: k, dir: 1 };
      renderPortsTable();
      return;
    }
    const pr = e.target.closest("[data-port-refresh]");
    if (pr) recheckPort(Number(pr.dataset.portRefresh));
  });
}

// ================================================================ DISCOVERY

async function openDiscover() {
  openModal("Discover Projects", "<div id='disc-form'><p class='muted'>Loading…</p></div>");
  const box = $("#disc-form");
  box.innerHTML = "";
  let discState = null;
  try {
    discState = await api("/api/discover", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ action: "state" }),
    });
  } catch (e) {
    box.innerHTML = '<p class="err-text">' + esc(e.message) + "</p>";
    return;
  }
  // scan root form
  const pathInput = document.createElement("input");
  pathInput.type = "text";
  pathInput.placeholder = "/parent/directory/with/repositories";
  pathInput.value = (discState.scanRoots && discState.scanRoots[0]) || "";
  const depthInput = document.createElement("input");
  depthInput.type = "number";
  depthInput.min = 1;
  depthInput.max = 6;
  depthInput.value = 4;
  depthInput.style.width = "70px";
  const exInput = document.createElement("input");
  exInput.type = "text";
  exInput.value = (discState.exclusions || []).join(", ");
  const f1 = document.createElement("div", "field");
  f1.appendChild(document.createElement("label")).textContent = "Scan root (user-selected; never the whole filesystem)";
  f1.appendChild(pathInput);
  const f2 = document.createElement("div", "field-row");
  const fd = document.createElement("div", "field");
  fd.appendChild(document.createElement("label")).textContent = "Max depth";
  fd.appendChild(depthInput);
  const fe = document.createElement("div", "field");
  fe.appendChild(document.createElement("label")).textContent = "Exclusions (comma-separated)";
  fe.appendChild(exInput);
  f2.append(fd, fe);
  const scanBtn = document.createElement("button");
  scanBtn.className = "btn btn-primary";
  scanBtn.textContent = "Scan";
  const remoteBtn = document.createElement("button");
  remoteBtn.className = "btn";
  remoteBtn.textContent = "Inspect remote URL…";
  box.append(f1, f2, scanBtn, remoteBtn);
  const results = document.createElement("div");
  results.style.marginTop = "10px";
  box.appendChild(results);
  const moved = document.createElement("div");
  box.appendChild(moved);

  const renderResults = (list) => {
    results.innerHTML = "";
    if (!list || !list.length) {
      results.appendChild(document.createElement("p")).className = "muted";
      results.lastChild.textContent = "No repositories found under the selected root.";
      return;
    }
    for (const d of list) {
      const item = document.createElement("div");
      item.className = "disc-item" + (d.ignored ? " ignored" : "");
      item.innerHTML = "<div class='d-name'>" + esc(d.name) + " " +
        '<span class="tag">' + esc(d.typeId) + "</span>" +
        '<span class="badge st-' + esc(d.confidence) + '">' + esc(d.confidence) + "</span>" +
        (d.git ? ' <span class="mono muted">' + esc(d.branch || "") + "</span>" : "") +
        (d.moduleCount ? ' <span class="muted">' + d.moduleCount + " modules</span>" : "") +
        (d.registered
          ? ' <span class="ok-text">registered as ' + esc(d.registeredId || "") + "</span>"
          : "") +
        (d.ignored ? ' <span class="muted">ignored</span>' : "") +
        "</div><div class='d-path'>" + esc(d.path) + "</div>";
      const row = document.createElement("div");
      row.className = "row";
      row.style.marginTop = "6px";
      if (!d.registered) {
        const reg = document.createElement("button");
        reg.className = "btn btn-sm btn-primary";
        reg.textContent = "Register";
        reg.onclick = () => registerDiscovered(d);
        row.appendChild(reg);
      }
      const inspect = document.createElement("button");
      inspect.className = "btn btn-sm";
      inspect.textContent = "Inspect";
      inspect.onclick = () => inspectDiscovered(d);
      row.appendChild(inspect);
      const ignore = document.createElement("button");
      ignore.className = "btn btn-sm";
      ignore.textContent = d.ignored ? "Unignore" : "Ignore";
      ignore.onclick = async () => {
        await api("/api/discover", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ action: "ignore", path: d.path, ignore: !d.ignored }),
        });
        d.ignored = !d.ignored;
        renderResults(list);
      };
      row.appendChild(ignore);
      item.appendChild(row);
      results.appendChild(item);
    }
  };

  const registerDiscovered = async (d) => {
    const id = d.name.replace(/[^A-Za-z0-9_-]/g, "-");
    try {
      const r = await api("/api/projects", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          id, name: d.name, repoDir: d.path,
          type: d.typeId === "custom" ? "custom" : d.typeId,
          detectionConfidence: "USER_CONFIRMED",
          launchConfigs: [],
          warnings: [],
        }),
      });
      if (!r.ok) throw new Error(r.error);
      toast("success", id, "Project registered from discovery");
      await refreshState();
    } catch (e) {
      toast("error", "Register failed", e.message);
    }
  };

  const inspectDiscovered = async (d) => {
    const r = await api("/api/detect", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ source: "local", path: d.path }),
    });
    const det = r.detection || {};
    openModal("Inspect — " + d.name,
      "<div class='kv'>" +
      "<div class='k'>Type</div><div class='v'>" + esc(det.typeId) + "</div>" +
      "<div class='k'>Confidence</div><div class='v'>" + esc(det.confidence) + "</div>" +
      "<div class='k'>Evidence</div><div class='v'>" + esc((det.evidence || []).join("; ")) + "</div>" +
      "<div class='k'>Modules</div><div class='v'>" + esc((d.modules || []).join(", ") || "—") + "</div>" +
      (det.hint ? "<div class='k'>Hint</div><div class='v'>" + esc(det.hint) + "</div>" : "") +
      "</div><p class='section-note'>Detection is read-only; nothing from the repository was executed.</p>");
  };

  const checkMoved = async () => {
    try {
      const m = await api("/api/discover", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "moved" }),
      });
      moved.innerHTML = "";
      for (const x of m || []) {
        const item = document.createElement("div");
        item.className = "disc-item";
        item.innerHTML = "<div class='d-name'>Registered project moved: <b>" + esc(x.projectId) + "</b></div>" +
          "<div class='d-path'>old: " + esc(x.oldPath) + "</div>" +
          "<div class='d-path'>candidate: " + esc(x.candidatePath) + "</div>";
        const remap = document.createElement("button");
        remap.className = "btn btn-sm";
        remap.textContent = "Remap to candidate";
        remap.onclick = async () => {
          await api("/api/discover", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ action: "remap", projectId: x.projectId, newPath: x.candidatePath }),
          });
          toast("success", x.projectId, "Path remapped");
          await refreshState();
        };
        item.appendChild(remap);
        moved.appendChild(item);
      }
    } catch (e) { /* ignore */ }
  };

  scanBtn.onclick = async () => {
    scanBtn.disabled = true;
    scanBtn.textContent = "Scanning…";
    try {
      const r = await api("/api/discover", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          action: "scan", path: pathInput.value,
          depth: Number(depthInput.value) || 4,
          exclusions: exInput.value.split(",").map((s) => s.trim()).filter(Boolean),
        }),
      });
      if (r.error) throw new Error(r.error);
      renderResults(r.found);
      await checkMoved();
    } catch (e) {
      results.innerHTML = '<p class="err-text">' + esc(e.message) + "</p>";
    } finally {
      scanBtn.disabled = false;
      scanBtn.textContent = "Scan";
    }
  };

  remoteBtn.onclick = async () => {
    const url = prompt("Git repository URL (HTTPS, SSH or provider URL):", "");
    if (!url) return;
    try {
      const r = await api("/api/discover", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ action: "remote", url }),
      });
      if (r.error) throw new Error(r.error);
      let html = "<div class='kv'>" +
        "<div class='k'>URL</div><div class='v'>" + esc(r.url) + "</div>" +
        "<div class='k'>Reachable</div><div class='v'>" + esc(String(!!r.reachable)) + "</div>" +
        "<div class='k'>Default branch</div><div class='v'>" + esc(r.defaultBranch || "—") + "</div>" +
        "<div class='k'>Branches</div><div class='v'>" + esc((r.branches || []).join(", ")) + "</div>" +
        "</div><p class='section-note'>Remote inspection used git ls-remote only — no clone was performed.</p>";
      openModal("Remote repository — " + url, html);
    } catch (e) {
      toast("error", "Remote inspection failed", e.message);
    }
  };
  await checkMoved();
}

document.addEventListener("click", (e) => {
  const navProject = e.target.closest("[data-nav-project]");
  if (navProject) {
    document.dispatchEvent(new CustomEvent("upm-navigate-project", { detail: { project: navProject.dataset.navProject } }));
  }
});

// polling fallback for the monitoring history (lightweight, on demand)
setInterval(() => {
  if (activeView !== "monitoring" || document.hidden || monPaused) return;
  api("/api/metrics/current").then((m) => {
    onMetricsSample(Object.assign(m, { timeMs: Date.now() }));
  }).catch(() => {});
}, 2000);

// automatic periodic refresh of the Port Manager inventory (server caches
// for 10 s, so this re-scans actual listeners roughly every 15 s)
setInterval(() => {
  if (activeView !== "ports" || document.hidden) return;
  refreshPorts(false);
}, 15000);
