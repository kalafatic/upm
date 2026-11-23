/* UPM — lightweight SVG graph engine.
   Separates graph data/model from rendering and interaction; reused by all
   architecture perspectives. Zoom, pan, drag, select, multi-select, search,
   filter, details, layout persistence, export (SVG/PNG/JSON/CSV), print. */
"use strict";

const GraphEngine = (function () {
  const NODE_W = 150;
  const NODE_H = 44;
  const COLORS = {
    project: "#2f6bd8", workspace: "#44506b", process: "#1f8a4c", service: "#7c5bd6",
    application: "#c05b28", "http-endpoint": "#2b7fbe", endpoint: "#2b7fbe",
    repository: "#5c6a7e", module: "#8b6f2f", profile: "#556a8a", model: "#b0457e",
    "inference-runtime": "#8a4fc4", "llm-provider": "#2b7fbe", session: "#1f8a4c",
    device: "#5c6a7e", hardware: "#44506b", actor: "#44506b", operation: "#c05b28",
    placeholder: "#5c6a7e",
  };
  const STATUS_COLORS = {
    ready: "#1f8a4c", running: "#2b7fbe", active: "#2b7fbe", starting: "#2b7fbe",
    stopped: "#8b96a8", failed: "#c23b2e", stopping: "#a86a00", external: "#a86a00",
    listening: "#1f8a4c", reachable: "#1f8a4c", available: "#1f8a4c", unreachable: "#c23b2e",
    unknown: "#8b96a8", dirty: "#a86a00", clean: "#1f8a4c",
  };
  const EDGE_TYPES = ["DEPENDS_ON", "CALLS", "CREATES", "OWNS", "USES", "HOSTS",
    "RUNS_ON", "CONNECTS_TO", "LOADS", "INVOKES", "PROVIDES", "CONSUMES", "SPAWNS"];

  class Engine {
    constructor(container, options) {
      this.container = container;
      this.opts = options || {};
      this.svg = null;
      this.graph = { nodes: [], edges: [] };
      this.selected = new Set();
      this.filterTypes = new Set();
      this.filterProject = "";
      this.searchText = "";
      this.hiddenTypes = new Set();
      this.zoom = 1;
      this.panX = 0;
      this.panY = 0;
      this.nodePositions = {};
      this.highlight = null; // node id whose connections are highlighted
      this.onSelect = this.opts.onSelect || (() => {});
      this.dragging = null;
      this.dragOffset = null;
    }

    load(graph, savedLayout) {
      this.graph = graph;
      this.selected.clear();
      this.zoom = 1;
      this.panX = 0;
      this.panY = 0;
      // Restore saved positions or compute an initial layered layout.
      if (savedLayout && savedLayout.nodes) {
        this.nodePositions = JSON.parse(JSON.stringify(savedLayout.nodes));
      } else {
        this.nodePositions = {};
        this.autoLayout();
      }
      this.render();
    }

    autoLayout() {
      // Column-based layered layout by edge depth.
      const depth = {};
      const pass = (id, d) => {
        if (depth[id] === undefined || d > depth[id]) depth[id] = d;
        else if (depth[id] !== undefined && d <= depth[id]) return;
        for (const e of this.graph.edges) {
          if (e.from === id) pass(e.to, (depth[id] || 0) + 1);
        }
      };
      for (const n of this.graph.nodes) pass(n.id, depth[n.id] || 0);
      const cols = {};
      for (const [id, d] of Object.entries(depth)) {
        (cols[d] = cols[d] || []).push(id);
      }
      const colGap = NODE_W + 70;
      const rowGap = NODE_H + 26;
      for (const [d, ids] of Object.entries(cols)) {
        const col = Number(d);
        const total = ids.length * rowGap;
        ids.forEach((id, i) => {
          this.nodePositions[id] = {
            x: 40 + col * colGap,
            y: 40 + (i * rowGap - total / 2) + (this.graph.nodes.length * rowGap) / 4,
          };
        });
      }
    }

    visibleNodes() {
      return this.graph.nodes.filter((n) => {
        if (this.hiddenTypes.has(n.type)) return false;
        if (this.filterTypes.size && !this.filterTypes.has(n.type)) return false;
        if (this.filterProject && n.project && n.project !== this.filterProject) return false;
        if (this.searchText) {
          const hay = (n.label + " " + n.id + " " + (n.props && JSON.stringify(n.props))).toLowerCase();
          if (!hay.includes(this.searchText.toLowerCase())) return false;
        }
        return true;
      });
    }

    visibleEdges() {
      const ids = new Set(this.visibleNodes().map((n) => n.id));
      return this.graph.edges.filter((e) => ids.has(e.from) && ids.has(e.to));
    }

    render() {
      const box = this.container;
      box.innerHTML = "";
      const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
      svg.setAttribute("width", "100%");
      svg.setAttribute("height", "100%");
      this.svg = svg;
      box.appendChild(svg);
      this.draw();
    }

    draw() {
      const svg = this.svg;
      const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
      g.setAttribute("transform", "translate(" + this.panX + "," + this.panY + ") scale(" + this.zoom + ")");
      svg.appendChild(g);
      const edges = this.visibleEdges();
      const nodes = this.visibleNodes();
      const pos = this.nodePositions;
      const nodeById = {};
      for (const n of this.graph.nodes) nodeById[n.id] = n;

      for (const e of edges) {
        const from = pos[e.from];
        const to = pos[e.to];
        if (!from || !to) continue;
        const x1 = from.x + NODE_W / 2;
        const y1 = from.y + NODE_H / 2;
        const x2 = to.x + NODE_W / 2;
        const y2 = to.y + NODE_H / 2;
        const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
        const dx = Math.max(40, Math.abs(x2 - x1) / 2);
        const d = "M" + x1 + "," + y1 + " C" + (x1 + dx) + "," + y1 + " " + (x2 - dx) + "," + y2 + " " + x2 + "," + y2;
        path.setAttribute("d", d);
        path.setAttribute("class", "g-edge" + (this.selected.has("edge:" + e.from + ">" + e.to) ? " selected" : "")
          + (this.highlight && (e.from === this.highlight || e.to === this.highlight) ? " g-edge-highlight" : ""));
        if (e.classification === "INFERRED") {
          path.setAttribute("stroke-dasharray", "4 3");
        }
        path.dataset.from = e.from;
        path.dataset.to = e.to;
        path.addEventListener("click", (ev) => this.selectEdge(e, ev));
        g.appendChild(path);
        // arrowhead at target
        const angle = Math.atan2(y2 - y1, x2 - x1);
        const hx = x2 - 8 * Math.cos(angle);
        const hy = y2 - 8 * Math.sin(angle);
        const arrow = document.createElementNS("http://www.w3.org/2000/svg", "polygon");
        arrow.setAttribute("points",
          (hx + 7 * Math.cos(angle)) + "," + (hy + 7 * Math.sin(angle)) + " " +
          (hx + 5 * Math.cos(angle + 2.5)) + "," + (hy + 5 * Math.sin(angle + 2.5)) + " " +
          (hx + 5 * Math.cos(angle - 2.5)) + "," + (hy + 5 * Math.sin(angle - 2.5)));
        arrow.setAttribute("fill", "var(--faint)");
        g.appendChild(arrow);
        const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
        label.setAttribute("x", (x1 + x2) / 2);
        label.setAttribute("y", (y1 + y2) / 2 - 4);
        label.setAttribute("text-anchor", "middle");
        label.setAttribute("class", "g-edge-label");
        label.textContent = e.type;
        g.appendChild(label);
      }

      for (const n of nodes) {
        const p = pos[n.id];
        if (!p) continue;
        const group = document.createElementNS("http://www.w3.org/2000/svg", "g");
        group.setAttribute("class", "g-node" + (this.selected.has(n.id) ? " selected" : ""));
        group.setAttribute("transform", "translate(" + p.x + "," + p.y + ")");
        const rect = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        rect.setAttribute("width", NODE_W);
        rect.setAttribute("height", NODE_H);
        rect.setAttribute("class", "node-rect");
        group.appendChild(rect);
        const statusColor = STATUS_COLORS[String(n.status).toLowerCase()] || "var(--faint)";
        const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
        dot.setAttribute("cx", 12);
        dot.setAttribute("cy", NODE_H / 2);
        dot.setAttribute("r", 4);
        dot.setAttribute("fill", statusColor);
        group.appendChild(dot);
        const label = document.createElementNS("http://www.w3.org/2000/svg", "text");
        label.setAttribute("x", 22);
        label.setAttribute("y", 18);
        label.setAttribute("class", "g-node-label");
        label.textContent = n.label.length > 26 ? n.label.slice(0, 25) + "…" : n.label;
        group.appendChild(label);
        const sub = document.createElementNS("http://www.w3.org/2000/svg", "text");
        sub.setAttribute("x", 22);
        sub.setAttribute("y", 33);
        sub.setAttribute("class", "g-node-sublabel");
        const bits = [n.type, n.classification];
        if (n.props && n.props.pid) bits.push("pid " + n.props.pid);
        sub.textContent = bits.filter(Boolean).join(" · ");
        group.appendChild(sub);
        group.dataset.id = n.id;
        group.addEventListener("click", (ev) => this.selectNode(n, ev));
        group.addEventListener("dblclick", (ev) => {
          ev.stopPropagation();
          if (n.project) {
            const nav = new CustomEvent("upm-navigate-project", { detail: { project: n.project } });
            document.dispatchEvent(nav);
          }
        });
        // drag
        group.addEventListener("mousedown", (ev) => {
          if (ev.button !== 0) return;
          this.dragging = { id: n.id, startX: ev.clientX, startY: ev.clientY };
          ev.preventDefault();
        });
        g.appendChild(group);
      }
      // drag/pan handlers on the svg
      svg.onmousemove = (ev) => {
        if (this.dragging) {
          const d = this.dragging;
          const p = this.nodePositions[d.id];
          p.x += (ev.clientX - d.startX) / this.zoom;
          p.y += (ev.clientY - d.startY) / this.zoom;
          d.startX = ev.clientX;
          d.startY = ev.clientY;
          this.draw();
          return;
        }
        if (this.panning) {
          this.panX += ev.clientX - this.panning.x;
          this.panY += ev.clientY - this.panning.y;
          this.panning = { x: ev.clientX, y: ev.clientY };
          this.draw();
        }
      };
      svg.onmouseup = () => {
        this.dragging = null;
        this.panning = null;
      };
      svg.onmousedown = (ev) => {
        if (ev.target === svg && ev.button === 0) {
          this.panning = { x: ev.clientX, y: ev.clientY };
        }
      };
      svg.onwheel = (ev) => {
        ev.preventDefault();
        const factor = ev.deltaY < 0 ? 1.1 : 0.9;
        this.zoom = Math.max(0.25, Math.min(3, this.zoom * factor));
        this.draw();
      };
    }

    selectNode(n, ev) {
      if (ev.ctrlKey || ev.metaKey) {
        if (this.selected.has(n.id)) this.selected.delete(n.id);
        else this.selected.add(n.id);
      } else {
        this.selected.clear();
        this.selected.add(n.id);
      }
      this.onSelect(n);
      this.draw();
    }

    selectEdge(e, ev) {
      if (ev.ctrlKey || ev.metaKey) {
        const key = "edge:" + e.from + ">" + e.to;
        if (this.selected.has(key)) this.selected.delete(key);
        else this.selected.add(key);
      } else {
        this.selected.clear();
        this.selected.add("edge:" + e.from + ">" + e.to);
      }
      this.onSelect(e);
      this.draw();
    }

    setHighlight(nodeId) {
      this.highlight = this.highlight === nodeId ? null : nodeId;
      this.draw();
    }

    toggleType(type) {
      if (this.hiddenTypes.has(type)) this.hiddenTypes.delete(type);
      else this.hiddenTypes.add(type);
      this.draw();
    }

    fit() {
      const nodes = this.visibleNodes();
      if (!nodes.length) return;
      let minX = Infinity, minY = Infinity, maxX = -Infinity, maxY = -Infinity;
      for (const n of nodes) {
        const p = this.nodePositions[n.id];
        if (!p) continue;
        minX = Math.min(minX, p.x);
        minY = Math.min(minY, p.y);
        maxX = Math.max(maxX, p.x + NODE_W);
        maxY = Math.max(maxY, p.y + NODE_H);
      }
      const w = this.container.clientWidth || 900;
      const h = this.container.clientHeight || 480;
      const gx = Math.max(10, maxX - minX);
      const gy = Math.max(10, maxY - minY);
      this.zoom = Math.min(1.5, Math.max(0.3, Math.min(w / gx, h / gy) * 0.9));
      this.panX = (w - gx * this.zoom) / 2 - minX * this.zoom;
      this.panY = (h - gy * this.zoom) / 2 - minY * this.zoom;
      this.draw();
    }

    resetLayout() {
      this.autoLayout();
      this.fit();
    }

    layoutState() {
      return { nodes: this.nodePositions, zoom: this.zoom, pan: [this.panX, this.panY] };
    }

    exportSVG() {
      if (!this.svg) {
        this.render();
      }
      const clone = this.svg.cloneNode(true);
      const g = clone.querySelector("g");
      if (g) {
        g.removeAttribute("transform");
      }
      const rects = clone.querySelectorAll("rect");
      let maxX = 0, maxY = 0;
      for (const n of this.graph.nodes) {
        const p = this.nodePositions[n.id];
        if (p) {
          maxX = Math.max(maxX, p.x + NODE_W + 20);
          maxY = Math.max(maxY, p.y + NODE_H + 20);
        }
      }
      clone.setAttribute("width", maxX || 900);
      clone.setAttribute("height", maxY || 480);
      const bg = document.createElementNS("http://www.w3.org/2000/svg", "rect");
      bg.setAttribute("width", "100%");
      bg.setAttribute("height", "100%");
      bg.setAttribute("fill", "#ffffff");
      clone.insertBefore(bg, clone.firstChild);
      const style = document.createElementNS("http://www.w3.org/2000/svg", "style");
      style.textContent = ".node-rect{fill:#f7f9fb;stroke:#c3cad5}.g-node-label{font:600 11px Inter,sans-serif;fill:#1b2434}" +
        ".g-node-sublabel{font:9px Inter,sans-serif;fill:#5c6a7e}.g-edge{stroke:#8b96a8;stroke-width:1.2;fill:none}" +
        ".g-edge-label{font:8.5px monospace;fill:#5c6a7e;stroke:#fff;stroke-width:3px;paint-order:stroke}";
      clone.appendChild(style);
      return new XMLSerializer().serializeToString(clone);
    }

    exportPNG(callback) {
      const svg = this.exportSVG();
      const img = new Image();
      const blob = new Blob([svg], { type: "image/svg+xml;charset=utf-8" });
      const url = URL.createObjectURL(blob);
      img.onload = () => {
        const size = Math.min(4096, Math.max(800, img.width));
        const canvas = document.createElement("canvas");
        canvas.width = img.width * 2;
        canvas.height = img.height * 2;
        const ctx = canvas.getContext("2d");
        ctx.fillStyle = "#ffffff";
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        URL.revokeObjectURL(url);
        callback(canvas.toDataURL("image/png"));
      };
      img.src = url;
    }

    exportJSON() {
      return JSON.stringify(this.graph, null, 2);
    }

    exportCSV() {
      let out = "source,target,type,classification,evidence\n";
      for (const e of this.visibleEdges()) {
        out += [csv(e.from), csv(e.to), csv(e.type), csv(e.classification),
          csv((e.evidence || []).join("; "))].join(",") + "\n";
      }
      return out;
    }

    print() {
      const w = window.open("", "_blank");
      if (!w) return;
      w.document.write("<html><head><title>Architecture diagram</title><style>body{font-family:sans-serif}</style></head><body>"
        + this.exportSVG() + "</body></html>");
      w.document.close();
      w.print();
    }
  }

  function csv(s) {
    return '"' + String(s == null ? "" : s).replace(/"/g, '""') + '"';
  }

  return {
    Engine,
    EDGE_TYPES,
    download: (name, content, mime) => {
      const a = document.createElement("a");
      a.href = URL.createObjectURL(new Blob([content], { type: mime }));
      a.download = name;
      a.click();
      URL.revokeObjectURL(a.href);
    },
  };
})();
