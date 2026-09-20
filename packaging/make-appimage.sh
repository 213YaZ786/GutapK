#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APPDIR="$ROOT/build/AppDir"
DIST="$ROOT/dist"

SRC=$(find "$ROOT/build/compose/binaries/main/app" -mindepth 1 -maxdepth 1 -type d | head -n 1)
[ -n "$SRC" ] || { echo "no app image, run createDistributable first" >&2; exit 1; }

rm -rf "$APPDIR" "$DIST"
mkdir -p "$APPDIR/usr" "$DIST"
cp -a "$SRC/." "$APPDIR/usr/"

install -m 755 "$ROOT/packaging/gutapk-launch" "$APPDIR/usr/bin/gutapk-launch"
install -m 755 "$ROOT/packaging/AppRun" "$APPDIR/AppRun"
install -m 644 "$ROOT/packaging/gutapk.desktop" "$APPDIR/gutapk.desktop"
install -m 644 "$ROOT/packaging/gutapk.png" "$APPDIR/gutapk.png"

mkdir -p "$APPDIR/usr/share/applications" "$APPDIR/usr/share/icons/hicolor/256x256/apps"
cp "$APPDIR/gutapk.desktop" "$APPDIR/usr/share/applications/"
cp "$APPDIR/gutapk.png" "$APPDIR/usr/share/icons/hicolor/256x256/apps/"

TOOL="$ROOT/build/appimagetool"
if [ ! -x "$TOOL" ]; then
    curl -fsSL -o "$TOOL" \
        https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage \
    || curl -fsSL -o "$TOOL" \
        https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage
    chmod +x "$TOOL"
fi

ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$APPDIR" "$DIST/GutapK-x86_64.AppImage"
sha256sum "$DIST/GutapK-x86_64.AppImage" | tee "$DIST/GutapK-x86_64.AppImage.sha256"
