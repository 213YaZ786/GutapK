#!/bin/bash
# Resolves the latest release of every tool in the table, the way the app
# does, downloads it and checks it against what the publisher states. Fails
# when a lookup no longer works or a file does not match its publisher, not
# when a new version comes out.
#
#   bash scripts/check-tools.sh [table] [scratch dir]
set -euo pipefail

TABLE="${1:-src/main/resources/io/gutapk/tools/tools.tsv}"
SCRATCH="${2:-${RUNNER_TEMP:-$PWD/build}/tools-check}"
mkdir -p "$SCRATCH"

# Same rules as Releases.parseGoogle: stable channel, no preview, Linux,
# newest version. Prints version, url, size, sha1 on one line.
google_latest() {
    python3 - "$1" "$2" "$3" <<'PY'
import sys, xml.etree.ElementTree as ET
index, pkg, base = sys.argv[1], sys.argv[2], sys.argv[3]
root = ET.parse(index).getroot()
def local(t): return t.rsplit('}', 1)[-1]
def child(e, name):
    for c in e:
        if local(c.tag) == name:
            return c
    return None
best = None
for p in root.iter():
    if local(p.tag) != 'remotePackage' or p.get('path') != pkg:
        continue
    rev = child(p, 'revision')
    if rev is None or child(rev, 'preview') is not None:
        continue
    ch = child(p, 'channelRef')
    if ch is not None and ch.get('ref') not in (None, '', 'channel-0'):
        continue
    parts = [child(rev, n) for n in ('major', 'minor', 'micro')]
    parts = [x.text.strip() for x in parts if x is not None and x.text and x.text.strip()]
    if not parts:
        continue
    key = [int(x) if x.isdigit() else 0 for x in parts]
    archives = child(p, 'archives')
    if archives is None:
        continue
    for a in archives:
        if local(a.tag) != 'archive':
            continue
        os_ = child(a, 'host-os')
        arch = child(a, 'host-arch')
        if os_ is not None and os_.text and os_.text.strip() != 'linux':
            continue
        if arch is not None and arch.text and arch.text.strip() != 'x64':
            continue
        c = child(a, 'complete')
        if c is None:
            continue
        url = child(c, 'url').text.strip()
        if not url.startswith('https://'):
            url = base + url
        cand = (key, '.'.join(parts), url, child(c, 'size').text.strip(), child(c, 'checksum').text.strip().lower())
        if best is None or cand[0] > best[0]:
            best = cand
if best is None:
    sys.exit('package %s not found' % pkg)
print(best[1], best[2], best[3], best[4])
PY
}

fail=0
checked=0
while IFS=$'\t' read -r id source index pkg entry execdir licence licenceurl what; do
    case "$id" in ''|'#'*) continue ;; esac
    checked=$((checked + 1))
    echo "--- $id, $source, $index"
    case "$source" in
        google-repo)
            if ! curl -fsSL --retry 3 --retry-delay 5 -o "$SCRATCH/index.xml" "$index"; then
                echo "::error::$id index unreachable"
                fail=1
                continue
            fi
            if ! line="$(google_latest "$SCRATCH/index.xml" "$pkg" "${index%/*}/")"; then
                echo "::error::$id lookup failed in the index"
                fail=1
                continue
            fi
            read -r version url size sha1 <<< "$line"
            ;;
        *)
            echo "::error::$id has unknown source $source"
            fail=1
            continue
            ;;
    esac

    echo "latest $version"
    echo "from   $url"
    file="$SCRATCH/$id-$version"
    if ! curl -fsSL --retry 3 --retry-delay 5 -o "$file" "$url"; then
        echo "::error::$id $version download failed"
        fail=1
        continue
    fi
    got_size="$(stat -c %s "$file")"
    got_sha1="$(sha1sum "$file" | cut -d' ' -f1)"
    echo "size   $got_size"
    echo "sha1   $got_sha1"
    echo "sha256 $(sha256sum "$file" | cut -d' ' -f1)"
    [ "$got_size" = "$size" ] || { echo "::error::$id size is $got_size, publisher says $size"; fail=1; }
    [ "$got_sha1" = "$sha1" ] || { echo "::error::$id sha1 is $got_sha1, publisher says $sha1"; fail=1; }

    # The entry the app will run must exist, or the install succeeds and the
    # tool is missing.
    if ! unzip -l "$file" "$entry" > /dev/null 2>&1; then
        echo "::error::$id $version archive has no $entry"
        fail=1
    fi
    rm -f "$file" "$SCRATCH/index.xml"
done < "$TABLE"

if [ "$checked" -eq 0 ]; then
    echo "::error::no tool found in $TABLE"
    exit 1
fi
echo "$checked tool(s) checked"
exit "$fail"
