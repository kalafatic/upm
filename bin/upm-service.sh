#!/usr/bin/env bash
# =============================================================================
# UPM — systemd service management (install / uninstall / status / lifecycle).
# The service reuses the same JVM and the same UPM core as the interactive
# launch: systemd sends SIGTERM, the JVM shutdown hook stops the HTTP server
# gracefully and releases its sockets. No shell backgrounding (&) in ExecStart.
#
#   upm-service.sh install [--system] [--user NAME]   # install + enable
#   upm-service.sh uninstall [--system]               # disable + remove
#   upm-service.sh status|start|stop|restart [--system]
# =============================================================================
set -u

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TEMPLATE="$APP_DIR/service/upm.service"
LOG_DIR="$APP_DIR/logs"

err() { echo "ERROR: $*" >&2; exit 1; }
info() { echo "$*"; }

# Locate a JDK 25+ (no hardcoded developer paths).
detect_java() {
  local j=""
  for c in /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
           /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
           /usr/lib/jvm/openjdk-25/bin/java; do
    [[ -x "$c" ]] && { j="$c"; break; }
  done
  [[ -z "$j" ]] && j="$(command -v java)"
  [[ -z "$j" || ! -x "$j" ]] && err "Java 25+ not found. Install a JDK or set UPM_JAVA."
  echo "$j"
}

SERVICE_USER="${SUDO_USER:-$USER}"
SYSTEM=0
ACTION="${1:-}"
shift 2>/dev/null || true
while [[ $# -gt 0 ]]; do
  case "$1" in
    --system) SYSTEM=1 ;;
    --user) SERVICE_USER="${2:-$SERVICE_USER}"; shift ;;
    *) ;;
  esac
  shift
done

[[ -z "$ACTION" ]] && err "Usage: upm-service.sh install|uninstall|status|start|stop|restart [--system] [--user NAME]"
[[ ! -f "$TEMPLATE" ]] && err "Missing template: $TEMPLATE"

JAVA_BIN="$(detect_java)"

# Lifecycle commands delegate straight to systemctl.
case "$ACTION" in
  status|start|stop|restart)
    if [[ "$SYSTEM" == "1" ]]; then
      systemctl "$ACTION" upm.service
    else
      systemctl --user "$ACTION" upm.service
    fi
    exit $?
    ;;
esac

generate_unit() {
  local out="$1"
  sed -e "s|@APP_DIR@|$APP_DIR|g" \
      -e "s|@JAVA_BIN@|$JAVA_BIN|g" \
      -e "s|@USER@|$SERVICE_USER|g" \
      -e "s|@GROUP@|$(id -gn "$SERVICE_USER" 2>/dev/null || echo "$SERVICE_USER")|g" \
      "$TEMPLATE" > "$out"
  # User-level services must not declare User=/Group=.
  if [[ "$SYSTEM" != "1" ]]; then
    sed -i '/^User=/d; /^Group=/d' "$out"
  fi
}

case "$ACTION" in
  install)
    mkdir -p "$LOG_DIR"
    if [[ "$SYSTEM" == "1" ]]; then
      [[ $EUID -ne 0 ]] && err "System install requires root (use sudo)."
      generate_unit "/etc/systemd/system/upm.service"
      systemctl daemon-reload
      systemctl enable upm.service
      info "Installed and enabled system service: upm.service (user $SERVICE_USER)"
    else
      local_dir="$HOME/.config/systemd/user"
      mkdir -p "$local_dir"
      generate_unit "$local_dir/upm.service"
      systemctl --user daemon-reload
      systemctl --user enable upm.service
      info "Installed and enabled user service: $local_dir/upm.service"
    fi
    ;;
  uninstall)
    if [[ "$SYSTEM" == "1" ]]; then
      [[ $EUID -ne 0 ]] && err "System uninstall requires root (use sudo)."
      systemctl disable upm.service 2>/dev/null || true
      rm -f /etc/systemd/system/upm.service
      systemctl daemon-reload
      info "Removed system service: upm.service"
    else
      systemctl --user disable upm.service 2>/dev/null || true
      rm -f "$HOME/.config/systemd/user/upm.service"
      systemctl --user daemon-reload
      info "Removed user service: upm.service"
    fi
    ;;
  *)
    err "Unknown action: $ACTION"
    ;;
esac
