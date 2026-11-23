#!/usr/bin/env bash
# Installs the UPM desktop launchers (Ubuntu GNOME).
# The @APP_DIR@ placeholder in the .desktop files is replaced with the actual
# application directory at install time — no hardcoded developer paths.
set -u
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

chmod +x "$APP_DIR/launch.sh" "$APP_DIR/launch.desktop" "$APP_DIR/launch-stop.desktop" \
  "$APP_DIR/bin/"*.sh 2>/dev/null

install_desktop() {
  local src="$1" dst="$2"
  sed "s|@APP_DIR@|$APP_DIR|g" "$src" > "$dst"
  chmod +x "$dst"
}

mkdir -p "$HOME/.local/share/applications"
install_desktop "$APP_DIR/launch.desktop" "$HOME/.local/share/applications/upm.desktop"
install_desktop "$APP_DIR/launch-stop.desktop" "$HOME/.local/share/applications/upm-stop.desktop"
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true

if [[ -d "$HOME/Desktop" ]]; then
  install_desktop "$APP_DIR/launch.desktop" "$HOME/Desktop/UPM.desktop"
  echo "Desktop shortcut: $HOME/Desktop/UPM.desktop"
fi
echo "Applications menu entries installed."
