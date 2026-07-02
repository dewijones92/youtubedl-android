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
 * [start] first writes a segment-less stub playlist so a player can begin polling immediately;
 * ffmpeg overwrites it once the first segment is cut. When the mux completes, ffmpeg appends
 * ENDLIST and the playlist becomes a normal VOD. [stop] kills ffmpeg and deletes [outputDir].
 *
 * Create via [YtdlpKt.newLocalHlsBridge]. One session per playback; not reusable after [stop].
 */
class LocalHlsBridgeSession internal constructor(
    private val videoUrl: String,
    private val audioUrl: String?,
    val outputDir: File,
    private val segmentSeconds: Int,
) {
    val playlistFile: File = File(outputDir, "index.m3u8")

    @Volatile
    private var process: Process? = null

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
     * player prepare against the (empty, still-live) playlist before any download starts —
     * e.g. for preloaded queue items that may never be played.
     */
    @Synchronized
    fun prepareOutput() {
        outputDir.mkdirs()
        if (!playlistFile.exists()) {
            playlistFile.writeText(stubPlaylist(segmentSeconds))
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
            File(outputDir, "seg%05d.m4s").absolutePath, playlistFile.absolutePath,
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
    }

    /** Kill ffmpeg and delete the output dir (deletion happens on a background thread). */
    @Synchronized
    fun stop() {
        process?.destroy()
        process = null
        Thread({ outputDir.deleteRecursively() }, "ytdlp-bridge-clean").start()
    }

    private companion object {
        private const val TAG = "LocalHlsBridge"
    }
}

/**
 * Segment-less HLS event playlist so a player can poll before ffmpeg writes the real one.
 * TARGETDURATION drives the player's reload cadence (typically half of it).
 */
internal fun stubPlaylist(segmentSeconds: Int): String =
    "#EXTM3U\n" +
        "#EXT-X-VERSION:3\n" +
        "#EXT-X-TARGETDURATION:$segmentSeconds\n" +
        "#EXT-X-MEDIA-SEQUENCE:0\n" +
        "#EXT-X-PLAYLIST-TYPE:EVENT\n"

/** Pure arg-builder (unit-tested): googlevideo -> local HLS, stream copy. */
internal fun buildFfmpegArgs(
    ffmpegPath: String,
    videoUrl: String,
    audioUrl: String?,
    segmentSeconds: Int,
    segmentPattern: String,
    playlistPath: String,
): List<String> {
    val args = mutableListOf(ffmpegPath, "-nostdin", "-loglevel", "warning", "-y", "-i", videoUrl)
    if (audioUrl != null) {
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
