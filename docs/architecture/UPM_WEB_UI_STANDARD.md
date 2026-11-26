# UPM Web UI Standard

**Authoritative reference implementation:** `web/` of the Universal Project Manager
(`index.html`, `app.css`, `app.js`, `charts.js`, `graph.js`).

This document is the design standard for every web interface of the EVO family
(EVO, ENR, EVO-Code, EVO-Studio, TRYST, UPM itself). It is extracted from the UPM
implementation — it does not invent a new design system. UPM is the benchmark;
when in doubt, look at the UPM sources, not at this document.

---

## 1. Visual identity

### 1.1 Design philosophy

- Professional, restrained, technically oriented.
- Neutral surfaces, subtle gray separators, dark typography, **one blue accent**.
- Color is used **only for state** (success, warning, error, info) and the accent.
- No gradients, no decorative shadows beyond one subtle level, no animated
  background effects.
- Dense desktop application feel: 13 px base font, compact paddings, one-pixel
  borders. This is a desktop tool rendered in a browser (embedded SWT Browser
  or standalone), not a marketing page.

### 1.2 Theme model

Two themes, driven by CSS variables. Light theme is defined in `:root`,
dark theme overrides under `[data-theme="dark"]`. The theme attribute is set
on the `<html>` element:

```html
<html lang="en" data-theme="dark">
```

Theme persistence: the current theme is part of the server-side application
state (`state.app.theme`); the server sends it in the initial snapshot and the
client applies `document.documentElement.dataset.theme`. A header **Theme**
button toggles and persists it (`PUT /api/settings {theme}`).

### 1.3 Design tokens (canonical values)

**Light theme (`:root`):**

| Token | Value | Role |
|---|---|---|
| `--bg` | `#f4f6f9` | application background |
| `--bg2` | `#eceff4` | hover/alternate background |
| `--panel` | `#ffffff` | panel/surface background |
| `--panel2` | `#f7f9fb` | table header / secondary surface |
| `--border` | `#d7dce4` | standard separator |
| `--border-strong` | `#c3cad5` | control borders |
| `--text` | `#1b2434` | primary text |
| `--muted` | `#5c6a7e` | secondary text |
| `--faint` | `#8b96a8` | tertiary text |
| `--accent` | `#2f6bd8` | primary accent (blue) |
| `--accent-soft` | `#e8effa` | accent-tinted background |
| `--ok` | `#1f8a4c` | success |
| `--ok-soft` | `#e7f4ec` | success background |
| `--warn` | `#a86a00` | warning |
| `--warn-soft` | `#fdf3e2` | warning background |
| `--err` | `#c23b2e` | error |
| `--err-soft` | `#fbeceb` | error background |
| `--info` | `#2b7fbe` | info |
| `--info-soft` | `#e7f1f9` | info background |
| `--sel` | `#e8effa` | selected row/item |
| `--shadow` | `0 1px 2px rgba(16, 24, 40, .06)` | only shadow level |

**Dark theme (`[data-theme="dark"]`):**

| Token | Value |
|---|---|
| `--bg` | `#12161f` |
| `--bg2` | `#181d29` |
| `--panel` | `#1c2230` |
| `--panel2` | `#232b3c` |
| `--border` | `#2e394c` |
| `--border-strong` | `#3c4a61` |
| `--text` | `#e7ebf3` |
| `--muted` | `#93a0b4` |
| `--faint` | `#6b7789` |
| `--accent` | `#5b8feb` |
| `--accent-soft` | `#24344e` |
| `--ok` | `#46c58a` |
| `--ok-soft` | `#1d3a2c` |
| `--warn` | `#e2ad4e` |
| `--warn-soft` | `#3d3320` |
| `--err` | `#ef7a6f` |
| `--err-soft` | `#432422` |
| `--info` | `#62b6ea` |
| `--info-soft` | `#1f3a4d` |
| `--sel` | `#26334c` |
| `--shadow` | `0 1px 2px rgba(0, 0, 0, .3)` |

### 1.4 Typography

- Sans: `"Inter", "Segoe UI", "DejaVu Sans", system-ui, sans-serif`.
- Mono: `"JetBrains Mono", "DejaVu Sans Mono", "Liberation Mono", monospace`
  (token `--mono`).
- Base font size **13 px**, line-height **1.45**.
- Application name in header: 600 weight, 14.5 px.
- Panel titles: 600 weight, 13 px.
- Table headers: 600 weight, **11 px, uppercase**, letter-spacing .3 px.
- Widget/metric headings: 10.5 px uppercase, letter-spacing .5 px, muted.
- No webfont loading is required; system stacks are authoritative. Inter is
  used when installed.

### 1.5 Spacing scale

| Use | Value |
|---|---|
| page padding (main) | `14px 16px 40px`, content max-width 1560 px, centered |
| header padding | `7px 16px` |
| panel padding | `12px 14px` |
| inter-panel gap | `12px` |
| control padding (btn) | `5px 10px` |
| small button padding | `2px 8px` |
| field gap | `10px` |
| grid gap | `12px` |

### 1.6 Geometry

- Border radius: panels/widgets **6 px**, buttons/inputs/toasts **5 px**,
  badges/tags **4 px**, brand mark 2 px.
- Borders: `1px solid var(--border)` for surfaces, `var(--border-strong)` for
  interactive controls.
- Shadows: only `--shadow` (one subtle level); modals additionally use
  `0 8px 30px rgba(16,24,40,.18)`.

### 1.7 Interaction states

- Hover on buttons: border and text become `--accent` (primary button darkens
  to `#2559ba`).
- Hover on clickable rows/items: background `--sel`, cursor pointer.
- Focus: `outline: 2px solid var(--accent); outline-offset: 1px`
  (`:focus-visible` for buttons; inputs use accent border + `--accent-soft`
  outline on focus).
- Disabled: `opacity: .45; cursor: not-allowed`.
- Active nav tab: `--accent-soft` background, `--accent` text, 600 weight.
- Active tab (panel tabs): `--accent` text + 2 px `--accent` bottom border.

---

## 2. Application layout

### 2.1 Header (`#topbar`)

Sticky top bar, one per application:

```
[■ brand-mark] Brand Name   workspace-sub  |  Dashboard  Projects ...  |  Action Buttons  Theme  🔔
```

- `position: sticky; top: 0; z-index: 50`, background `--panel`,
  border-bottom `1px solid var(--border)`, padding `7px 16px`, flex row,
  gap 12 px.
- Brand: 9×9 px accent square (radius 2 px) + brand name (600, 14.5 px) +
  optional sub-context label in mono 11 px muted (e.g. workspace name).
- Navigation: pill buttons with transparent background, muted text,
  radius 5 px; hover = text/bg2; active = accent-soft/accent/600.
- Actions area right-aligned (`margin-left: auto`): normal `.btn` buttons +
  theme toggle + notifications icon button with red count badge.

### 2.2 Navigation

- Single-level, top navigation inside the header. View switching is in-page
  (SPA): `.view` sections toggled by `.active`; no page reloads, no URL
  routing required.
- The active view indicator is the `.view-btn.active` state described above.
- If an application genuinely needs a sidebar or second-level navigation
  (e.g. file trees), it must use the same visual language: muted items,
  `--sel` hover, accent active state, 1 px borders.

### 2.3 Content area (`main`)

- `padding: 14px 16px 40px; max-width: 1560px; margin: 0 auto`.
- Content is organized in `.panel` blocks (white surface, 1 px border,
  6 px radius, `--shadow`), separated by 12 px.
- Two-column layouts: `grid-template-columns: 300px 1fr` (list → detail),
  320 px for project lists.

### 2.4 Toolbars

- Toolbar = flex row with 8 px gap, `flex-wrap: wrap`, placed either inside a
  panel above the content or as the panel title's right-aligned action group.
- Toolbar controls are `.btn .btn-sm` (11 px), inputs are compact.

### 2.5 Footer / status

- No permanent footer. Status information lives in the header (sub-labels),
  panel titles (muted counters, e.g. "Projects (12)", "last sample 14:03:22")
  and toasts/notifications.

---

## 3. Component specifications

All components derive from the tokens; none may hardcode colors.

### 3.1 Buttons

| Variant | Spec |
|---|---|
| `.btn` | panel bg, text color, `1px solid var(--border-strong)`, radius 5 px, padding 5×10, font 12 px; hover: accent border + accent text; disabled: opacity .45 |
| `.btn-primary` | accent bg, white text; hover `#2559ba` |
| `.btn-danger` | panel bg, `--err` border and text; hover `--err-soft` bg |
| `.btn-sm` | padding 2×8, font 11 px |
| `.icon-btn` | transparent bg, `--border` border, radius 5 px, 13 px |

No dead buttons: every rendered button performs an action or is disabled.

### 3.2 Inputs

- `input/select/textarea`: panel bg, text color, `1px solid var(--border-strong)`,
  radius 4 px, padding 5×8, font 12 px, width 100 %.
- Focus: `--accent` border + `outline: 2px solid var(--accent-soft)`.
- `textarea` uses the mono font.
- Labels: block, 11 px, muted, 3 px bottom margin (`.field`).
- Checkboxes: flex row, 6 px gap, 12 px font.

### 3.3 Panels

- `.panel`: `--panel` bg, `1px solid var(--border)`, radius 6 px,
  padding 12×14, margin-bottom 12 px, `box-shadow: var(--shadow)`.
- `.panel-title`: 600 weight, 13 px, flex row with 8 px gap; muted counters
  and right-aligned `.btn-sm` actions via `.spacer`.

### 3.4 Tables (`.tbl`)

- `width: 100%; border-collapse: collapse; font-size: 12px`.
- `th`: left-aligned, 11 px, uppercase, letter-spacing .3 px, muted color,
  600 weight, padding 5×8, bottom border `--border-strong`, background
  `--panel2`.
- `td`: padding 6×8, bottom border `--border`, vertical-align top.
- Row hover/selected: background `--sel`; hover rows get `cursor: pointer`
  only when clickable (`.clickable`).
- Mono cells use `.mono` class (11 px).
- Empty table: `.empty` centered muted message with actionable hint.

### 3.5 Badges and tags

- `.badge`: inline-block, padding 1×7, radius 4 px, font 10.5 px, 600 weight,
  1 px border, `white-space: nowrap`. State colors only:

| State classes | Colors |
|---|---|
| `st-STOPPED`, `st-UNKNOWN`, `st-CANCELLED`, `st-UNSUPPORTED`, `st-INFERRED` | muted text, `--border-strong` border, `--panel2` bg |
| `st-READY`, `st-SUCCEEDED`, `st-OK`, `st-DETECTED`, `st-USER_CONFIRMED`, `st-VERIFIED_*` | ok |
| `st-RUNNING`, `st-STARTING`, `st-QUEUED`, `st-ACTIVE`, `st-PROPOSED` | info |
| `st-STOPPING`, `st-EXTERNAL` | warn |
| `st-FAILED` | err |

  (ok/info/warn/err = `--ok/--info/--warn/--err` text + border on the
  corresponding `-soft` background.)
- `.tag`: 10.5 px, padding 0×6, radius 3 px, panel2 bg, muted text,
  `--border` border — for type/label chips, never for state.

### 3.6 Tabs

- `.tabs` container: flex, 2 px gap, bottom border `--border-strong`,
  10 px bottom margin.
- `.tab`: transparent bg, no border, muted text, padding 7×12, 12.5 px,
  `border-bottom: 2px solid transparent`; active: accent text + accent
  bottom border + 600 weight.

### 3.7 Lists and cards

- List item (project/operation): `--panel` bg, 1 px `--border`, radius 5 px,
  6–8 px margins/paddings, hover accent border; active item accent border +
  `--accent-soft` bg.
- Card grids: `repeat(auto-fill, minmax(260px, 1fr))`, 10 px gap.
- Cards: same surface style; current/ok state uses `--ok` border.

### 3.8 Dialogs (modals)

- Backdrop: `fixed; inset: 0; rgba(16,24,40,.35); z-index: 300`,
  flex-centered.
- Modal: `--panel` bg, `--border-strong` border, radius 7 px,
  `width: min(860px, 92vw)`, `max-height: 88vh`, flex column,
  `box-shadow: 0 8px 30px rgba(16,24,40,.18)`.
- Title bar: 600 weight, padding 12×16, bottom border.
- Body: padding 14×16, overflow auto.
- Actions: right-aligned flex row, top border, padding 10×16;
  **Cancel left of the primary action; destructive action visually distinct
  (`.btn-danger`)**.
- Escape closes; clicking the backdrop closes; confirm dialogs are 520 px
  wide and state the consequence of the action before the confirm button.

### 3.9 Notifications (toasts)

- Fixed top-right stack (`top: 52px; right: 16px`), 380 px wide, 8 px gap,
  z-index 200.
- Toast: panel bg, `--border-strong` border, radius 5 px, 3 px left border
  colored by level (`--info`/`--ok`/`--warn`/`--err`), padding 9×12,
  12 px, slide-in animation, auto-removed after 8 s.
- Notification panel (bell): 420 px, fixed under the header, list of items
  with title/detail/next-step, red count badge on the bell.

### 3.10 Progress and loading

- `.progress`: height 7 px, `--bg2` bg, radius 3 px, `--border` border;
  fill = `--accent`, width transition .4 s.
- `.progress.indeterminate`: 40 % fill sliding left-to-right (1.4 s loop).
- Inline loading = `.muted` "Loading…" text; long operations report phase
  text next to the progress bar.

### 3.11 Logs, code, errors

- `.log-view`: mono 11 px, line-height 1.5, light `#fbfcfe` bg (dark
  `#10151f`), text `#22304a` (dark `#c8d3e8`), 1 px border, radius 5 px,
  padding 8 px, pre-wrap, max-height variants `.small` (160 px) /
  `.large` (60 vh).
- Error text: `--err`; error detail boxes: mono log box tinted red.
- `.empty`: centered muted 14 px padding block; always suggests the next
  action.

### 3.12 Status indicators

- Colored text spans: `.ok-text`, `.err-text`, `.warn-text` (state only).
- Metric cards: uppercase muted title (10.5 px), mono value 22 px 600,
  muted sub-line 11 px.
- Charts: `--panel` boxes with 1 px border, radius 5 px, uppercase muted
  title, legend of small color swatches, mono tooltip on hover.

---

## 4. Interaction conventions

1. **SPA navigation.** View switching never reloads the page; it toggles
   sections. Backend state is the single source of truth; the UI re-renders
   from snapshots/events.
2. **Server-push + polling.** Live state via SSE (`/api/events`) with an
   initial REST snapshot (`/api/state`); metrics via periodic samples.
   Reconnect automatically; degrade gracefully with toasts on failure.
3. **Optimistic feedback.** Every mutation confirms with a toast
   ("Build started", "Project updated") and errors with an
   `error` toast containing the server message. Never silent failure.
4. **Confirmation for destructive/irreversible actions** (delete, stop-all,
   push, branch switch with dirty tree): confirm dialog states exactly what
   will happen, what is preserved, and what cannot be undone.
5. **Keyboard:** Escape closes dialogs; focus-visible outlines everywhere;
   buttons are real `<button>` elements; data-attribute event delegation
   (`data-act`, `data-open-*`) instead of inline `onclick`.
6. **Tooltips:** `title` attributes on icon-only controls.
7. **XSS safety:** all dynamic strings pass through an escape function
   (`esc()`) before `innerHTML`; text content uses `textContent`.
8. **No dead controls.** Every button either performs an action, is disabled,
   or is removed from the DOM.
9. **Async feedback.** Long operations show progress bars with phase/module
   detail; cancellable operations expose a Cancel action.
10. **Print/export:** print CSS hides chrome (header, toasts, buttons);
    export actions are real (SVG/PNG/JSON/CSV downloads).

---

## 5. JavaScript architecture conventions

- Vanilla JS only (`"use strict"`), no frameworks, no build step, no CDN.
- DOM helpers: `$` (querySelector) and `el(tag, cls, text)` factories.
- One shared `api(path, opts)` fetch wrapper (JSON-first, blob fallback,
  error extraction from `{error}` envelope).
- Modules: separate files loaded in dependency order
  (`graph.js`, `charts.js`, `app.js`), each a self-contained feature.
- State: single mutable `state` object, updated by snapshot/SSE; render
  functions are idempotent and cheap (re-render on state change).
- Event delegation on `document` using `data-*` attributes.
- Booting: fetch initial snapshot, apply, connect SSE, start polling loops
  for live data; show a clear error page if the backend is unreachable.

---

## 6. Responsive behavior

- Desktop-first professional layout: 1920×1080 and 1366×768 are the target
  sizes; panels must not overlap and tables must not break.
- Breakpoint 1100 px: dashboard grid 3→2 columns, two-column layouts stack
  to 1 column, header action buttons collapse (only icon buttons remain).
- Rules: no horizontal overflow; dialogs sized with `min(width, 92vw)`;
  no fixed-width content wider than the viewport.
- Tablet-sized viewports keep the layout usable (stacked panels, scrollable
  tables).

---

## 7. Compatibility requirements (all EVO family apps)

- Must work standalone in modern browsers (Chrome/Chromium, Firefox).
- Must work in the embedded **SWT Browser** (Chromium on Linux/Windows):
  avoid web platform features unavailable there; keep JS ES2017-compatible
  enough for the embedded engine; no Service Workers, no ES modules required.
- Resources load both from the app's embedded HTTP server and via `file:`
  where applicable (relative paths preferred).
- No external network dependencies for fonts/scripts.

---

## 8. How applications map to this standard

- **UPM** — the reference; do not redesign.
- **EVO** — keeps its workstation layout (chat, forge canvas, develop diff),
  adopts these tokens, header/nav, buttons, tables, badges, dialogs, toasts.
- **EVO-Code** — keeps the IDE shell (explorer/editor/console), adopts tokens,
  both themes, UPM-style topbar chrome and dialogs.
- **EVO-Studio** — keeps the Web IDE layout, adopts tokens, both themes.
- **ENR** — keeps the control-center pages, adopts tokens, both themes.
- **TRYST** — keeps admin/review/studio pages, adopts tokens, both themes.

Each application keeps its own purpose, endpoints, workflows and business
logic; only the visual language and interaction conventions are unified.
