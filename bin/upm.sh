#!/usr/bin/env bash
# =============================================================================
# Universal Project Manager — CLI. A thin client of the backend API; the
# backend is started automatically when needed.
# =============================================================================
set -u

# Keep in sync with upm.App.version.
VERSION="2.6.11"

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CONFIG="${UPM_CONFIG:-$APP_DIR/config/application.jsonc}"
JAVA_BIN="${UPM_JAVA:-/usr/lib/jvm/java-25-openjdk-amd64/bin/java}"
JAR="$APP_DIR/universal-project-manager.jar"
LOG_FILE="$APP_DIR/logs/upm.log"
API=""
PORT=""

info()  { echo -e "\033[36m$*\033[0m"; }
ok()    { echo -e "\033[32m$*\033[0m"; }
warn()  { echo -e "\033[33m$*\033[0m"; }
err()   { echo -e "\033[31m$*\033[0m"; }

resolve_port() {
  PORT="$(sed -n 's/.*"port"[[:space:]]*:[[:space:]]*\([0-9]*\).*/\1/p' "$CONFIG" | head -1)"
  [[ -z "$PORT" ]] && PORT=29080
  API="http://127.0.0.1:$PORT"
}

daemon_alive() {
  curl -s -m 2 "$API/api/health" >/dev/null 2>&1
}

backend_start() {
  resolve_port
  if daemon_alive; then return 0; fi
  info "Starting Universal Project Manager..."
  mkdir -p "$APP_DIR/logs"
  if [[ ! -f "$JAR" ]]; then
    err "universal-project-manager.jar not found. Run build.sh."
    exit 1
  fi
  ( setsid nohup "$JAVA_BIN" -jar "$JAR" "$CONFIG" >>"$LOG_FILE" 2>&1 </dev/null & )
  for _ in $(seq 1 60); do
    if daemon_alive; then ok "Backend ready: $API"; return 0; fi
    sleep 0.5
  done
  err "Backend failed to start. Log: $LOG_FILE"
  exit 1
}

backend_stop() {
  resolve_port
  if ! daemon_alive; then info "Backend is not running."; return 0; fi
  info "Stopping backend..."
  curl -s -m 3 -X POST "$API/api/shutdown" >/dev/null 2>&1 || true
  for _ in $(seq 1 20); do
    if ! daemon_alive; then ok "Stopped."; return 0; fi
    sleep 0.3
  done
  warn "Backend did not stop within 6s."
}

api_call() {
  local method="$1" path="$2" body="${3:-}"
  curl -s -m 900 -X "$method" "$API$path" \
    ${body:+-H "Content-Type: application/json" -d "$body"}
}

wait_op() {
  local op_id="$1" out="" status="" phase="" task="" last=""
  while true; do
    out="$(api_call GET "/api/ops/$op_id")"
    status="$(echo "$out" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)"
    [[ "$status" == "SUCCEEDED" || "$status" == "FAILED" || "$status" == "CANCELLED" || "$status" == "INTERRUPTED" ]] && break
    phase="$(echo "$out" | sed -n 's/.*"phase":"\([^"]*\)".*/\1/p' | head -1)"
    task="$(echo "$out" | sed -n 's/.*"currentModule":"\([^"]*\)".*/\1/p' | head -1)"
    if [[ "$last" != "$phase|$task" ]]; then
      last="$phase|$task"
      printf "\r\033[K  %s%s" "$phase" "${task:+ — $task}"
    fi
    sleep 2
  done
  printf "\r\033[K"
  echo "$out"
}

print_op_result() {
  local out="$1" status summary error
  status="$(echo "$out" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)"
  summary="$(echo "$out" | sed -n 's/.*"summary":"\([^"]*\)".*/\1/p' | head -1)"
  error="$(echo "$out" | sed -n 's/.*"error":"\([^"]*\)".*/\1/p' | head -1)"
  case "$status" in
    SUCCEEDED) ok "  $summary" ;;
    *) err "  $status: $summary"; [[ -n "$error" ]] && echo "$error" | head -30 ;;
  esac
}

usage() {
  cat <<EOF
UPM — Universal Project Manager CLI

Usage: upm <command> [options]          (a leading '-'/'--' is also accepted)

Server & service:
  -health                       Control-center health + lifecycle state
  -ports                        Port inventory (EVO family + live listeners)
  -version                      Print the UPM version
  -service <cmd> [--system]     systemd service: status|start|stop|restart|install|uninstall
  shutdown                      Stop the backend

Workspace:
  list                          List registered projects
  status                        Runtime + build/test status
  doctor                        Workspace diagnostics
  report                        Aggregated build/test/report matrix
  gui                           Open the interface in the browser

Projects (use 'all' for every project):
  build|clean-build|update|test|package|verify|install <project|all>
  run|start <project> [launchConfig] [port]
  stop <project|all>            Stop a runtime
  restart <project>             Restart a runtime
  logs <project>                Runtime log
  diagnostics <project>         Project diagnostics

Git:
  git-status <project>          Branch, HEAD and changed files
  git-fetch|git-pull <project>
  git-push <project>            Requires explicit confirmation (--yes)

Workspaces and profiles:
  ws-list                       List workspaces
  ws-switch <id>                Switch the active workspace
  ws-create <id> [name]
  ws-export <id> [file]         Export a portable configuration package
  profile-launch <id>           Launch a profile (dependency-aware order)
  profile-stop <id>             Stop a profile's projects

Global:
  launch-all|start-all          Launch all projects (dependency-aware order)
  stop-all                      Stop all projects
  restart-all                   Stop all, then launch all
EOF
}

main() {
  local cmd="${1:-}"
  [[ -z "$cmd" || "$cmd" == "help" || "$cmd" == "-h" || "$cmd" == "--help" ]] && { usage; exit 0; }

  # Accept the `upm -command` namespace as well as `upm command`.
  while [[ "$cmd" == -* && "$cmd" != "-" ]]; do cmd="${cmd#-}"; done

  resolve_port

  case "$cmd" in
    shutdown|stop-backend) backend_stop; exit $? ;;
    version) echo "UPM $VERSION"; exit 0 ;;
    service) shift; "$APP_DIR/bin/upm-service.sh" "$@"; exit $? ;;
    health)
      if daemon_alive; then
        api_call GET "/api/server" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("state:        {}".format(d.get("state")))
print("pid:          {}".format(d.get("pid")))
print("bind:         {}:{}".format(d.get("host"), d.get("boundPort") or d.get("port")))
print("configured:   {}".format(d.get("configuredPort")))
print("startupMs:    {}".format(d.get("startupMs")))
print("version:      {}".format(d.get("version")))
if d.get("cause"): print("port cause:   {}".format(d["cause"]))
' 2>/dev/null
        exit 0
      else
        err "UPM backend is not running."
        exit 1
      fi
      ;;
  esac

  if ! daemon_alive; then backend_start; fi

  case "$cmd" in
    list)
      api_call GET "/api/projects" | python3 -c '
import json,sys
for p in json.load(sys.stdin):
    g=p.get("git") or {}
    print("{:<8} {}".format(p["id"], p["name"]))
    print("         type: {} ({})  repo: {}  git: {}".format(
        p["type"], p["detectionConfidence"], p["repoDir"], g.get("branch") or "-"))
' 2>/dev/null
      ;;
    status)
      api_call GET "/api/projects" | python3 -c '
import json,sys
for p in json.load(sys.stdin):
    b=p.get("lastBuild") or {}; t=p.get("lastTest") or {}
    bs=b.get("status","-"); ts=t.get("status","-")
    pidv = p.get("pid", -1)
    pd = str(pidv) if pidv and pidv > 0 else "-"
    print("  {:<8} runtime={:<9} pid={:<7} build={:<8} tests={:<8}".format(
        p["id"], p["runtimeState"], pd, bs, ts))
    if p.get("lastError"): print("           lastError: {}".format(p["lastError"][:140]))
' 2>/dev/null
      ;;
    doctor|diagnostics)
      local target="${2:-workspace}"
      if [[ "$target" == "workspace" ]]; then
        api_call GET "/api/diagnostics"
      else
        api_call GET "/api/projects/$target/diagnostics"
      fi | python3 -c '
import json,sys
for c in json.load(sys.stdin):
    print(("OK  " if c["status"]=="OK" else "FAIL"), c["label"], ("— "+c["detail"] if c.get("detail") else ""))
' 2>/dev/null
      ;;
    report)
      api_call GET "/api/report" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("{:<9} {:<10} {:<10} {:<10} {:<10}".format("Project","Build","Update","Tests","Runtime"))
for p in d["projects"]:
    b=(p["build"] or {}).get("status") or "-"; u=(p["update"] or {}).get("status") or "-"
    t=(p["test"] or {}).get("status") or "-"
    print("{:<9} {:<10} {:<10} {:<10} {:<10}".format(p["project"],b,u,t,p["runtimeState"]))
' 2>/dev/null
      ;;
    ports)
      api_call GET "/api/ports/inventory" | python3 -c '
import json,sys
d=json.load(sys.stdin)
cc=d.get("controlCenter") or {}
print("UPM: {}:{} (pid {})".format(cc.get("host"), cc.get("port"), cc.get("pid")))
s=d.get("summary") or {}
print("summary: {} running, {} ready, {} occupied".format(s.get("running"), s.get("ready"), s.get("occupied")))
print("{:<8} {:>6} {:<8} {:<12} {:<20}".format("App","Port","Status","Service","Process"))
for e in d.get("entries") or []:
    print("{:<8} {:>6} {:<8} {:<12} {:<20}".format(
        e.get("app"), e.get("port"), e.get("status"), e.get("service"),
        (e.get("process") or "")[:20]))
' 2>/dev/null
      ;;
    build|clean-build|update|test|package|verify|install)
      local project="${2:-}"
      [[ -z "$project" ]] && { err "Usage: upm.sh $cmd <project|all>"; exit 1; }
      local op_type="$cmd"
      if [[ "$project" == "all" ]]; then
        local results=()
        for pid in $(api_call GET "/api/projects" | sed -n 's/.*"id":"\([A-Za-z][A-Za-z0-9_-]*\)".*/\1/p'); do
          info "== $op_type $pid =="
          local out opid
          out="$(api_call POST "/api/ops" "{\"project\":\"$pid\",\"type\":\"$op_type\"}")"
          opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
          if [[ -z "$opid" ]]; then err "  $(echo "$out" | sed 's/.*"error":"\([^"]*\)".*/\1/')"; results+=("$pid: FAILED"); continue; fi
          out="$(wait_op "$opid")"
          print_op_result "$out"
          results+=("$pid: $(echo "$out" | sed -n 's/.*"status":"\([^"]*\)".*/\1/p' | head -1)")
        done
        info "== summary =="
        printf '  %s\n' "${results[@]}"
      else
        local out opid
        out="$(api_call POST "/api/ops" "{\"project\":\"$project\",\"type\":\"$op_type\"}")"
        opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
        [[ -z "$opid" ]] && { err "$(echo "$out" | sed 's/.*"error":"\([^"]*\)".*/\1/')"; exit 1; }
        out="$(wait_op "$opid")"
        print_op_result "$out"
        [[ "$out" == *'"SUCCEEDED"'* ]] || exit 1
      fi
      ;;
    run|start)
      local project="${2:-}" lc="${3:-}" port="${4:-}"
      [[ -z "$project" ]] && { err "Usage: upm.sh run <project> [launchConfig] [port]"; exit 1; }
      local out opid body
      body="{\"project\":\"$project\",\"type\":\"run\""
      [[ -n "$lc" ]] && body="$body,\"launchConfig\":\"$lc\""
      [[ -n "$port" ]] && body="$body,\"port\":$port"
      body="$body}"
      out="$(api_call POST "/api/ops" "$body")"
      opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
      [[ -z "$opid" ]] && { err "$(echo "$out" | sed 's/.*"error":"\([^"]*\)".*/\1/')"; exit 1; }
      out="$(wait_op "$opid")"
      print_op_result "$out"
      [[ "$out" == *'"SUCCEEDED"'* ]] || exit 1
      ;;
    stop)
      local project="${2:-}"
      [[ -z "$project" ]] && { err "Usage: upm.sh stop <project|all>"; exit 1; }
      if [[ "$project" == "all" ]]; then
        out="$(api_call POST "/api/ops" '{"project":"workspace","type":"stopAll"}')"
        opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
        out="$(wait_op "$opid")"
        print_op_result "$out"
      else
        api_call POST "/api/projects/$project/stop" '{}' | python3 -c '
import json,sys
d=json.load(sys.stdin)
print("Stopped." if d.get("ok") else "ERROR: "+d.get("error",""))
' 2>/dev/null
      fi
      ;;
    restart)
      local project="${2:-}"
      [[ -z "$project" ]] && { err "Usage: upm.sh restart <project>"; exit 1; }
      out="$(api_call POST "/api/ops" "{\"project\":\"$project\",\"type\":\"restart\"}")"
      opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
      out="$(wait_op "$opid")"
      print_op_result "$out"
      ;;
    launch-all|start-all|stop-all|restart-all)
      local gtype="launchAll"
      [[ "$cmd" == "stop-all" ]] && gtype="stopAll"
      [[ "$cmd" == "restart-all" ]] && gtype="restartAll"
      out="$(api_call POST "/api/ops" "{\"project\":\"workspace\",\"type\":\"$gtype\"}")"
      opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
      [[ -z "$opid" ]] && { err "$(echo "$out" | sed 's/.*"error":"\([^"]*\)".*/\1/')"; exit 1; }
      out="$(wait_op "$opid")"
      print_op_result "$out"
      ;;
    logs)
      local project="${2:-}"
      [[ -z "$project" ]] && { err "Usage: upm.sh logs <project>"; exit 1; }
      api_call GET "/api/projects/$project/logs" | python3 -c '
import json,sys
d=json.load(sys.stdin)
print(d.get("log") or "(empty)")
' 2>/dev/null
      ;;
    git-status)
      local project="${2:-}"
      [[ -z "$project" ]] && { err "Usage: upm.sh git-status <project>"; exit 1; }
      api_call GET "/api/projects/$project/git" | python3 -c '
import json,sys
g=json.load(sys.stdin)
if not g.get("isRepo"): print("not a git repository"); sys.exit()
print("branch:", g.get("branch"), " HEAD:", g.get("head"))
print("modified:", g.get("modifiedCount"), " staged:", g.get("stagedCount"), " untracked:", g.get("untrackedCount"))
for f in (g.get("files") or [])[:30]: print(" ", f["state"], f["path"])
' 2>/dev/null
      ;;
    git-fetch|git-pull|git-push)
      local project="${2:-}" gtype="gitFetch"
      [[ "$cmd" == "git-pull" ]] && gtype="gitPull"
      [[ "$cmd" == "git-push" ]] && gtype="gitPush"
      [[ -z "$project" ]] && { err "Usage: upm.sh $cmd <project>"; exit 1; }
      if [[ "$cmd" == "git-push" && "${3:-}" != "--yes" ]]; then
        err "git push is externally visible. Re-run with --yes to confirm."
        exit 1
      fi
      out="$(api_call POST "/api/ops" "{\"project\":\"$project\",\"type\":\"$gtype\"}")"
      opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
      out="$(wait_op "$opid")"
      print_op_result "$out"
      ;;
    ws-list)
      api_call GET "/api/workspaces" | python3 -c '
import json,sys
for w in json.load(sys.stdin):
    mark="*" if w.get("current") else " "
    print(f" {mark} {w[\"id\"]:<20} {w.get(\"name\")} ({w.get(\"projectCount\",0)} projects)")
' 2>/dev/null
      ;;
    ws-switch)
      local id="${2:-}"
      [[ -z "$id" ]] && { err "Usage: upm.sh ws-switch <id>"; exit 1; }
      api_call POST "/api/workspaces/$id/switch" '{}'
      echo
      ;;
    ws-create)
      local id="${2:-}" name="${3:-$2}"
      [[ -z "$id" ]] && { err "Usage: upm.sh ws-create <id> [name]"; exit 1; }
      api_call POST "/api/workspaces" "{\"id\":\"$id\",\"name\":\"$name\"}"
      echo
      ;;
    ws-export)
      local id="${2:-}" file="${3:-}"
      [[ -z "$id" ]] && { err "Usage: upm.sh ws-export <id> [file]"; exit 1; }
      [[ -z "$file" ]] && file="workspace-$id.zip"
      curl -s -X POST "$API/api/workspaces/$id/export" -o "$file"
      ok "Exported $file ($(stat -c%s "$file" 2>/dev/null || echo '?') bytes)"
      ;;
    profile-launch|profile-stop)
      local id="${2:-}" gtype="profileLaunch"
      [[ "$cmd" == "profile-stop" ]] && gtype="profileStop"
      [[ -z "$id" ]] && { err "Usage: upm.sh $cmd <profileId>"; exit 1; }
      out="$(api_call POST "/api/ops" "{\"project\":\"\",\"type\":\"$gtype\",\"launchConfig\":\"$id\"}")"
      opid="$(echo "$out" | sed -n 's/.*"opId":\([0-9]*\).*/\1/p')"
      out="$(wait_op "$opid")"
      print_op_result "$out"
      ;;
    gui)
      if command -v xdg-open >/dev/null 2>&1; then
        xdg-open "$API/" >/dev/null 2>&1 &
        ok "Opened $API/ in the browser."
      else
        ok "Universal Project Manager: $API/"
      fi
      ;;
    *)
      err "Unknown command: $cmd"
      usage
      exit 1
      ;;
  esac
}

main "$@"
