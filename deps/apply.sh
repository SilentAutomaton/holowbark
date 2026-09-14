#!/bin/sh
# Fetch one Go dependency at a pinned revision and apply the patches this
# repository keeps for it.
#
#   deps/apply.sh <url> <ref> <checkout-dir> <patch-dir>
#
# Safe to run on every build. The checkout is a build artefact, not a place to
# work: tracked files are reset to the pinned revision before the patches go on,
# so the patches stay the only description of what we changed. Edit a patch, or
# capture your experiment with `git -C <checkout-dir> diff` before rebuilding.
set -eu

URL=$1
REF=$2
DIR=$3
PATCHES=$4

PATCHES=$(cd "$PATCHES" && pwd)
HINT="run 'make deps-reset' to fetch it again from scratch"

if [ ! -d "$DIR/.git" ]; then
    echo "==> Fetching $(basename "$DIR") @ $REF"
    mkdir -p "$DIR"
    git -C "$DIR" init -q
    git -C "$DIR" fetch -q --depth 1 "$URL" "$REF"
    git -C "$DIR" checkout -q FETCH_HEAD
    echo "$REF" > "$DIR/.holowbark-ref"
fi

# A tag cannot be compared against HEAD, so the ref that was fetched is recorded
# instead. A checkout from a different pin is not ours to reset.
if [ "$(cat "$DIR/.holowbark-ref" 2>/dev/null || true)" != "$REF" ]; then
    echo "error: $DIR was not fetched at $REF — $HINT" >&2
    exit 1
fi

echo "  $(basename "$DIR"): resetting tracked files to $REF"
git -C "$DIR" checkout -q -- .

for patch in "$PATCHES"/*.patch; do
    [ -e "$patch" ] || continue
    if ! git -C "$DIR" apply "$patch"; then
        echo "error: $(basename "$patch") does not apply to $DIR — $HINT" >&2
        exit 1
    fi
    echo "  $(basename "$DIR"): applied $(basename "$patch")"
done
