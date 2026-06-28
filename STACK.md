# yt-dlp on Android 6.0 — the full stack (L1 → L5)

A ground-up, all-public, build-from-source stack that runs **yt-dlp on Android 6.0 (API 23)**
and consumes it all the way up into a working YouTube client (PipePipe), with the YouTube
extractor handled by yt-dlp instead of (or alongside) NewPipeExtractor.

Every layer is its own fork/repo with its own CI/CD; each publishes the artifact the layer
above consumes. **Proven end-to-end on a real API-23 device** — from source build to actual
YouTube playback in the PipePipe app.

```
L1  termux-packages (fork)      from-source native runtime for API 23
      │   CPython · ffmpeg · quickjs-ng · aria2  →  self-contained bundles + checksums + SLSA provenance
      ▼   release: native-v0.3.0
L2  yt-dlp (the .pyz)            bundled zipapp, kept current by a weekly auto-bump PR
      ▼   library/src/main/res/raw/ytdlp  (2026.06.09)
L3  youtubedl-android (fork)     Kotlin wrapper; vendors L1's bundles into jniLibs (no shim)
      │   tier-B CI: symbol gate + API-23 emulator gate; published via JitPack
      ▼   release: v0.23.0-api23  (com.github.dewijones92.youtubedl-android:{common,library,ffmpeg,aria2c})
L4  ytdlp-kt (module here)       typed coroutines/Flow SDK over L3
      │   YtdlpKt.resolve()/search()/download(Flow) + blocking variants; MediaInfo/MediaFormat
      ▼   com.github.dewijones92.youtubedl-android:ytdlp-kt:v0.23.0-api23
L5  PipePipeClient + PipePipeExtractor (forks)
          one dep on ytdlp-kt; YtdlpHelper → ytdlp-kt → StreamInfo; feature-flagged
          yt-dlp-primary for YouTube with NewPipe fallback. Plays on Android 6.0.
```

## The core thesis

Termux's native packages target API 24+. Bionic added a batch of libc functions at API 24
(`lockf`, `preadv`/`pwritev`, `getifaddrs`, `in6addr_any`, `strchrnul`, `memfd_create`, …).
The original approach (yausername) shipped API-24 binaries and back-filled the missing symbols
with an `LD_PRELOAD` shim + page-padding.

**This fork builds from source for API 23 instead.** Setting `TERMUX_PKG_API_LEVEL=23` makes each
package's `configure` feature-detect those functions as absent and use fallbacks, so the binaries
have **zero undefined API-24 symbols** — no shim needed. Where a dependency couldn't adapt, it was
leaned out (ffmpeg dropped libsrt/libssh/libzmq/all external encoders; aria2 dropped c-ares/libxml2)
or configure-adapted. **64-bit only** (arm64-v8a + x86_64): both 32-bit ABIs hit 32-bit-specific
API-24 symbols (`__aeabi_mem*`, 32-bit `fseeko64`).

## Layer detail

- **L1 — [termux-packages fork](https://github.com/dewijones92/termux-packages)** (`native-v0.3.0`).
  `scripts/collect-runtime-bundle.sh` resolves each consumer binary's full `.so` closure from the
  build prefix and emits stripped, self-contained per-module bundles (python/qjs/ffmpeg/aria2c) +
  `runtime-bundle-manifest.json` (the single source of the L1→L3 contract). Built in CI, checksummed,
  SLSA-attested.
- **L2 — yt-dlp.** The zipapp at `library/src/main/res/raw/ytdlp`. `.github/workflows/update-ytdlp.yml`
  bumps it weekly via PR (deterministic gates run; real-network extraction validated off-CI).
- **L3 — [youtubedl-android fork](https://github.com/dewijones92/youtubedl-android)** (`v0.19.0`→`v0.23.0-api23`).
  `tools/vendor_from_native_release.sh` reads the manifest and lays L1's bundles into jniLibs.
  `tools/check_api23_symbols.sh` is a deterministic gate (fails on any GLOBAL undefined API-24 symbol);
  `ci.yml` adds an **API-23 x86_64 emulator gate** (`RuntimeSmokeTest` runs every binary on Android 6.0).
  Published via JitPack.
- **L4 — `ytdlp-kt`** (module in this repo). A typed, coroutines/Flow SDK: `YtdlpKt.init/resolve/
  search/download` (+ `resolveBlocking`/`searchBlocking` for Java callers), `MediaInfo`/`MediaFormat`.
  Consumers get the API + L3's runtime jniLibs from a single dependency.
- **L5 — [PipePipeClient](https://github.com/dewijones92/PipePipeClient) +
  [PipePipeExtractor](https://github.com/dewijones92/PipePipeExtractor) forks.** Depend on `ytdlp-kt`;
  `StreamingService.ytdlpEnabled` flag; `ExtractorHelper.getNewStreamInfo` routes YouTube through
  yt-dlp first (NewPipe fallback); `YtdlpHelper` maps `MediaInfo` → NewPipe `StreamInfo`.

## Known caveats (honest)

- **YouTube is a moving target.** Extraction proven for real videos on a residential network, but
  YouTube changes constantly (cipher/nsig/clients/DRM). The L2 auto-bump + the NewPipe fallback are
  the mitigations; expect occasional breakage needing a yt-dlp bump.
- **CI can't validate real extraction.** GitHub's datacenter IPs are YouTube-bot-blocked
  ("Sign in to confirm you're not a bot"). CI runs deterministic gates only; real-network checks are
  local / on-device.
- **L5 mapping is PoC-level.** Plays fine, but skips audio-language tracks, live streams, subtitles,
  rich metadata and DASH byte-ranges.
- Playback/HW-decode is the player's job (ExoPlayer/MediaCodec); ffmpeg here only mux/copies (no
  re-encode), so it carries no hardware-decode dependency.
