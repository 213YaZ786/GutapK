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

# Same rules as Releases.parseGithubList: drafts skipped, pre-releases
# taken, highest version among the releases holding a matching asset.
github_pre_latest() {
    python3 - "$1" "$2" <<'PY'
import json, re, sys
path, pattern = sys.argv[1], sys.argv[2]
def key(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r'[.-]', v)]
best = None
for d in json.load(open(path)):
    if d.get('draft'):
        continue
    version = d['tag_name'].strip()
    for p in ('v', 'V'):
        if version.startswith(p):
            version = version[1:]
    if not version or not version[0].isdigit():
        continue
    for a in d.get('assets', []):
        if re.fullmatch(pattern, a['name']):
            url = a['browser_download_url']
            if not url.startswith('https://'):
                continue
            digest = (a.get('digest') or '').strip().lower()
            sha = digest[7:] if re.fullmatch(r'sha256:[0-9a-f]{64}', digest) else '-'
            cand = (key(version), version, url, a['size'], sha)
            if best is None or cand[0] > best[0]:
                best = cand
            break
if best is None:
    sys.exit('no asset matching %s' % pattern)
print(best[1], best[2], best[3], best[4])
PY
}

# Same rules as Releases.parseNightlyRun and parseNightlyArtifact: the
# newest successful push build, the artifact by name, GitHub's digest, the
# file through nightly.link by run id. Prints version, url, size, sha256.
# Exits 3 when the build has no such artifact left, which is a warning.
github_nightly_latest() {
    python3 - "$1" "$2" "$3" "$4" <<'PY'
import json, re, sys
runs, arts, repo, name = sys.argv[1:5]
r = json.load(open(runs)).get('workflow_runs') or []
if not r:
    sys.exit('no successful build')
run = r[0]
m = re.fullmatch(r'(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})Z', run['created_at'])
version = ''.join(m.group(1, 2, 3)) + '.' + ''.join(m.group(4, 5, 6)) + '-' + run['head_sha'][:7]
for a in json.load(open(arts)).get('artifacts', []):
    if a['name'] == name and not a.get('expired'):
        digest = (a.get('digest') or '').lower()
        if not re.fullmatch(r'sha256:[0-9a-f]{64}', digest):
            sys.exit('artifact has no digest')
        print(version, 'https://nightly.link/%s/actions/runs/%s/%s.zip' % (repo, run['id'], name), a['size_in_bytes'], digest[7:])
        sys.exit(0)
sys.exit(3)
PY
}

# Same rules as Releases.dotnetChannel and parseDotnetChannel: newest long
# term channel still supported, its latest runtime, the file by name, its
# sha512. Prints version, url, sha512.
dotnet_latest() {
    python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
from urllib.parse import urlparse
index_file, index_url, name = sys.argv[1:4]
host = urlparse(index_url).hostname
def key(v):
    return [int(x) if x.isdigit() else 0 for x in re.split(r'[.-]', v)]
chans = [c for c in json.load(open(index_file))['releases-index'] if c.get('release-type') == 'lts' and c.get('support-phase') in ('active', 'maintenance')]
if not chans:
    sys.exit('no supported lts channel')
c = max(chans, key=lambda c: key(c['channel-version']))
url = c['releases.json']
if not url.startswith('https://') or urlparse(url).hostname != host:
    sys.exit('channel index on another host')
print(url)
PY
}
dotnet_file() {
    python3 - "$1" "$2" "$3" <<'PY'
import json, re, sys
from urllib.parse import urlparse
chan_file, index_url, name = sys.argv[1:4]
d = json.load(open(chan_file))
latest = d['latest-runtime']
for r in d['releases']:
    rt = r.get('runtime') or {}
    if rt.get('version') != latest:
        continue
    for f in rt.get('files', []):
        if f['name'] == name:
            if not f['url'].startswith('https://') or urlparse(f['url']).hostname != urlparse(index_url).hostname:
                sys.exit('file on another host')
            h = f['hash'].lower()
            if not re.fullmatch(r'[0-9a-f]{128}', h):
                sys.exit('no sha512')
            print(latest, f['url'], h)
            sys.exit(0)
sys.exit('%s not in the latest runtime' % name)
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
        github-pre)
            repo="${index#https://github.com/}"
            if ! curl -fsSL --retry 3 --retry-delay 5 "${gh_auth[@]}" \
                    -H "Accept: application/vnd.github+json" \
                    -o "$SCRATCH/release.json" "https://api.github.com/repos/$repo/releases?per_page=20"; then
                echo "::error::$id release lookup failed on the GitHub API"
                fail=1
                continue
            fi
            if ! line="$(github_pre_latest "$SCRATCH/release.json" "$pkg")"; then
                echo "::error::$id lookup failed in the release answer"
                fail=1
                continue
            fi
            read -r version url size sha256 <<< "$line"
            sha1=-
            ;;
        dotnet)
            if ! curl -fsSL --retry 3 --retry-delay 5 -o "$SCRATCH/index.json" "$index" \
                    || ! chan="$(dotnet_latest "$SCRATCH/index.json" "$index" "$pkg")" \
                    || ! curl -fsSL --retry 3 --retry-delay 5 -o "$SCRATCH/channel.json" "$chan" \
                    || ! line="$(dotnet_file "$SCRATCH/channel.json" "$index" "$pkg")"; then
                echo "::error::$id lookup failed in the .NET release index"
                fail=1
                continue
            fi
            read -r version url sha512 <<< "$line"
            size="$(curl -fsSI --retry 3 "$url" | tr -d '\r' | awk 'tolower($1)=="content-length:" {print $2}' | tail -1)"
            sha1=-
            sha256=-
            ;;
        github-nightly)
            repo="${index#https://github.com/}"
            workflow="${pkg%%@*}"
            rest="${pkg#*@}"
            branch="${rest%%/*}"
            artifact="${rest#*/}"
            if ! curl -fsSL --retry 3 --retry-delay 5 "${gh_auth[@]}" -H "Accept: application/vnd.github+json" \
                    -o "$SCRATCH/runs.json" "https://api.github.com/repos/$repo/actions/workflows/$workflow/runs?branch=$branch&status=success&event=push&per_page=1"; then
                echo "::error::$id build lookup failed on the GitHub API"
                fail=1
                continue
            fi
            run_id="$(python3 -c 'import json,sys; r=json.load(open(sys.argv[1]))["workflow_runs"]; print(r[0]["id"] if r else "")' "$SCRATCH/runs.json")"
            if [ -z "$run_id" ] || ! curl -fsSL --retry 3 --retry-delay 5 "${gh_auth[@]}" -H "Accept: application/vnd.github+json" \
                    -o "$SCRATCH/artifacts.json" "https://api.github.com/repos/$repo/actions/runs/$run_id/artifacts?per_page=100"; then
                echo "::error::$id artifact lookup failed on the GitHub API"
                fail=1
                continue
            fi
            set +e
            line="$(github_nightly_latest "$SCRATCH/runs.json" "$SCRATCH/artifacts.json" "$repo" "$artifact")"
            code=$?
            set -e
            if [ "$code" -eq 3 ]; then
                echo "::warning::$id newest build has no $artifact left, GitHub keeps them 90 days"
                continue
            elif [ "$code" -ne 0 ]; then
                echo "::error::$id lookup failed in the build answer"
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
    if [ -n "${sha512:-}" ]; then
        got_sha512="$(sha512sum "$file" | cut -d' ' -f1)"
        if [ "$got_sha512" != "$sha512" ]; then
            echo "::error::$id sha512 is $got_sha512, publisher says $sha512"
            fail=1
        else
            echo "sha512 matches the publisher"
        fi
    elif [ "$sha1" = "-" ] && [ "$sha256" = "-" ]; then
        echo "::warning::$id publishes no checksum, the app records it on first download"
    fi

    # The entry the app will run must exist, or the install succeeds and the
    # tool is missing. A single jar is its own entry, it must be a sound zip.
    # A single native program must be an ELF file.
    case "$url" in
        *.zip)
            if ! unzip -l "$file" "$entry" > /dev/null 2>&1; then
                echo "::error::$id $version archive has no $entry"
                fail=1
            fi
            ;;
        *.tar.gz)
            # The app drops the top folder, so the entry is one level down.
            if ! tar -tzf "$file" | cut -d/ -f2- | grep -qx "$entry"; then
                echo "::error::$id $version archive has no $entry"
                fail=1
            fi
            ;;
        *)
            if [ "$execdir" = "." ]; then
                if [ "$(head -c 4 "$file" | od -An -tx1 | tr -d ' \n')" != "7f454c46" ]; then
                    echo "::error::$id $version is not an ELF program"
                    fail=1
                fi
            elif ! unzip -tq "$file" > /dev/null 2>&1; then
                echo "::error::$id $version is not a sound jar"
                fail=1
            fi
            ;;
    esac
    rm -f "$file" "$SCRATCH/index.xml" "$SCRATCH/release.json" "$SCRATCH/runs.json" "$SCRATCH/artifacts.json" "$SCRATCH/index.json" "$SCRATCH/channel.json"
    sha512=
done < "$TABLE"

if [ "$checked" -eq 0 ]; then
    echo "::error::no tool found in $TABLE"
    exit 1
fi
echo "$checked tool(s) checked"
exit "$fail"
