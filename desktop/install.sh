#!/usr/bin/env bash
# Builds the musikbox desktop app and installs it for the current user.
#
#   desktop/install.sh              build (ProGuard-shrunk, bundled JRE) and install into ~/.local
#   desktop/install.sh --no-build   install the last build
#   desktop/install.sh --uninstall  remove it (settings, library and downloads are kept)
#
# Installs to ~/.local/opt/musikbox, links ~/.local/bin/musikbox, and adds a launcher
# entry + icon so it shows up in the KDE/GNOME app menu. Requires mpv at runtime.
# On NixOS, run it from the repo's shell.nix:  nix-shell --run desktop/install.sh
set -euo pipefail
cd "$(dirname "$0")/.."

PREFIX="${PREFIX:-$HOME/.local}"
APP_DIR="$PREFIX/opt/musikbox"
DESKTOP_FILE="$PREFIX/share/applications/musikbox.desktop"
ICON_DIR="$PREFIX/share/icons/hicolor"

log() { printf '\033[1;32m[musikbox]\033[0m %s\n' "$*"; }

if [[ "${1:-}" == "--uninstall" ]]; then
    rm -rf "$APP_DIR" "$PREFIX/bin/musikbox" "$DESKTOP_FILE" \
        "$ICON_DIR/256x256/apps/musikbox.png" "$ICON_DIR/scalable/apps/musikbox.svg"
    command -v update-desktop-database >/dev/null && update-desktop-database "$PREFIX/share/applications" || true
    log "uninstalled (your data in ~/.config/musikbox and ~/.local/share/musikbox was kept)"
    exit 0
fi

nixos=0
if [[ -e /etc/NIXOS ]]; then
    nixos=1
    [[ -n "${LD_LIBRARY_PATH:-}" && -n "${JAVA_HOME:-}" ]] ||
        { echo "on NixOS, run this from the repo's nix-shell: nix-shell --run desktop/install.sh" >&2; exit 1; }
fi

command -v mpv >/dev/null || log "WARNING: mpv is not installed — musikbox needs it for playback (e.g. sudo pacman -S mpv)"

if [[ "${1:-}" != "--no-build" ]]; then
    log "building the release app"
    ./gradlew --console=plain -q :desktop:createReleaseDistributable
fi
src=desktop/build/compose/binaries/main-release/app/musikbox
[[ -x "$src/bin/musikbox" ]] || { echo "no build at $src" >&2; exit 1; }

log "installing to $APP_DIR"
rm -rf "$APP_DIR"
mkdir -p "$(dirname "$APP_DIR")" "$PREFIX/bin" "$(dirname "$DESKTOP_FILE")" "$ICON_DIR/256x256/apps" "$ICON_DIR/scalable/apps"
cp -a "$src" "$APP_DIR"
launcher="$APP_DIR/bin/musikbox"
if [[ $nixos -eq 1 ]]; then
    # The bundled Java runtime links against the JDK's store paths, and Skia finds its libraries
    # through LD_LIBRARY_PATH. Register them, and shell.nix's mpv, as GC roots so
    # nix-collect-garbage keeps them; the launcher then works from the app menu too.
    log "NixOS: pinning the JDK, mpv and libraries from shell.nix as GC roots"
    mpv_bin=$(readlink -f "$(command -v mpv)")
    mkdir -p "$APP_DIR/gcroots"
    i=0
    for p in "$(readlink -f "$JAVA_HOME")" "$mpv_bin" ${LD_LIBRARY_PATH//:/ }; do
        [[ $p == /nix/store/* ]] || continue
        nix-store --add-root "$APP_DIR/gcroots/$i" --realise "$(cut -d/ -f1-4 <<<"$p")" >/dev/null
        i=$((i + 1))
    done
    launcher="$APP_DIR/musikbox-nixos"
    cat > "$launcher" <<EOS
#!/bin/sh
export LD_LIBRARY_PATH="$LD_LIBRARY_PATH\${LD_LIBRARY_PATH:+:\$LD_LIBRARY_PATH}"
export MUSIKBOX_MPV="\${MUSIKBOX_MPV:-$mpv_bin}"
exec "$APP_DIR/bin/musikbox" "\$@"
EOS
    chmod +x "$launcher"
fi
ln -sf "$launcher" "$PREFIX/bin/musikbox"
cp desktop/src/main/resources/musikbox.png "$ICON_DIR/256x256/apps/musikbox.png"
cp desktop/src/main/resources/musikbox.svg "$ICON_DIR/scalable/apps/musikbox.svg"

cat > "$DESKTOP_FILE" <<EOD
[Desktop Entry]
Type=Application
Name=musikbox
GenericName=Music Player
Comment=Playlist-first player for Navidrome and Subsonic
Exec=$launcher
Icon=musikbox
Terminal=false
Categories=AudioVideo;Audio;Player;
Keywords=music;navidrome;subsonic;playlist;
StartupWMClass=musikbox
EOD

command -v update-desktop-database >/dev/null && update-desktop-database "$PREFIX/share/applications" || true
command -v gtk-update-icon-cache >/dev/null && gtk-update-icon-cache -q -t "$ICON_DIR" || true
log "done — launch it from the app menu or run: musikbox"
