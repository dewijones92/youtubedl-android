package com.yausername.youtubedl_android_example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

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
 * NON-GATING network test (same caveat as {@link NetworkExtractionTest}: datacenter IPs are
 * bot-blocked; run on a residential-network device/emulator). Proves the persistent resolve
 * daemon: (a) returns the same essential fields as the process-per-call CLI path, (b) is faster
 * on repeat resolves (no interpreter/zipapp boot), (c) restarts transparently after a kill.
 * Timing lands in logcat as "DaemonResolve" PERF lines.
 */
@RunWith(AndroidJUnit4.class)
public class DaemonResolveTest {

    private static final String TAG = "DaemonResolve";
    /** "Me at the zoo" — stable id, short. */
    private static final String URL = "https://www.youtube.com/watch?v=jNQXAC9IVRw";

    @BeforeClass
    public static void setUp() throws Exception {
        final Context ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YoutubeDL.getInstance().init(ctx);
        FFmpeg.getInstance().init(ctx);
    }

    @Test
    public void daemonMatchesCliAndIsFasterOnRepeat() throws Exception {
        // CLI baseline (includes per-call python boot).
        long t0 = System.currentTimeMillis();
        final VideoInfo cli = YoutubeDL.getInstance().getInfo(URL);
        perf("cli_getinfo_ms", System.currentTimeMillis() - t0);

        // Daemon: first call pays the one-off boot, later calls shouldn't.
        t0 = System.currentTimeMillis();
        final VideoInfo daemon1 = YoutubeDL.getInstance().getInfoViaDaemon(URL);
        final long daemonCold = System.currentTimeMillis() - t0;
        perf("daemon_first_ms", daemonCold);

        t0 = System.currentTimeMillis();
        final VideoInfo daemon2 = YoutubeDL.getInstance().getInfoViaDaemon(URL);
        final long daemonWarm = System.currentTimeMillis() - t0;
        perf("daemon_warm_ms", daemonWarm);

        // The realistic warm case: a DIFFERENT video on the already-running daemon (imports and
        // yt-dlp's in-memory nsig/player caches shared; the video itself never seen before).
        t0 = System.currentTimeMillis();
        final VideoInfo other = YoutubeDL.getInstance().getInfoViaDaemon(
                "https://www.youtube.com/watch?v=7muV3mKjqmM");
        perf("daemon_warm_other_video_ms", System.currentTimeMillis() - t0);
        assertNotNull("other-video formats null", other.getFormats());
        assertFalse("other-video formats empty", other.getFormats().isEmpty());

        // Field parity on everything toMediaInfo() consumes.
        assertEquals("id differs", cli.getId(), daemon1.getId());
        assertEquals("title differs", cli.getTitle(), daemon1.getTitle());
        assertEquals("duration differs", cli.getDuration(), daemon1.getDuration());
        assertNotNull("daemon formats null", daemon1.getFormats());
        assertFalse("daemon formats empty", daemon1.getFormats().isEmpty());
        assertNotNull("cli formats null", cli.getFormats());
        Log.i(TAG, "formats cli=" + cli.getFormats().size()
                + " daemon=" + daemon1.getFormats().size());
        assertNotNull("daemon format url missing",
                daemon1.getFormats().get(daemon1.getFormats().size() - 1).getUrl());
        assertEquals("second daemon resolve differs", daemon1.getId(), daemon2.getId());

        // Restart-on-death: kill it, next call must transparently bring it back.
        YoutubeDL.getInstance().shutdownDaemon();
        t0 = System.currentTimeMillis();
        final VideoInfo daemon3 = YoutubeDL.getInstance().getInfoViaDaemon(URL);
        perf("daemon_after_kill_ms", System.currentTimeMillis() - t0);
        assertEquals("post-restart resolve differs", cli.getId(), daemon3.getId());

        assertTrue("warm daemon resolve (" + daemonWarm
                + "ms) not faster than its own cold start (" + daemonCold + "ms)",
                daemonWarm <= daemonCold);
    }

    private static void perf(final String label, final long value) {
        Log.i(TAG, "PERF " + label + "=" + value);
    }
}
