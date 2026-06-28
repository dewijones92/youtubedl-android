#!/usr/bin/env bash
# L3: vendor the from-source API-23 native runtime from the termux-packages fork release
# (native-vX.Y.Z) into this repo's jniLibs.
#
# This REPLACES the old approach (yausername's API-24 binaries binary-patched on-disk with an
# LD_PRELOAD shim + page-padding via tools/patch_natives_api23.sh). The bundles consumed here are
# built FROM SOURCE targeting API 23, so they have ZERO undefined API-24 symbols and need no shim.
#
# Per ABI, lays out the bundles exactly as YoutubeDL.kt expects:
#   library/jniLibs/<abi>/libpython.so      <- bundle-python  usr/bin/python3.13  (loader exe)
#   library/jniLibs/<abi>/libpython.zip.so  <- bundle-python  usr/ tree (zipped, incl. stdlib+certs)
#   library/jniLibs/<abi>/libqjs.so         <- bundle-qjs     usr/bin/qjs         (loader exe)
#   ffmpeg/jniLibs/<abi>/libffmpeg.so       <- bundle-ffmpeg  usr/bin/ffmpeg
#   ffmpeg/jniLibs/<abi>/libffprobe.so      <- bundle-ffmpeg  usr/bin/ffprobe
#   ffmpeg/jniLibs/<abi>/libffmpeg.zip.so   <- bundle-ffmpeg  usr/ tree (zipped)
#   aria2c/jniLibs/<abi>/libaria2c.so       <- bundle-aria2c  usr/bin/aria2c
#   aria2c/jniLibs/<abi>/libaria2c.zip.so   <- bundle-aria2c  usr/ tree (zipped)
#
# 64-bit only (matches L1): arm64-v8a + x86_64. Any 32-bit ABI dirs are removed (see README).
#
# Usage: tools/vendor_from_native_release.sh <native-tag> [owner/repo]
set -euo pipefail
TAG="${1:?usage: vendor_from_native_release.sh <native-tag> [owner/repo]}"
REPO="${2:-dewijones92/termux-packages}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# termux arch -> android ABI
declare -A ABI=( [aarch64]=arm64-v8a [x86_64]=x86_64 )

echo "Downloading $TAG runtime bundles from $REPO ..."
gh release download "$TAG" --repo "$REPO" --dir "$WORK" --pattern 'bundle-*.tar' --clobber

# Page-pad to a 4096 boundary: the strict API-23 linker refuses a final LOAD segment whose contents
# end exactly at EOF. Harmless on the from-source libs; applied to loader exes for safety.
pad4096(){ local f="$1" sz p; sz=$(stat -c%s "$f"); p=$(( (4096 - sz%4096)%4096 )); [ "$p" -gt 0 ] && head -c "$p" /dev/zero >> "$f" || true; }

extract(){ local module="$1" arch="$2" d="$WORK/$1-$2"; rm -rf "$d"; mkdir -p "$d"; tar xf "$WORK/bundle-$module-$arch.tar" -C "$d"; printf '%s' "$d"; }

zip_usr(){ local srcdir="$1" dest="$2"; rm -f "$dest"; ( cd "$srcdir" && zip --symlinks -qr -X "$dest" usr ); }

for arch in "${!ABI[@]}"; do
  abi="${ABI[$arch]}"
  echo "== $arch -> $abi =="
  for module in python qjs ffmpeg aria2c; do
    [ -f "$WORK/bundle-$module-$arch.tar" ] || { echo "ERROR: missing bundle-$module-$arch.tar in $TAG"; exit 1; }
  done

  # library module: python (loader+zip) + qjs (loader only; self-contained)
  pyd="$(extract python "$arch")"; qjd="$(extract qjs "$arch")"
  ld="$ROOT/library/src/main/jniLibs/$abi"; mkdir -p "$ld"
  cp "$pyd/usr/bin/python3.13" "$ld/libpython.so"; pad4096 "$ld/libpython.so"
  zip_usr "$pyd" "$ld/libpython.zip.so"
  cp "$qjd/usr/bin/qjs" "$ld/libqjs.so"; pad4096 "$ld/libqjs.so"

  # ffmpeg module
  fd="$(extract ffmpeg "$arch")"
  fld="$ROOT/ffmpeg/src/main/jniLibs/$abi"; mkdir -p "$fld"
  cp "$fd/usr/bin/ffmpeg"  "$fld/libffmpeg.so";  pad4096 "$fld/libffmpeg.so"
  cp "$fd/usr/bin/ffprobe" "$fld/libffprobe.so"; pad4096 "$fld/libffprobe.so"
  zip_usr "$fd" "$fld/libffmpeg.zip.so"

  # aria2c module
  ad="$(extract aria2c "$arch")"
  ald="$ROOT/aria2c/src/main/jniLibs/$abi"; mkdir -p "$ald"
  cp "$ad/usr/bin/aria2c" "$ald/libaria2c.so"; pad4096 "$ald/libaria2c.so"
  zip_usr "$ad" "$ald/libaria2c.zip.so"
done

# L1 is 64-bit only; remove any leftover 32-bit ABI dirs.
for m in library ffmpeg aria2c; do
  for old in armeabi-v7a x86; do rm -rf "$ROOT/$m/src/main/jniLibs/$old"; done
done

echo
echo "Vendored $TAG (from source, API 23) into jniLibs: arm64-v8a + x86_64. 32-bit removed."
echo "Resulting layout:"
for m in library ffmpeg aria2c; do find "$ROOT/$m/src/main/jniLibs" -maxdepth 2 -name '*.so' -printf '  %P  (%s bytes)\n' 2>/dev/null | sort; done
