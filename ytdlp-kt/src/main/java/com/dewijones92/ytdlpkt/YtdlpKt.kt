package com.dewijones92.ytdlpkt

import android.content.Context
import com.yausername.aria2c.Aria2c
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.yausername.youtubedl_android.mapper.VideoFormat
import com.yausername.youtubedl_android.mapper.VideoInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Typed, coroutines-first SDK over the youtubedl-android API-23 runtime. Wraps the CLI-style
 * library so consumers (PipePipe, SmartTube, ...) get suspend/Flow calls and typed models instead
 * of building YoutubeDLRequest strings and parsing JSON.
 *
 * Call [init] once (e.g. in Application.onCreate) before any other call.
 */
object YtdlpKt {

    @Volatile
    private var initialized = false

    /** Initialise the native runtime (python/yt-dlp + ffmpeg + aria2c). Idempotent. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        YoutubeDL.getInstance().init(app)
        FFmpeg.getInstance().init(app)
        Aria2c.getInstance().init(app)
        initialized = true
    }

    /** Resolve a single URL to typed metadata (formats, thumbnail, etc.). */
    suspend fun resolve(url: String): MediaInfo = withContext(Dispatchers.IO) {
        ensureInit()
        YoutubeDL.getInstance().getInfo(url).toMediaInfo()
    }

    /**
     * Search a service (default YouTube) and return up to [limit] typed results.
     * Uses yt-dlp's `ytsearchN:` with `--dump-json` (newline-delimited JSON, one entry per line).
     */
    suspend fun search(query: String, limit: Int = 10): List<MediaInfo> = withContext(Dispatchers.IO) {
        ensureInit()
        val request = YoutubeDLRequest("ytsearch$limit:$query")
        request.addOption("--dump-json")
        val response = YoutubeDL.getInstance().execute(request, null, null)
        response.out.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("{") }
            .mapNotNull { line ->
                runCatching { YoutubeDL.objectMapper.readValue(line, VideoInfo::class.java) }.getOrNull()
            }
            .map { it.toMediaInfo() }
            .toList()
    }

    /**
     * Download [url] to [outputTemplate] (a yt-dlp output template, e.g.
     * "/path/%(title)s.%(ext)s"), optionally constraining the format selector (yt-dlp `-f`).
     * Emits [DownloadProgress.Progress] as it runs and a final [DownloadProgress.Completed].
     */
    fun download(
        url: String,
        outputTemplate: String,
        formatSelector: String? = null,
        /**
         * SponsorBlock categories to cut out during download (yt-dlp `--sponsorblock-remove`), e.g.
         * "sponsor" or "all". yt-dlp drives the bundled ffmpeg (auto-wired via --ffmpeg-location) to
         * remove the segments. Null = no SponsorBlock. This is the original "SponsorBlock-on-download"
         * goal, on the from-source API-23 stack.
         */
        sponsorBlockCategories: String? = null,
    ): Flow<DownloadProgress> = callbackFlow {
        ensureInit()
        val request = YoutubeDLRequest(url)
        request.addOption("-o", outputTemplate)
        if (formatSelector != null) request.addOption("-f", formatSelector)
        if (sponsorBlockCategories != null) request.addOption("--sponsorblock-remove", sponsorBlockCategories)

        val job = launch(Dispatchers.IO) {
            try {
                val response = YoutubeDL.getInstance().execute(request, null) { percent, eta, line ->
                    trySend(DownloadProgress.Progress(percent, eta, line))
                }
                trySend(DownloadProgress.Completed(response.exitCode))
                close()
            } catch (t: Throwable) {
                close(t)
            }
        }
        awaitClose { job.cancel() }
    }

    private fun ensureInit() = check(initialized) { "YtdlpKt.init(context) must be called first" }

    // ---- Java-friendly blocking variants (for synchronous callers, e.g. NewPipe's
    // Single.fromCallable). Call off the main thread. ----

    @JvmStatic
    fun resolveBlocking(url: String): MediaInfo = runBlocking { resolve(url) }

    @JvmStatic
    @JvmOverloads
    fun searchBlocking(query: String, limit: Int = 10): List<MediaInfo> =
        runBlocking { search(query, limit) }

    /**
     * Blocking download (for Java callers / a download service thread). Returns yt-dlp's exit code
     * (0 = success). [sponsorBlockCategories] e.g. "sponsor" or "all" removes those segments via
     * yt-dlp + the bundled ffmpeg. Call off the main thread.
     */
    @JvmStatic
    @JvmOverloads
    fun downloadBlocking(
        url: String,
        outputTemplate: String,
        formatSelector: String? = null,
        sponsorBlockCategories: String? = null,
        listener: ProgressListener? = null,
    ): Int = runBlocking {
        var exit = -1
        download(url, outputTemplate, formatSelector, sponsorBlockCategories).collect { p ->
            when (p) {
                is DownloadProgress.Progress -> listener?.onProgress(p.percent, p.etaSeconds, p.line)
                is DownloadProgress.Completed -> exit = p.exitCode
            }
        }
        exit
    }
}

// ---- mapping (pure, unit-tested) ----

internal fun VideoInfo.toMediaInfo(): MediaInfo = MediaInfo(
    id = id,
    title = title,
    durationSeconds = duration,
    uploader = uploader,
    webpageUrl = webpageUrl,
    thumbnailUrl = thumbnail,
    formats = formats?.map { it.toMediaFormat() } ?: emptyList(),
)

internal fun VideoFormat.toMediaFormat(): MediaFormat = MediaFormat(
    formatId = formatId,
    ext = ext,
    width = width,
    height = height,
    vcodec = vcodec,
    acodec = acodec,
    averageBitrateKbps = abr,
    totalBitrateKbps = tbr,
    audioSampleRate = asr,
    fps = fps,
    formatNote = formatNote,
    fileSizeBytes = if (fileSize > 0) fileSize else fileSizeApproximate,
    url = url,
)
