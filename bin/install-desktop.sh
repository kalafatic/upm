#!/usr/bin/env bash
# Installs the Universal Project Manager desktop launchers (Ubuntu GNOME).
set -u
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

chmod +x "$APP_DIR/launch.sh" "$APP_DIR/launch.desktop" "$APP_DIR/launch-stop.desktop" \
  "$APP_DIR/bin/"*.sh 2>/dev/null

mkdir -p "$HOME/.local/share/applications"
cp "$APP_DIR/launch.desktop" "$HOME/.local/share/applications/"
cp "$APP_DIR/launch-stop.desktop" "$HOME/.local/share/applications/"
update-desktop-database "$HOME/.local/share/applications" 2>/dev/null || true

if [[ -d "$HOME/Desktop" ]]; then
  cp "$APP_DIR/launch.desktop" "$HOME/Desktop/Universal Project Manager.desktop"
  chmod +x "$HOME/Desktop/Universal Project Manager.desktop"
  echo "Desktop shortcut: $HOME/Desktop/Universal Project Manager.desktop"
fi
echo "Applications menu entries installed."
