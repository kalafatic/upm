#!/usr/bin/env bash
# Universal Project Manager — desktop/terminal launcher.
# Single instance: a second invocation reuses the running application.
# Works from any working directory (paths resolve against this script).
set -u
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$APP_DIR/universal-project-manager.jar"
JAVA_BIN="${UPM_JAVA:-}"
CONFIG="${UPM_CONFIG:-$APP_DIR/config/application.jsonc}"
LOG_DIR="$APP_DIR/logs"
LOG_FILE="$LOG_DIR/upm.log"
PID_FILE="$LOG_DIR/upm.pid"

fail() {
  local msg="$1"
  if command -v zenity >/dev/null 2>&1; then
    zenity --error --title="Universal Project Manager" --text="$msg" 2>/dev/null
  elif command -v xmessage >/dev/null 2>&1; then
    xmessage "$msg" 2>/dev/null
  else
    echo "ERROR: $msg" >&2
  fi
  exit 1
}

# 1. Locate a JDK 25+ (configurable, detected at runtime — no hardcoded paths).
if [[ -z "$JAVA_BIN" ]]; then
  for candidate in /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
                  /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
                  /usr/lib/jvm/openjdk-25/bin/java; do
    [[ -x "$candidate" ]] && JAVA_BIN="$candidate" && break
  done
  [[ -z "$JAVA_BIN" ]] && JAVA_BIN="$(command -v java)"
fi
if [[ -z "$JAVA_BIN" || ! -x "$JAVA_BIN" ]]; then
  fail "Java 25+ not found. Install a JDK or set UPM_JAVA."
fi

if [[ ! -f "$JAR" ]]; then
  fail "universal-project-manager.jar not found in $APP_DIR.\nRun build.sh first."
fi

mkdir -p "$LOG_DIR"

# 2. Start the application fully detached (survives the terminal closing).
#    The jar itself detects an already-running instance and reuses it —
#    no duplicate servers, browser opens only once.
( setsid nohup "$JAVA_BIN" -jar "$JAR" "$CONFIG" >>"$LOG_FILE" 2>&1 </dev/null &
  echo $! > "$PID_FILE" )
exit 0
