package com.dewijones92.ytdlpkt

import android.util.Log
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File

/**
 * Local playback bridge: the bundled ffmpeg fetches a video-only + audio-only stream pair
 * (yt-dlp-resolved URLs) and remuxes them with stream copy (no re-encode) into a GROWING local
 * HLS event playlist under [outputDir]. A player then plays [playlistFile] from local storage and
 * never contacts the remote host itself — sidestepping adaptive containers (fragmented MP4 /
 * adaptive WebM) that players can't consume as progressive streams.
 *
 * **Seek-triggered remux**: with [startAtSeconds] > 0 the session muxes from that offset
 * (ffmpeg input-level `-ss` range-seeks into the remote streams, typically ~2-3s) and the
 * playlist declares the skipped head `[0..startAt)` as EXT-X-GAP segments, so the player's
 * window still covers the full timeline and positions/seekbar remain in TRUE media time.
 *
 * **Watchdog**: ffmpeg runs as a sequence of RUNS. If a run dies before writing ENDLIST
 * (network drop, crash, kill), the session restarts it at the point the mux stopped and stitches
 * the new run into the playlist behind an EXT-X-DISCONTINUITY — the player just sees the
 * playlist resume growing (no reload, usually no visible stall). Repeated failures without
 * progress give up by writing ENDLIST, so playback ends cleanly at the muxed edge instead of
 * buffering forever. ffmpeg always writes per-run raw playlists; a merger thread composes
 * [playlistFile] from them (atomic tmp+rename).
 *
 * Create via [YtdlpKt.newLocalHlsBridge]. One session per playback; not reusable after [stop].
 */
class LocalHlsBridgeSession internal constructor(
    private val videoUrl: String,
    private val audioUrl: String?,
    val outputDir: File,
    private val segmentSeconds: Int,
    private val startAtSeconds: Int = 0,
) {
    val playlistFile: File = File(outputDir, "index.m3u8")

    /** Media time where this session's real (non-gap) content begins. */
    val startAtMs: Long get() = startAtSeconds * 1000L

    private val lock = Object()
    private val runPlaylists = ArrayList<File>() // raw ffmpeg playlist per run, in order
    private var process: Process? = null
    private var merger: Thread? = null
    private var stopped = false
    private var failedStreak = 0
    private var segmentsAtLastStart = 0

    val isRunning: Boolean
        get() = synchronized(lock) { process }?.let {
            // Process.isAlive needs API 26; exitValue throws while still running.
            try {
                it.exitValue()
                false
            } catch (e: IllegalThreadStateException) {
                true
            }
        } ?: false

    /**
     * Create [outputDir] and write the stub playlist WITHOUT spawning ffmpeg. Cheap; lets a
     * player prepare against the (still-live) playlist before any download starts —
     * e.g. for preloaded queue items that may never be played.
     */
    fun prepareOutput() {
        synchronized(lock) {
            outputDir.mkdirs()
            if (!playlistFile.exists()) {
                playlistFile.writeText(stubPlaylist(segmentSeconds, startAtSeconds))
            }
        }
    }

    /** Write the stub playlist (if needed) and spawn ffmpeg. Idempotent while running. */
    fun start() {
        synchronized(lock) {
            if (stopped || process != null) return
            prepareOutput()
            startRunLocked(startAtSeconds)
            if (merger == null) {
                merger = Thread({ mergeLoop() }, "ytdlp-bridge-merge").also { it.start() }
            }
        }
    }

    /** Spawn one ffmpeg run muxing from [fromSeconds]; caller must hold [lock]. */
    private fun startRunLocked(fromSeconds: Int) {
        val runIndex = runPlaylists.size
        val raw = File(outputDir, "run$runIndex.m3u8")
        runPlaylists.add(raw)
        segmentsAtLastStart = segmentCount()
        val spec = YoutubeDL.getInstance().ffmpegExecSpec()
        val command = buildFfmpegArgs(
            spec.binary.absolutePath, videoUrl, audioUrl, segmentSeconds,
            File(outputDir, "run${runIndex}_%05d.m4s").absolutePath, raw.absolutePath,
            fromSeconds, "init$runIndex.mp4",
        )
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment().putAll(spec.environment)
        val p = pb.start()
        process = p
        Log.i(TAG, "run $runIndex started from ${fromSeconds}s")
        Thread({ drainAndSupervise(p, raw) }, "ytdlp-bridge-run$runIndex").start()
    }

    /** Drain a run's output, then decide: finished / restart at the mux edge / give up. */
    private fun drainAndSupervise(p: Process, raw: File) {
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                // Old-linker "unused DT entry" warnings are benign noise; keep real output.
                if (!line.contains("unused DT entry")) Log.i(TAG, line)
            }
        } catch (ignored: Exception) {
            // stream closes when the process is destroyed
        }
        val backoffMs: Long
        synchronized(lock) {
            if (stopped || process !== p) return
            process = null
            if (raw.exists() && raw.readText().contains("#EXT-X-ENDLIST")) {
                Log.i(TAG, "run finished (ENDLIST); mux complete")
                return
            }
            // Died without finishing. Progress since the last (re)start resets the streak, so
            // an occasional network blip doesn't eat the retry budget of a long session.
            failedStreak = if (segmentCount() > segmentsAtLastStart) 1 else failedStreak + 1
            if (failedStreak > MAX_FAILED_STREAK) {
                Log.e(TAG, "ffmpeg died $failedStreak times without progress; giving up — "
                        + "finalizing playlist so playback ends at the muxed edge")
                finalizeIndexLocked()
                return
            }
            backoffMs = 1000L * (1 shl (failedStreak - 1)) // 1s, 2s, 4s
            Log.w(TAG, "ffmpeg died unfinished (streak=$failedStreak); "
                    + "restarting at mux edge in ${backoffMs}ms")
        }
        try {
            Thread.sleep(backoffMs)
        } catch (e: InterruptedException) {
            return
        }
        synchronized(lock) {
            if (stopped || process != null) return
            startRunLocked(startAtSeconds + muxedSecondsLocked())
        }
    }

    /** Whole seconds of media muxed so far across all runs; caller must hold [lock]. */
    private fun muxedSecondsLocked(): Int =
        runPlaylists.sumOf { if (it.exists()) sumExtinfSeconds(it.readText()) else 0.0 }.toInt()

    private fun segmentCount(): Int =
        outputDir.listFiles { _, name -> name.endsWith(".m4s") }?.size ?: 0

    /** Compose the final playlist with ENDLIST so the player treats it as finished VOD. */
    private fun finalizeIndexLocked() {
        val rawTexts = runPlaylists.filter { it.exists() }.map { it.readText() }
        val tmp = File(outputDir, "index.m3u8.tmp")
        tmp.writeText(composeIndex(rawTexts, startAtSeconds, segmentSeconds, forceEnd = true))
        tmp.renameTo(playlistFile)
    }

    /**
     * Recomposes [playlistFile] from all runs' raw playlists whenever they change; atomic via
     * temp file + rename so the player never reads a half-written playlist.
     */
    private fun mergeLoop() {
        var last = ""
        try {
            while (!Thread.interrupted()) {
                val rawTexts: List<String>
                val stoppedNow: Boolean
                synchronized(lock) {
                    rawTexts = runPlaylists.filter { it.exists() }.map { it.readText() }
                    stoppedNow = stopped
                }
                if (stoppedNow) return
                if (rawTexts.any { it.contains("#EXTINF") }) {
                    val composed = composeIndex(rawTexts, startAtSeconds, segmentSeconds)
                    if (composed != last) {
                        last = composed
                        val tmp = File(outputDir, "index.m3u8.tmp")
                        tmp.writeText(composed)
                        tmp.renameTo(playlistFile)
                    }
                    if (composed.contains("#EXT-X-ENDLIST")) {
                        return
                    }
                }
                Thread.sleep(400)
            }
        } catch (ignored: InterruptedException) {
            // stop() interrupts us; nothing to clean up
        } catch (e: Exception) {
            Log.e(TAG, "playlist merger failed", e)
        }
    }

    /**
     * Test seam: kill the current ffmpeg as if it died externally, WITHOUT stopping the session,
     * so the watchdog restart path runs. Returns false if no run is active. (Reproduces, in an
     * automated test, the hand-verified "kill ffmpeg behind the app's back mid-play" scenario.)
     */
    fun simulateFfmpegDeathForTest(): Boolean = synchronized(lock) {
        val p = process ?: return false
        p.destroy()
        true
    }

    /** Kill ffmpeg and delete the output dir (deletion happens on a background thread). */
    fun stop() {
        synchronized(lock) {
            stopped = true
            process?.destroy()
            process = null
            merger?.interrupt()
            merger = null
        }
        Thread({ outputDir.deleteRecursively() }, "ytdlp-bridge-clean").start()
    }

    private companion object {
        private const val TAG = "LocalHlsBridge"

        /** Consecutive deaths with zero new segments before giving up (URL expired, offline). */
        private const val MAX_FAILED_STREAK = 3
    }
}

/**
 * Stub playlist so a player can poll before ffmpeg writes the real one. With a start offset the
 * stub already carries the gap prefix, so a pending seek to the offset resolves against a window
 * of the right size while the first real segment is still being fetched.
 */
internal fun stubPlaylist(segmentSeconds: Int, startAtSeconds: Int = 0): String =
    "#EXTM3U\n" +
        "#EXT-X-VERSION:7\n" +
        "#EXT-X-TARGETDURATION:$segmentSeconds\n" +
        "#EXT-X-MEDIA-SEQUENCE:0\n" +
        "#EXT-X-PLAYLIST-TYPE:EVENT\n" +
        gapEntries(startAtSeconds, segmentSeconds)

/**
 * EXT-X-GAP entries declaring `[0..totalSeconds)` as present-but-unavailable: they give the
 * window its true media-time offset without any fetchable content. Players must not request
 * gap URIs, so the names never exist on disk.
 */
internal fun gapEntries(totalSeconds: Int, segmentSeconds: Int): String {
    val sb = StringBuilder()
    var remaining = totalSeconds
    while (remaining > 0) {
        val d = minOf(segmentSeconds, remaining)
        sb.append("#EXTINF:").append(d).append(".000000,\n")
            .append("#EXT-X-GAP\n")
            .append("gap").append(remaining).append(".m4s\n")
        remaining -= d
    }
    return sb.toString()
}

/** Sum of #EXTINF durations in a playlist body (media seconds muxed by that run). */
internal fun sumExtinfSeconds(playlistText: String): Double =
    EXTINF_REGEX.findAll(playlistText).sumOf { it.groupValues[1].toDouble() }

private val EXTINF_REGEX = Regex("#EXTINF:([0-9.]+)")

/**
 * Compose index.m3u8 from the runs' raw ffmpeg playlists: shared header, EXT-X-GAP head for a
 * seek-jump start, then each run's body (its EXT-X-MAP + segments) with EXT-X-DISCONTINUITY
 * between runs (timestamps restart per run). ENDLIST is kept only from the LAST run — earlier
 * runs never finished (that's why there are later ones) — or forced when giving up. Pure
 * (unit-tested).
 */
internal fun composeIndex(
    rawTexts: List<String>,
    startAtSeconds: Int,
    segmentSeconds: Int,
    forceEnd: Boolean = false,
): String {
    val sb = StringBuilder()
    sb.append("#EXTM3U\n#EXT-X-VERSION:7\n")
    val targetDuration = rawTexts.mapNotNull {
        TARGET_DURATION_REGEX.find(it)?.groupValues?.get(1)?.toIntOrNull()
    }.maxOrNull() ?: segmentSeconds
    sb.append("#EXT-X-TARGETDURATION:").append(targetDuration).append('\n')
    sb.append("#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n")
    sb.append(gapEntries(startAtSeconds, segmentSeconds))
    var wroteRun = false
    var lastHadEnd = false
    for (raw in rawTexts) {
        val body = playlistBody(raw)
        if (body.isEmpty()) {
            continue
        }
        if (wroteRun) {
            sb.append("#EXT-X-DISCONTINUITY\n")
        }
        lastHadEnd = raw.contains("#EXT-X-ENDLIST")
        sb.append(body.trimEnd('\n')).append('\n')
        wroteRun = true
    }
    if (lastHadEnd || forceEnd) {
        sb.append("#EXT-X-ENDLIST\n")
    }
    return sb.toString()
}

private val TARGET_DURATION_REGEX = Regex("#EXT-X-TARGETDURATION:(\\d+)")

/** A raw playlist's segment body: from the first EXT-X-MAP/EXTINF line, minus ENDLIST. */
internal fun playlistBody(rawText: String): String {
    val sb = StringBuilder()
    var inBody = false
    for (line in rawText.split("\n")) {
        if (!inBody && (line.startsWith("#EXT-X-MAP") || line.startsWith("#EXTINF"))) {
            inBody = true
        }
        if (inBody && line.isNotEmpty() && line != "#EXT-X-ENDLIST") {
            sb.append(line).append('\n')
        }
    }
    return sb.toString()
}

/** Pure arg-builder (unit-tested): googlevideo -> local HLS, stream copy. */
internal fun buildFfmpegArgs(
    ffmpegPath: String,
    videoUrl: String,
    audioUrl: String?,
    segmentSeconds: Int,
    segmentPattern: String,
    playlistPath: String,
    startAtSeconds: Int = 0,
    initFileName: String = "init.mp4",
): List<String> {
    val args = mutableListOf(ffmpegPath, "-nostdin", "-loglevel", "warning", "-y")
    // Input-level -ss: ffmpeg range-seeks into the remote mp4/webm (seconds, not a re-download).
    if (startAtSeconds > 0) args += listOf("-ss", startAtSeconds.toString())
    args += listOf("-i", videoUrl)
    if (audioUrl != null) {
        if (startAtSeconds > 0) args += listOf("-ss", startAtSeconds.toString())
        args += listOf("-i", audioUrl, "-map", "0:v", "-map", "1:a")
    } else {
        args += listOf("-map", "0")
    }
    args += listOf(
        "-c", "copy",
        "-f", "hls",
        "-hls_time", segmentSeconds.toString(),
        "-hls_playlist_type", "event",
        // fMP4 segments (not MPEG-TS): MPEG-TS can't carry VP9/AV1 (ffmpeg muxes them as an
        // unrecognized "private data stream" that the demuxer drops on read → the player gets
        // audio only). fMP4 carries h264 + VP9 + AV1 cleanly, which matters because yt-dlp's
        // higher-quality video-only formats are usually VP9.
        "-hls_segment_type", "fmp4",
        // Write segments/playlist to a temp file and rename on completion, so a concurrent
        // reader never sees a half-written file.
        "-hls_flags", "temp_file",
        "-hls_fmp4_init_filename", initFileName,
        "-hls_segment_filename", segmentPattern,
        playlistPath,
    )
    return args
}
