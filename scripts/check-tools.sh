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

# Same rules as Releases.parseGithub: tag without its leading v, first asset
# whose whole name matches the pattern, GitHub's sha256 when published.
# Prints version, url, size, sha256 or - on one line.
github_latest() {
    python3 - "$1" "$2" <<'PY'
import json, re, sys
path, pattern = sys.argv[1], sys.argv[2]
d = json.load(open(path))
if d.get('draft') or d.get('prerelease'):
    sys.exit('latest release is a draft or a pre-release')
version = d['tag_name'].strip()
for p in ('v', 'V'):
    if version.startswith(p):
        version = version[1:]
if not version or not version[0].isdigit():
    sys.exit('tag %s is not a version' % d['tag_name'])
for a in d.get('assets', []):
    if re.fullmatch(pattern, a['name']):
        url = a['browser_download_url']
        if not url.startswith('https://'):
            sys.exit('asset url is not https')
        digest = (a.get('digest') or '').strip().lower()
        sha = digest[7:] if re.fullmatch(r'sha256:[0-9a-f]{64}', digest) else '-'
        print(version, url, a['size'], sha)
        sys.exit(0)
sys.exit('no asset matching %s' % pattern)
PY
}

# The app asks GitHub without an account, 60 lookups an hour. CI shares its
# addresses with other jobs, so it uses the job token when one is given.
gh_auth=()
if [ -n "${GH_TOKEN:-}" ]; then
    gh_auth=(-H "Authorization: Bearer $GH_TOKEN")
fi

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
            sha256=-
            ;;
        github)
            repo="${index#https://github.com/}"
            if ! curl -fsSL --retry 3 --retry-delay 5 "${gh_auth[@]}" \
                    -H "Accept: application/vnd.github+json" \
                    -o "$SCRATCH/release.json" "https://api.github.com/repos/$repo/releases/latest"; then
                echo "::error::$id release lookup failed on the GitHub API"
                fail=1
                continue
            fi
            if ! line="$(github_latest "$SCRATCH/release.json" "$pkg")"; then
                echo "::error::$id lookup failed in the release answer"
                fail=1
                continue
            fi
            read -r version url size sha256 <<< "$line"
            sha1=-
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
    got_sha256="$(sha256sum "$file" | cut -d' ' -f1)"
    echo "sha256 $got_sha256"
    if [ "$got_size" != "$size" ]; then
        echo "::error::$id size is $got_size, publisher says $size"
        fail=1
    fi
    if [ "$sha1" != "-" ] && [ "$got_sha1" != "$sha1" ]; then
        echo "::error::$id sha1 is $got_sha1, publisher says $sha1"
        fail=1
    fi
    if [ "$sha256" != "-" ] && [ "$got_sha256" != "$sha256" ]; then
        echo "::error::$id sha256 is $got_sha256, publisher says $sha256"
        fail=1
    fi
    if [ "$sha1" = "-" ] && [ "$sha256" = "-" ]; then
        echo "::warning::$id publishes no checksum, the app records it on first download"
    fi

    # The entry the app will run must exist, or the install succeeds and the
    # tool is missing. A single jar is its own entry, it must be a sound zip.
    case "$url" in
        *.zip)
            if ! unzip -l "$file" "$entry" > /dev/null 2>&1; then
                echo "::error::$id $version archive has no $entry"
                fail=1
            fi
            ;;
        *)
            if ! unzip -tq "$file" > /dev/null 2>&1; then
                echo "::error::$id $version is not a sound jar"
                fail=1
            fi
            ;;
    esac
    rm -f "$file" "$SCRATCH/index.xml" "$SCRATCH/release.json"
done < "$TABLE"

if [ "$checked" -eq 0 ]; then
    echo "::error::no tool found in $TABLE"
    exit 1
fi
echo "$checked tool(s) checked"
exit "$fail"
