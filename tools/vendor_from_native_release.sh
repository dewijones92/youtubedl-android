#!/usr/bin/env bash
# L3: vendor the from-source API-23 native runtime from the termux-packages fork release
# (native-vX.Y.Z) into this repo's jniLibs.
#
# This REPLACES the old approach (yausername's API-24 binaries binary-patched on-disk with an
# LD_PRELOAD shim + page-padding). The bundles consumed here are built FROM SOURCE targeting
# API 23, so they have ZERO undefined API-24 symbols and need no shim.
#
# The bundle -> module -> loader layout is NOT hardcoded here: it is driven by the
# runtime-bundle-manifest.json published in the same release (the single source of the L1->L3
# contract, also used by L1's collect-runtime-bundle.sh). The 'out' loader names and 'zip' names
# in that manifest must match the youtubedl-android runtime constants (YoutubeDL.kt / FFmpeg.kt /
# Aria2c.kt). 64-bit only (arm64-v8a + x86_64); any 32-bit ABI dirs are removed.
#
# Usage: tools/vendor_from_native_release.sh <native-tag> [owner/repo]
set -euo pipefail
TAG="${1:?usage: vendor_from_native_release.sh <native-tag> [owner/repo]}"
REPO="${2:-dewijones92/termux-packages}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

echo "Downloading $TAG runtime bundles + manifest from $REPO ..."
gh release download "$TAG" --repo "$REPO" --dir "$WORK" --clobber \
  --pattern 'bundle-*.tar' --pattern 'runtime-bundle-manifest.json'
MANIFEST="$WORK/runtime-bundle-manifest.json"
[ -f "$MANIFEST" ] || { echo "ERROR: runtime-bundle-manifest.json missing from $TAG"; exit 1; }

# Page-pad to a 4096 boundary: the strict API-23 linker refuses a final LOAD segment whose contents
# end exactly at EOF. Harmless on the from-source libs; applied to loader exes for safety.
pad4096(){ local f="$1" sz p; sz=$(stat -c%s "$f"); p=$(( (4096 - sz%4096)%4096 )); [ "$p" -gt 0 ] && head -c "$p" /dev/zero >> "$f" || true; }

# termux-arch -> android-abi pairs from the manifest
mapfile -t ARCH_PAIRS < <(jq -r '.abis | to_entries[] | "\(.key) \(.value)"' "$MANIFEST")

for pair in "${ARCH_PAIRS[@]}"; do
  arch="${pair%% *}"; abi="${pair##* }"
  echo "== $arch -> $abi =="
  while IFS= read -r b; do
    name="$(printf '%s' "$b" | jq -r '.name')"
    module="$(printf '%s' "$b" | jq -r '.module')"
    zip="$(printf '%s' "$b" | jq -r '.zip // empty')"
    tar="$WORK/bundle-$name-$arch.tar"
    [ -f "$tar" ] || { echo "ERROR: missing bundle-$name-$arch.tar in $TAG"; exit 1; }

    d="$WORK/$name-$arch"; rm -rf "$d"; mkdir -p "$d"; tar xf "$tar" -C "$d"
    jnidir="$ROOT/$module/src/main/jniLibs/$abi"; mkdir -p "$jnidir"

    # loaders: rename usr/bin/<bin> -> jniLibs/<abi>/<out>
    while IFS= read -r ldr; do
      out="${ldr%% *}"; bin="${ldr##* }"
      cp "$d/usr/bin/$bin" "$jnidir/$out"; pad4096 "$jnidir/$out"
    done < <(printf '%s' "$b" | jq -r '.loaders[] | "\(.out) \(.bin)"')

    # zip: pack the usr/ tree as <out>.zip.so (preserves symlinks for SONAME lookup)
    if [ -n "$zip" ]; then
      rm -f "$jnidir/$zip"
      ( cd "$d" && zip --symlinks -qr -X "$jnidir/$zip" usr )
    fi
  done < <(jq -c '.bundles[]' "$MANIFEST")
done

# Keep only the ABIs the manifest defines; remove any leftover (e.g. dropped 32-bit) dirs.
keep="$(jq -r '.abis | to_entries[] | .value' "$MANIFEST" | sort -u)"
for module in $(jq -r '.bundles[].module' "$MANIFEST" | sort -u); do
  base="$ROOT/$module/src/main/jniLibs"
  [ -d "$base" ] || continue
  for d in "$base"/*/; do
    a="$(basename "$d")"
    grep -qx "$a" <<<"$keep" || { echo "removing stale ABI $module/$a"; rm -rf "$d"; }
  done
done

echo
echo "Vendored $TAG (from source, API 23) into jniLibs. ABIs: $(tr '\n' ' ' <<<"$keep")"
for module in $(jq -r '.bundles[].module' "$MANIFEST" | sort -u); do
  find "$ROOT/$module/src/main/jniLibs" -maxdepth 2 -name '*.so' -printf '  %P  (%s bytes)\n' 2>/dev/null | sort
done
