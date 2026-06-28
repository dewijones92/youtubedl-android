package com.dewijones92.ytdlpkt

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * NON-GATING (CI runs this with continue-on-error): a REAL on-device YouTube extraction via the
 * SDK on API 23. Unlike RuntimeSmokeTest (which only proves binaries load/run), this proves
 * yt-dlp can actually resolve a video end-to-end — exercising the JS runtime (nsig) and network.
 * It is expected to be flaky (YouTube 403 / SABR / PO-token churn), so failures must not gate the
 * pipeline; it exists for signal on the real-extraction risk ahead of L5 (PipePipe).
 */
@RunWith(AndroidJUnit4::class)
class NetworkExtractionSmokeTest {

    @Test
    fun resolveRealYouTubeVideo() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        YtdlpKt.init(ctx)
        // "Me at the zoo" — the first YouTube video, stable id.
        val info = YtdlpKt.resolve("https://www.youtube.com/watch?v=jNQXAC9IVRw")
        android.util.Log.i("YtdlpKtSmoke", "resolved title='${info.title}' formats=${info.formats.size}")
        assertTrue("no formats resolved (title=${info.title})", info.formats.isNotEmpty())
    }
}
