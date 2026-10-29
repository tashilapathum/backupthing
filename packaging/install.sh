#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Installs Backupthing for the current user from the unpacked release tarball, or upgrades an
# existing install. Nothing needs root; nothing outside these paths is changed:
#   ~/.local/opt/backupthing-<version>/   the program (~/.local/opt/backupthing links to it)
#   ~/.local/bin/backupthing              the command
#   ~/.config/systemd/user/               the background service
#   ~/.local/share/applications/, icons/  the app menu entry
# Your settings, the state database and your Proton sign-in are kept across upgrades and uninstalls.
#
# Usage:
#   ./install.sh                install or upgrade, start the service and open the dashboard
#   ./install.sh --no-service   install the program and menu entry only (run `backupthing serve` yourself)
#   ./install.sh --uninstall    stop the service and remove everything above
set -euo pipefail

VERSION="@version@"
NAME=backupthing
OPT="$HOME/.local/opt"
DEST="$OPT/$NAME-$VERSION"
CURRENT="$OPT/$NAME"
BIN_LINK="$HOME/.local/bin/$NAME"
UNIT="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/$NAME.service"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
DESKTOP="$APPS/$NAME.desktop"
ICON="$ICONS/scalable/apps/$NAME.svg"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
warn() { printf '\033[1;33mwarning:\033[0m %s\n' "$*" >&2; }
die() { printf '\033[1;31merror:\033[0m %s\n' "$*" >&2; exit 1; }

have_user_systemd() { command -v systemctl >/dev/null && systemctl --user show-environment >/dev/null 2>&1; }

refresh_menus() {
  update-desktop-database -q "$APPS" 2>/dev/null || true
  gtk-update-icon-cache -q -t "$ICONS" 2>/dev/null || true
}

install_app() {
  local here
  here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
  [[ -x "$here/bin/$NAME" && -d "$here/lib" ]] || die "run this from the unpacked $NAME-$VERSION folder"
  "$here/bin/$NAME" --version >/dev/null 2>&1 || die "$NAME doesn't start. It needs Java 21 or newer (java on PATH or JAVA_HOME)."

  say "Installing Backupthing $VERSION"
  mkdir -p "$OPT"
  if [[ "$here" != "$(realpath -m "$DEST")" ]]; then
    rm -rf "$DEST"
    cp -a "$here" "$DEST"
  fi
  ln -sfn "$NAME-$VERSION" "$CURRENT"
  mkdir -p "$(dirname "$BIN_LINK")"
  ln -sfn "$CURRENT/bin/$NAME" "$BIN_LINK"
  if [[ -e /usr/bin/$NAME ]]; then
    warn "/usr/bin/$NAME (a system package) is also installed; this copy takes precedence for you."
  fi
  case ":$PATH:" in
    *":$HOME/.local/bin:"*) ;;
    *) warn "~/.local/bin isn't on your PATH, so type $BIN_LINK (or add it to PATH)." ;;
  esac

  # Absolute paths: the menu doesn't see ~/.local/bin on every desktop.
  mkdir -p "$APPS" "$(dirname "$ICON")"
  sed "s|^Exec=$NAME |Exec=\"$CURRENT/bin/$NAME\" |" "$DEST/share/applications/$NAME.desktop" >"$DESKTOP"
  cp "$DEST/share/icons/hicolor/scalable/apps/$NAME.svg" "$ICON"
  refresh_menus

  # Older versions are no longer referenced by anything.
  for old in "$OPT/$NAME"-*/; do
    old="${old%/}"
    if [[ "$old" != "$DEST" && -x "$old/bin/$NAME" ]]; then rm -rf "$old"; fi
  done
}

install_service() {
  if ! have_user_systemd; then
    warn "No systemd user session found. Start Backupthing with: $NAME serve"
    return 1
  fi
  local was_active=false
  if systemctl --user -q is-active "$NAME"; then
    was_active=true
  elif pgrep -u "$(id -u)" -f "$NAME\.MainKt serve" >/dev/null; then
    die "Backupthing is already running outside the service (a \`$NAME serve\` you started?). Stop it and run this again."
  fi
  mkdir -p "$(dirname "$UNIT")"
  sed "s|^ExecStart=/usr/bin/$NAME |ExecStart=%h/.local/opt/$NAME/bin/$NAME |" \
    "$DEST/share/systemd/user/$NAME.service" >"$UNIT"
  systemctl --user daemon-reload
  systemctl --user enable -q "$NAME"
  if $was_active; then
    say "Restarting the service"
    systemctl --user restart "$NAME"
  else
    say "Starting the service"
    systemctl --user start "$NAME"
  fi
}

uninstall() {
  say "Uninstalling Backupthing"
  if [[ -f "$UNIT" ]]; then
    systemctl --user disable -q --now "$NAME" 2>/dev/null || true
    rm -f "$UNIT"
    systemctl --user daemon-reload 2>/dev/null || true
  fi
  rm -f "$DESKTOP" "$ICON"
  refresh_menus
  # Only our own link; leave anything else at that path alone.
  if [[ -L "$BIN_LINK" && "$(readlink "$BIN_LINK")" == "$CURRENT/bin/$NAME" ]]; then
    rm -f "$BIN_LINK"
  fi
  rm -f "$CURRENT"
  for dir in "$OPT/$NAME"-*/; do
    dir="${dir%/}"
    if [[ -x "$dir/bin/$NAME" ]]; then rm -rf "$dir"; fi
  done
  echo "Kept your settings and history. To remove them too:"
  echo "  rm -rf ${XDG_CONFIG_HOME:-~/.config}/$NAME ${XDG_DATA_HOME:-~/.local/share}/$NAME ${XDG_CACHE_HOME:-~/.cache}/$NAME"
}

case "${1:-}" in
  "")
    install_app
    if install_service; then
      "$CURRENT/bin/$NAME" open || exit 1
      say "Done. Backupthing runs in the background and starts when you log in."
      echo "Open the dashboard any time from your app menu (Backupthing) or with: $NAME open"
    fi
    ;;
  --no-service)
    install_app
    say "Done. Start it with: $NAME serve"
    ;;
  --uninstall) uninstall ;;
  -h | --help) sed -n '4,15p' "$0" | sed 's/^# \{0,1\}//' ;;
  *) die "unknown option $1 (see --help)" ;;
esac
