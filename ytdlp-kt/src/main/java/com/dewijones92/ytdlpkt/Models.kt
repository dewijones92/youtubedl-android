package com.dewijones92.ytdlpkt

/** Typed, consumer-facing media metadata — the SDK's abstraction over yt-dlp's VideoInfo. */
data class MediaInfo(
    val id: String?,
    val title: String?,
    val durationSeconds: Int,
    val uploader: String?,
    val webpageUrl: String?,
    val thumbnailUrl: String?,
    val formats: List<MediaFormat>,
) {
    /** Progressive (muxed audio+video) formats, best first by height. */
    val muxedFormats: List<MediaFormat>
        get() = formats.filter { !it.isVideoOnly && !it.isAudioOnly }.sortedByDescending { it.height }

    val audioOnlyFormats: List<MediaFormat> get() = formats.filter { it.isAudioOnly }
    val videoOnlyFormats: List<MediaFormat> get() = formats.filter { it.isVideoOnly }
}

/** A single downloadable representation. */
data class MediaFormat(
    val formatId: String?,
    val ext: String?,
    val width: Int,
    val height: Int,
    val vcodec: String?,
    val acodec: String?,
    /** Average (audio) bitrate, kbps — yt-dlp `abr`. */
    val averageBitrateKbps: Int,
    /** Total bitrate, kbps — yt-dlp `tbr` (used for stream selection/itag bitrate). */
    val totalBitrateKbps: Int,
    /** Audio sample rate in Hz — yt-dlp `asr`. */
    val audioSampleRate: Int,
    /** Frames per second — yt-dlp `fps`. */
    val fps: Int,
    /** yt-dlp `format_note` (e.g. "1080p60", "original", language tag). */
    val formatNote: String?,
    val fileSizeBytes: Long,
    val url: String?,
) {
    private fun absent(codec: String?) = codec == null || codec == "none" || codec.isBlank()
    val isAudioOnly: Boolean get() = absent(vcodec) && !absent(acodec)
    val isVideoOnly: Boolean get() = absent(acodec) && !absent(vcodec)
}

/** Streamed progress from [YtdlpKt.download]. */
sealed interface DownloadProgress {
    data class Progress(val percent: Float, val etaSeconds: Long, val line: String) : DownloadProgress
    data class Completed(val exitCode: Int) : DownloadProgress
}

/** Java-friendly progress callback for [YtdlpKt.downloadBlocking]. */
fun interface ProgressListener {
    fun onProgress(percent: Float, etaSeconds: Long, line: String)
}
