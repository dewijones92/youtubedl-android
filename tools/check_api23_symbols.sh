#!/usr/bin/env bash
# L3 verify-gate (deterministic, no emulator/network): assert no bundled native binary references a
# libc symbol that Android bionic only added at API 24+. If any does, it would fail to link on
# Android 6.0 (API 23) at runtime. From-source (termux-packages native-v*) binaries should have
# none — this gate catches a regression (e.g. a prebuilt API-24 dep sneaking back into a bundle).
#
# Scans every loader exe (lib*.so) and every *.so inside the lib*.zip.so bundles across all
# jniLibs ABIs. Uses `nm -D -u` / `readelf --dyn-syms` to read UNDEFINED dynamic symbols.
#
# Usage: tools/check_api23_symbols.sh [repo-root]
set -euo pipefail
ROOT="${1:-$(cd "$(dirname "$0")/.." && pwd)}"
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT

# Symbols bionic added at API 24+ (Nougat) — absent from the API-23 libc/libm/libdl, so a GLOBAL
# (non-weak) undefined reference is a hard load failure on Android 6.0.
# NOTE: fseeko/ftello are deliberately NOT here. On LP64 (our only target — 64-bit) they are base
# symbols available at API 23; the API-24 introduction is the 32-bit _FILE_OFFSET_BITS=64 variant
# (fseeko64), which was the i686-only wall — and i686 is dropped. The NDK's own libc++_shared.so
# references fseeko/ftello globally and ships for minSdk 21, confirming LP64 availability.
# memfd_create is also omitted: every reference to it (openssl/ffmpeg) is WEAK, and the gate only
# flags GLOBAL undefined symbols anyway.
API24_SYMS='^(getifaddrs|freeifaddrs|if_nameindex|if_freenameindex|if_indextoname|if_nametoindex|lockf|lockf64|preadv|preadv64|pwritev|pwritev64|in6addr_any|in6addr_loopback|strchrnul|__aeabi_memcpy|__aeabi_memcpy4|__aeabi_memcpy8|__aeabi_memmove|__aeabi_memmove4|__aeabi_memmove8|__aeabi_memset|__aeabi_memset4|__aeabi_memset8|__aeabi_memclr|__aeabi_memclr4|__aeabi_memclr8)$'

# Print only GLOBAL undefined dynamic symbols. WEAK undefined symbols (e.g. openssl/ffmpeg's
# optional memfd_create) resolve to 0 on bionic and are guarded at the call site, so they do NOT
# fail to load on API 23 — only an unresolved GLOBAL symbol is a hard runtime link failure.
reader() {
  readelf --dyn-syms "$1" 2>/dev/null | awk '$5=="GLOBAL" && $7=="UND" {print $8}' | sed 's/@.*//'
}

fail=0; scanned=0
scan_elf() {  # file label
  local f="$1" label="$2" bad
  bad="$(reader "$f" | grep -E "$API24_SYMS" | sort -u || true)"
  scanned=$((scanned+1))
  if [ -n "$bad" ]; then
    echo "FAIL  $label"
    echo "$bad" | sed 's/^/        API24+ symbol: /'
    fail=1
  fi
}

for module in library ffmpeg aria2c; do
  base="$ROOT/$module/src/main/jniLibs"
  [ -d "$base" ] || continue
  while IFS= read -r abi; do
    abiname="$(basename "$abi")"
    # loader exes (lib*.so but not the *.zip.so archives)
    while IFS= read -r so; do scan_elf "$so" "$module/$abiname/$(basename "$so")"; done \
      < <(find "$abi" -maxdepth 1 -name '*.so' ! -name '*.zip.so')
    # contents of each lib*.zip.so
    while IFS= read -r zip; do
      d="$WORK/$(basename "$zip" .zip.so)-$abiname"; rm -rf "$d"; mkdir -p "$d"
      unzip -qo "$zip" -d "$d" 2>/dev/null || continue
      while IFS= read -r so; do
        scan_elf "$so" "$module/$abiname/$(basename "$zip")::$(basename "$so")"
      done < <(find "$d" -type f -name '*.so*')
    done < <(find "$abi" -maxdepth 1 -name '*.zip.so')
  done < <(find "$base" -mindepth 1 -maxdepth 1 -type d)
done

echo "Scanned $scanned ELF objects across jniLibs."
if [ "$fail" -ne 0 ]; then
  echo "API-23 symbol gate: FAILED (see above)."; exit 1
fi
echo "API-23 symbol gate: PASS — no API-24+ libc symbols referenced."
