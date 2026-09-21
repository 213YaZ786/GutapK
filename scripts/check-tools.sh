#!/bin/bash
# Downloads every tool of the table and checks it against what the table
# says. Fails the build on any difference, and prints the sha256 of each
# file so an unpinned one can be pinned.
#
#   bash scripts/check-tools.sh [table] [scratch dir]
set -euo pipefail

TABLE="${1:-src/main/resources/io/gutapk/tools/tools.tsv}"
SCRATCH="${2:-${RUNNER_TEMP:-$PWD/build}/tools-check}"
mkdir -p "$SCRATCH"

fail=0
checked=0
while IFS=$'\t' read -r id version url size sha1 sha256 entry execdir licence licenceurl what; do
    case "$id" in ''|'#'*) continue ;; esac
    checked=$((checked + 1))
    file="$SCRATCH/$id-$version"
    echo "--- $id $version"
    echo "from $url"
    if ! curl -fsSL --retry 3 --retry-delay 5 -o "$file" "$url"; then
        echo "::error::$id $version download failed"
        fail=1
        continue
    fi

    got_size="$(stat -c %s "$file")"
    got_sha1="$(sha1sum "$file" | cut -d' ' -f1)"
    got_sha256="$(sha256sum "$file" | cut -d' ' -f1)"
    echo "size   $got_size"
    echo "sha1   $got_sha1"
    echo "sha256 $got_sha256"

    [ "$got_size" = "$size" ] || { echo "::error::$id size is $got_size, table says $size"; fail=1; }
    [ "$got_sha1" = "$sha1" ] || { echo "::error::$id sha1 is $got_sha1, table says $sha1"; fail=1; }
    if [ "$sha256" = "-" ]; then
        echo "::warning::$id $version has no pinned sha256. Pin $got_sha256"
    elif [ "$got_sha256" != "$sha256" ]; then
        echo "::error::$id sha256 is $got_sha256, table says $sha256"
        fail=1
    fi

    # The entry the app will run must exist in the archive, or the install
    # succeeds and the tool is missing.
    if ! unzip -l "$file" "$entry" > /dev/null 2>&1; then
        echo "::error::$id archive has no $entry"
        fail=1
    fi
    rm -f "$file"
done < "$TABLE"

if [ "$checked" -eq 0 ]; then
    echo "::error::no tool found in $TABLE"
    exit 1
fi
echo "$checked tool(s) checked"
exit "$fail"
