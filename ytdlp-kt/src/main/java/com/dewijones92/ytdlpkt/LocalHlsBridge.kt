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
 * [start] first writes a stub playlist so a player can begin polling immediately; the real
 * playlist takes over once the first segment is cut. When the mux completes, ENDLIST appears and
 * the playlist becomes a normal VOD. [stop] kills ffmpeg and deletes [outputDir].
 *
 * **Seek-triggered remux**: with [startAtSeconds] > 0 the session muxes from that offset
 * (ffmpeg input-level `-ss` range-seeks into the remote streams, typically ~2-3s) and the
 * playlist declares the skipped head `[0..startAt)` as EXT-X-GAP segments, so the player's
 * window still covers the full timeline and positions/seekbar remain in TRUE media time.
 * ffmpeg then writes to a raw playlist which a merger thread rewrites into [playlistFile]
 * with the gap prefix.
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

    /** Media time where this session's real content begins (start of the gap prefix's end). */
    val startAtMs: Long get() = startAtSeconds * 1000L

    /** ffmpeg's own output; == [playlistFile] when no gap prefix is needed. */
    private val rawPlaylist: File =
        if (startAtSeconds > 0) File(outputDir, "raw.m3u8") else playlistFile

    @Volatile
    private var process: Process? = null

    @Volatile
    private var merger: Thread? = null

    val isRunning: Boolean
        get() = process?.let {
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
    @Synchronized
    fun prepareOutput() {
        outputDir.mkdirs()
        if (!playlistFile.exists()) {
            playlistFile.writeText(stubPlaylist(segmentSeconds, startAtSeconds))
        }
    }

    /** Write the stub playlist (if needed) and spawn ffmpeg. Idempotent while running. */
    @Synchronized
    fun start() {
        if (process != null) return
        prepareOutput()
        val spec = YoutubeDL.getInstance().ffmpegExecSpec()
        val command = buildFfmpegArgs(
            spec.binary.absolutePath, videoUrl, audioUrl, segmentSeconds,
            File(outputDir, "seg%05d.m4s").absolutePath, rawPlaylist.absolutePath,
            startAtSeconds,
        )
        val pb = ProcessBuilder(command).redirectErrorStream(true)
        pb.environment().putAll(spec.environment)
        val p = pb.start()
        process = p
        Thread({
            try {
                p.inputStream.bufferedReader().forEachLine { line ->
                    // Old-linker "unused DT entry" warnings are benign noise; keep real output.
                    if (!line.contains("unused DT entry")) Log.i(TAG, line)
                }
            } catch (ignored: Exception) {
                // stream closes when the process is destroyed
            }
            Log.i(TAG, "ffmpeg exited, running=$isRunning dir=$outputDir")
        }, "ytdlp-bridge-log").start()
        if (startAtSeconds > 0) {
            merger = Thread({ mergeLoop() }, "ytdlp-bridge-merge").also { it.start() }
        }
    }

    /**
     * Rewrites ffmpeg's raw playlist into [playlistFile] with the gap prefix whenever it
     * changes; atomic via temp file + rename so the player never reads a half-written playlist.
     */
    private fun mergeLoop() {
        var last = ""
        try {
            while (!Thread.interrupted()) {
                if (rawPlaylist.exists()) {
                    val raw = rawPlaylist.readText()
                    if (raw != last && raw.contains("#EXTINF")) {
                        last = raw
                        val tmp = File(outputDir, "index.m3u8.tmp")
                        tmp.writeText(gapMerge(raw, startAtSeconds, segmentSeconds))
                        tmp.renameTo(playlistFile)
                    }
                    if (raw.contains("#EXT-X-ENDLIST")) {
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

    /** Kill ffmpeg and delete the output dir (deletion happens on a background thread). */
    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        merger?.interrupt()
        merger = null
        Thread({ outputDir.deleteRecursively() }, "ytdlp-bridge-clean").start()
    }

    private companion object {
        private const val TAG = "LocalHlsBridge"
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

/**
 * index.m3u8 = raw playlist's header + gap prefix + raw's segment body (EXT-X-MAP onward).
 * Pure (unit-tested).
 */
internal fun gapMerge(rawText: String, startAtSeconds: Int, segmentSeconds: Int): String {
    val header = StringBuilder()
    val body = StringBuilder()
    var inBody = false
    for (line in rawText.split("\n")) {
        if (line.startsWith("#EXT-X-MAP") || line.startsWith("#EXTINF")) {
            inBody = true
        }
        (if (inBody) body else header).append(line).append('\n')
    }
    return header.toString() + gapEntries(startAtSeconds, segmentSeconds) + body.toString().trimEnd('\n') + "\n"
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
        "-hls_fmp4_init_filename", "init.mp4",
        "-hls_segment_filename", segmentPattern,
        playlistPath,
    )
    return args
}
