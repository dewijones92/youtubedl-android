package com.yausername.youtubedl_android_example;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;

import android.content.Context;
import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.mapper.VideoInfo;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * NON-GATING (CI runs this class with continue-on-error): a REAL on-device YouTube extraction on
 * API 23 from a proper application APK. Proves yt-dlp can resolve a video end-to-end — exercising
 * the JS runtime (nsig) + network — beyond just "binaries load" (RuntimeSmokeTest). Expected to be
 * flaky (YouTube 403 / SABR / PO-token / nsig churn); it exists only for signal ahead of L5, so it
 * is run in its own CI step that does not gate the pipeline.
 */
@RunWith(AndroidJUnit4.class)
public class NetworkExtractionTest {

    @BeforeClass
    public static void setUp() throws Exception {
        Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YoutubeDL.getInstance().init(ctx);
        FFmpeg.getInstance().init(ctx);
    }

    @Test
    public void resolveRealYouTubeVideo() throws Exception {
        // "Me at the zoo" — the first YouTube video, stable id.
        VideoInfo info = YoutubeDL.getInstance().getInfo("https://www.youtube.com/watch?v=jNQXAC9IVRw");
        int formatCount = info.getFormats() == null ? 0 : info.getFormats().size();
        Log.i("ExtractSignal", "title='" + info.getTitle() + "' formats=" + formatCount);
        assertNotNull("formats null", info.getFormats());
        assertFalse("no formats resolved (title=" + info.getTitle() + ")", info.getFormats().isEmpty());
    }
}
