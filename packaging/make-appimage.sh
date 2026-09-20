#!/usr/bin/env bash
set -euo pipefail

ROOT=$(cd "$(dirname "$0")/.." && pwd)
APPDIR="$ROOT/build/AppDir"
DIST="$ROOT/dist"

SRC=$(find "$ROOT/build/compose/binaries/main/app" -mindepth 1 -maxdepth 1 -type d | head -n 1)
[ -n "$SRC" ] || { echo "no app image, run createDistributable first" >&2; exit 1; }

echo "--- jpackage output ---"
find "$SRC" -maxdepth 3 | sed "s|^$SRC|.|" | sort

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

# An AppDir without a runtime produces an AppImage that builds and never starts.
# It shipped once. It fails here now, with the listing that explains why.
JAVA=$(find "$APPDIR/usr" -type f -name java -perm -u+x | head -n 1)
if [ -z "$JAVA" ]; then
    echo "no java runtime in the AppDir" >&2
    find "$APPDIR/usr" -maxdepth 4 >&2
    exit 1
fi
echo "runtime at ${JAVA#"$APPDIR/"}"

CFG=$(find "$APPDIR/usr" -type f -name '*.cfg' | head -n 1)
[ -n "$CFG" ] || { echo "no .cfg in the AppDir" >&2; exit 1; }
echo "cfg at ${CFG#"$APPDIR/"}"

TOOL="$ROOT/build/appimagetool"
if [ ! -x "$TOOL" ]; then
    curl -fsSL -o "$TOOL" \
        https://github.com/AppImage/appimagetool/releases/download/continuous/appimagetool-x86_64.AppImage \
    || curl -fsSL -o "$TOOL" \
        https://github.com/AppImage/AppImageKit/releases/download/continuous/appimagetool-x86_64.AppImage
    chmod +x "$TOOL"
fi

ARCH=x86_64 "$TOOL" --appimage-extract-and-run "$APPDIR" "$DIST/GutapK-x86_64.AppImage"
( cd "$DIST" && sha256sum GutapK-x86_64.AppImage > GutapK-x86_64.AppImage.sha256 )
cat "$DIST/GutapK-x86_64.AppImage.sha256"
