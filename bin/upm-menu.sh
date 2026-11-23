#!/usr/bin/env bash
# Universal Project Manager — simple interactive terminal menu.
# Every operation delegates to the same backend (bin/upm.sh).
set -u
APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
UPM="$APP_DIR/bin/upm.sh"

print_header() {
  clear
  echo "=============================================================="
  echo "                     UPM — Terminal"
  echo "=============================================================="
  "$UPM" status 2>/dev/null | head -20
  echo "--------------------------------------------------------------"
}

main_menu() {
  while true; do
    print_header
    echo "  1. Build All              6. Restart All"
    echo "  2. Update All             7. Workspace Status"
    echo "  3. Test All               8. Diagnostics"
    echo "  4. Launch All             9. Open GUI"
    echo "  5. Stop All              10. Exit"
    echo ""
    echo "  P. Project submenu        W. Workspaces"
    echo "--------------------------------------------------------------"
    read -r -p "Choose [1-10/P/W]: " choice
    case "$choice" in
      1) "$UPM" build all; pause ;;
      2) "$UPM" update all; pause ;;
      3) "$UPM" test all; pause ;;
      4) "$UPM" launch-all; pause ;;
      5) "$UPM" stop-all; pause ;;
      6) "$UPM" restart-all; pause ;;
      7) "$UPM" status; echo; "$UPM" report 2>/dev/null; pause ;;
      8) "$UPM" diagnostics workspace; pause ;;
      9) "$UPM" gui; pause ;;
      10) echo "Bye."; exit 0 ;;
      p|P) project_menu ;;
      w|W) workspace_menu ;;
      *) echo "Invalid choice."; pause ;;
    esac
  done
}

project_menu() {
  while true; do
    print_header
    echo "Projects:"
    mapfile -t _menu_projects < <("$UPM" list 2>/dev/null | sed -n 's/^\([A-Za-z][A-Za-z0-9_-]*\) .*/\1/p')
    local i=1
    for pid in "${_menu_projects[@]}"; do
      printf "  %2d. %s\n" "$i" "$pid"
      i=$((i + 1))
    done
    echo "   0. Back"
    echo "--------------------------------------------------------------"
    read -r -p "Choose project [0-${#_menu_projects[@]}]: " choice
    if [[ "$choice" == "0" ]]; then return; fi
    if [[ "$choice" =~ ^[0-9]+$ ]] && (( choice >= 1 && choice <= ${#_menu_projects[@]} )); then
      project_action_menu "${_menu_projects[$((choice - 1))]}"
    else
      echo "Invalid choice."; pause
    fi
  done
}

project_action_menu() {
  local pid="$1"
  while true; do
    print_header
    echo "Project: $pid"
    echo "  1. Build           6. Stop              11. Back"
    echo "  2. Update Deps     7. Restart"
    echo "  3. Test            8. Logs"
    echo "  4. Run (default)   9. Diagnostics"
    echo "  5. Git status     10. Git fetch"
    echo "--------------------------------------------------------------"
    read -r -p "Choose [1-11]: " choice
    case "$choice" in
      1) "$UPM" build "$pid"; pause ;;
      2) "$UPM" update "$pid"; pause ;;
      3) "$UPM" test "$pid"; pause ;;
      4) "$UPM" run "$pid"; pause ;;
      5) "$UPM" git-status "$pid"; pause ;;
      6) "$UPM" stop "$pid"; pause ;;
      7) "$UPM" restart "$pid"; pause ;;
      8) "$UPM" logs "$pid" | tail -60; pause ;;
      9) "$UPM" diagnostics "$pid"; pause ;;
      10) "$UPM" git-fetch "$pid"; pause ;;
      11) return ;;
      *) echo "Invalid choice."; pause ;;
    esac
  done
}

workspace_menu() {
  while true; do
    print_header
    "$UPM" ws-list 2>/dev/null
    echo "--------------------------------------------------------------"
    echo "  S. Switch workspace        N. Create workspace"
    echo "  E. Export workspace        0. Back"
    echo "--------------------------------------------------------------"
    read -r -p "Choose [S/N/E/0]: " choice
    case "$choice" in
      s|S)
        read -r -p "Workspace id: " wsid
        [[ -n "$wsid" ]] && "$UPM" ws-switch "$wsid"
        pause ;;
      n|N)
        read -r -p "New workspace id: " wsid
        read -r -p "Name: " wsname
        [[ -n "$wsid" ]] && "$UPM" ws-create "$wsid" "$wsname"
        pause ;;
      e|E)
        read -r -p "Workspace id: " wsid
        [[ -n "$wsid" ]] && "$UPM" ws-export "$wsid"
        pause ;;
      0) return ;;
      *) echo "Invalid choice."; pause ;;
    esac
  done
}

pause() {
  echo ""
  read -r -p "Press Enter to continue..."
}

main_menu
