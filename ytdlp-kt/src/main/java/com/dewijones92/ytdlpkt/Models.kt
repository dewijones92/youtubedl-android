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
    val averageBitrateKbps: Int,
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
