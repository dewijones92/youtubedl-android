package com.yausername.youtubedl_android

/**
 * Transport-level failure of the persistent resolve daemon (failed to start, died, timed out,
 * protocol breakage) — as opposed to a [YoutubeDLException] carrying a real extraction error.
 * Callers should fall back to the process-per-call path on this, and only this.
 */
class YoutubeDLDaemonException : Exception {
    constructor(message: String?) : super(message)
    constructor(message: String?, e: Throwable?) : super(message, e)
}
