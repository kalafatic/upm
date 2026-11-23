/* UPM — lightweight SVG time-series chart engine.
   Live mode, pause, time ranges, hover details, legend, CSV/SVG export. */
"use strict";

const Charts = (function () {
  const PALETTE = ["#2f6bd8", "#1f8a4c", "#c05b28", "#7c5bd6", "#2b7fbe", "#b0457e", "#a86a00"];

  class TimeChart {
    constructor(box, options) {
      this.box = box;
      this.opts = options || {};
      this.series = {}; // name -> {label, color, points: [{t, v}]}
      this.paused = false;
      this.rangeMs = this.opts.rangeMs || 5 * 60 * 1000;
      this.hover = null;
      this.build();
    }

    build() {
      this.box.innerHTML = "";
      if (this.opts.title) {
        const t = document.createElement("div");
        t.className = "chart-title";
        t.textContent = this.opts.title;
        this.box.appendChild(t);
      }
      this.svgHost = document.createElement("div");
      this.svgHost.style.height = (this.opts.height || 160) + "px";
      this.svgHost.style.position = "relative";
      this.box.appendChild(this.svgHost);
      this.legend = document.createElement("div");
      this.legend.className = "chart-legend";
      this.box.appendChild(this.legend);
      const row = document.createElement("div");
      row.className = "row";
      row.style.marginTop = "4px";
      if (this.opts.ranges) {
        this.rangeSel = document.createElement("select");
        for (const [label, ms] of Object.entries(this.opts.ranges)) {
          const o = document.createElement("option");
          o.value = ms;
          o.textContent = label;
          this.rangeSel.appendChild(o);
        }
        this.rangeSel.value = this.rangeMs;
        this.rangeSel.onchange = () => {
          this.rangeMs = Number(this.rangeSel.value);
          this.draw();
        };
        row.appendChild(this.rangeSel);
      }
      const pauseBtn = document.createElement("button");
      pauseBtn.className = "btn btn-sm";
      pauseBtn.textContent = "Pause";
      pauseBtn.onclick = () => {
        this.paused = !this.paused;
        pauseBtn.textContent = this.paused ? "Resume" : "Pause";
      };
      row.appendChild(pauseBtn);
      const csvBtn = document.createElement("button");
      csvBtn.className = "btn btn-sm";
      csvBtn.textContent = "CSV";
      csvBtn.onclick = () => GraphEngine.download(
        (this.opts.title || "chart").replace(/\W+/g, "-") + ".csv", this.csv(), "text/csv");
      row.appendChild(csvBtn);
      this.box.appendChild(row);
    }

    setSeries(name, label) {
      if (!this.series[name]) {
        this.series[name] = {
          label: label || name,
          color: PALETTE[Object.keys(this.series).length % PALETTE.length],
          points: [],
        };
      }
      return this.series[name];
    }

    addPoint(name, t, v) {
      const s = this.setSeries(name);
      s.points.push({ t, v: Number(v) });
      const cutoff = t - this.opts.maxRangeMs || t - 6 * 3600 * 1000;
      s.points = s.points.filter((p) => p.t >= cutoff);
      if (s.points.length > 2000) {
        s.points = s.points.slice(s.points.length - 2000);
      }
    }

    replaceSeries(name, label, points) {
      const s = this.setSeries(name, label);
      s.points = (points || []).map((p) => ({ t: Number(p.t), v: Number(p.v) }));
    }

    draw() {
      const host = this.svgHost;
      const W = host.clientWidth || 600;
      const H = this.opts.height || 160;
      const padL = 44;
      const padR = 8;
      const padT = 8;
      const padB = 18;
      let svg = host.querySelector("svg");
      if (!svg) {
        svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
        svg.setAttribute("width", "100%");
        svg.setAttribute("height", H);
        host.appendChild(svg);
      }
      svg.innerHTML = "";
      const now = Date.now();
      const t0 = now - this.rangeMs;
      let minV = Infinity;
      let maxV = -Infinity;
      const active = [];
      for (const s of Object.values(this.series)) {
        const pts = s.points.filter((p) => p.t >= t0);
        if (!pts.length) continue;
        active.push([s, pts]);
        for (const p of pts) {
          if (p.v < minV) minV = p.v;
          if (p.v > maxV) maxV = p.v;
        }
      }
      // grid + y labels
      const g = document.createElementNS("http://www.w3.org/2000/svg", "g");
      svg.appendChild(g);
      if (active.length && isFinite(minV)) {
        if (minV === maxV) {
          maxV = minV + 1;
        }
        const pad = (maxV - minV) * 0.08 || 1;
        minV = Math.max(0, minV - pad);
        maxV = maxV + pad;
        const steps = 4;
        for (let i = 0; i <= steps; i++) {
          const v = maxV - ((maxV - minV) * i) / steps;
          const y = padT + (H - padT - padB) * (i / steps);
          const line = document.createElementNS("http://www.w3.org/2000/svg", "line");
          line.setAttribute("x1", padL);
          line.setAttribute("x2", W - padR);
          line.setAttribute("y1", y);
          line.setAttribute("y2", y);
          line.setAttribute("stroke", "var(--border)");
          line.setAttribute("stroke-width", "0.5");
          g.appendChild(line);
          const lbl = document.createElementNS("http://www.w3.org/2000/svg", "text");
          lbl.setAttribute("x", padL - 5);
          lbl.setAttribute("y", y + 3);
          lbl.setAttribute("text-anchor", "end");
          lbl.setAttribute("font-size", "9");
          lbl.setAttribute("fill", "var(--muted)");
          lbl.textContent = this.opts.formatY ? this.opts.formatY(v) : Math.round(v * 10) / 10;
          g.appendChild(lbl);
        }
        // x time labels
        for (let i = 0; i <= 4; i++) {
          const t = t0 + (this.rangeMs * i) / 4;
          const x = padL + (W - padL - padR) * (i / 4);
          const lbl = document.createElementNS("http://www.w3.org/2000/svg", "text");
          lbl.setAttribute("x", x);
          lbl.setAttribute("y", H - 4);
          lbl.setAttribute("text-anchor", "middle");
          lbl.setAttribute("font-size", "9");
          lbl.setAttribute("fill", "var(--muted)");
          lbl.textContent = new Date(t).toLocaleTimeString();
          g.appendChild(lbl);
        }
        for (const [s, pts] of active) {
          const path = document.createElementNS("http://www.w3.org/2000/svg", "path");
          let d = "";
          for (let i = 0; i < pts.length; i++) {
            const x = padL + (W - padL - padR) * ((pts[i].t - t0) / this.rangeMs);
            const y = padT + (H - padT - padB) * (1 - (pts[i].v - minV) / (maxV - minV));
            d += (i === 0 ? "M" : "L") + x.toFixed(1) + "," + y.toFixed(1);
          }
          path.setAttribute("d", d);
          path.setAttribute("stroke", s.color);
          path.setAttribute("stroke-width", "1.5");
          path.setAttribute("fill", "none");
          g.appendChild(path);
          const last = pts[pts.length - 1];
          const lx = padL + (W - padL - padR) * ((last.t - t0) / this.rangeMs);
          const ly = padT + (H - padT - padB) * (1 - (last.v - minV) / (maxV - minV));
          const dot = document.createElementNS("http://www.w3.org/2000/svg", "circle");
          dot.setAttribute("cx", lx);
          dot.setAttribute("cy", ly);
          dot.setAttribute("r", 2.5);
          dot.setAttribute("fill", s.color);
          g.appendChild(dot);
        }
        // hover overlay
        const overlay = document.createElementNS("http://www.w3.org/2000/svg", "rect");
        overlay.setAttribute("x", padL);
        overlay.setAttribute("y", padT);
        overlay.setAttribute("width", W - padL - padR);
        overlay.setAttribute("height", H - padT - padB);
        overlay.setAttribute("fill", "transparent");
        g.appendChild(overlay);
        overlay.addEventListener("mousemove", (ev) => this.onHover(ev, t0, padL, W - padR, padT, H - padB, minV, maxV, active));
        overlay.addEventListener("mouseleave", () => this.clearHover());
      } else {
        const lbl = document.createElementNS("http://www.w3.org/2000/svg", "text");
        lbl.setAttribute("x", W / 2);
        lbl.setAttribute("y", H / 2);
        lbl.setAttribute("text-anchor", "middle");
        lbl.setAttribute("fill", "var(--muted)");
        lbl.setAttribute("font-size", "11");
        lbl.textContent = "no data for the selected range";
        g.appendChild(lbl);
      }
      // legend
      this.legend.innerHTML = "";
      for (const s of Object.values(this.series)) {
        const item = document.createElement("span");
        item.innerHTML = '<span class="sw" style="background:' + s.color + '"></span>' + escHtml(s.label);
        this.legend.appendChild(item);
      }
    }

    onHover(ev, t0, padL, w, padT, h, minV, maxV, active) {
      const rect = this.svgHost.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      const t = t0 + this.rangeMs * ((x - padL) / w);
      let tip = this.box.querySelector(".chart-tooltip");
      if (!tip) {
        tip = document.createElement("div");
        tip.className = "chart-tooltip";
        this.box.appendChild(tip);
      }
      let html = "<b>" + new Date(t).toLocaleTimeString() + "</b><br>";
      for (const [s, pts] of active) {
        let best = null;
        for (const p of pts) {
          if (p.t <= t && (!best || p.t > best.t)) best = p;
        }
        if (best) {
          html += '<span style="color:' + s.color + '">●</span> ' + escHtml(s.label) +
            ": " + (this.opts.formatY ? this.opts.formatY(best.v) : best.v) + "<br>";
        }
      }
      tip.innerHTML = html;
      tip.style.left = Math.min(rect.width - 160, x + 10) + "px";
      tip.style.top = Math.max(0, ev.clientY - rect.top - 40) + "px";
      tip.style.display = "block";
    }

    clearHover() {
      const tip = this.box.querySelector(".chart-tooltip");
      if (tip) tip.style.display = "none";
    }

    csv() {
      const keys = Object.keys(this.series);
      let out = "timestamp," + keys.join(",") + "\n";
      const byT = {};
      for (const [name, s] of Object.entries(this.series)) {
        for (const p of s.points) {
          (byT[p.t] = byT[p.t] || {})[name] = p.v;
        }
      }
      for (const t of Object.keys(byT).sort()) {
        out += t + "," + keys.map((k) => (byT[t][k] === undefined ? "" : byT[t][k])).join(",") + "\n";
      }
      return out;
    }
  }

  function escHtml(s) {
    return String(s == null ? "" : s).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));
  }

  return { TimeChart };
})();
