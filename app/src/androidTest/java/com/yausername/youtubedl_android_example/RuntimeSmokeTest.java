package com.yausername.youtubedl_android_example;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.yausername.aria2c.Aria2c;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * L3 verify-gate: proves the from-source, API-23 native runtime (python/yt-dlp, ffmpeg, ffprobe,
 * quickjs, aria2c) actually LOADS and RUNS on the device. Designed for an API-23 (Android 6.0)
 * emulator and deterministic — no network, just each binary's --version. A binary that fails to
 * link on API 23 exits 127 with "CANNOT LINK"/"cannot locate symbol", which these assertions catch.
 */
@RunWith(AndroidJUnit4.class)
public class RuntimeSmokeTest {

    private static Context ctx;

    @BeforeClass
    public static void setUp() throws Exception {
        ctx = InstrumentationRegistry.getInstrumentation().getTargetContext();
        YoutubeDL.getInstance().init(ctx);
        FFmpeg.getInstance().init(ctx);
        Aria2c.getInstance().init(ctx);
    }

    /** python + yt-dlp: exercises the interpreter via the library's own process pipeline. */
    @Test
    public void ytDlpVersionRuns() throws Exception {
        YoutubeDLRequest req = new YoutubeDLRequest(new ArrayList<>());
        req.addOption("--version");
        YoutubeDLResponse resp = YoutubeDL.getInstance().execute(req, null, null);
        assertEquals("yt-dlp --version exit code", 0, resp.getExitCode());
        assertFalse("yt-dlp --version output empty", resp.getOut().trim().isEmpty());
    }

    /** ffmpeg, ffprobe, quickjs, aria2c: each binary must load + report a version on API 23. */
    @Test
    public void nativeBinariesLoadOnApi23() throws Exception {
        String binDir = ctx.getApplicationInfo().nativeLibraryDir;
        File pkgs = new File(new File(ctx.getNoBackupFilesDir(), "youtubedl-android"), "packages");
        String ld = new File(pkgs, "python/usr/lib") + ":"
                + new File(pkgs, "ffmpeg/usr/lib") + ":"
                + new File(pkgs, "aria2c/usr/lib");

        // Run all binaries and collect every failure so one emulator run shows the full picture.
        StringBuilder failures = new StringBuilder();
        check(failures, binDir, ld, "libffmpeg.so", "-version");
        check(failures, binDir, ld, "libffprobe.so", "-version");
        check(failures, binDir, ld, "libqjs.so", "-e", "0");   // eval & exit (no REPL)
        check(failures, binDir, ld, "libaria2c.so", "--version");
        if (failures.length() > 0) fail(failures.toString());
    }

    private static void check(StringBuilder failures, String binDir, String ld, String binName,
                              String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(new File(binDir, binName).getAbsolutePath());
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("LD_LIBRARY_PATH", ld);
        pb.environment().put("LD_LIBRARY_PATH_ORIG", ld);
        Process p = pb.start();
        String out = new String(readAll(p), "UTF-8");
        int code = p.waitFor();
        // Linker warnings (unused DT entry / unsupported DT_FLAGS_1) are EXPECTED noise on API 23
        // and non-fatal. A real failure is a non-zero exit, an explicit link error, or no output.
        boolean linkFail = out.contains("CANNOT LINK") || out.contains("cannot locate symbol")
                || (out.contains("library \"") && out.contains("not found"));
        if (code != 0 || linkFail || out.trim().isEmpty()) {
            // Trim to the most informative bit (first link error line, else head of output).
            String detail = out;
            int idx = out.indexOf("CANNOT LINK");
            if (idx < 0) idx = out.indexOf("cannot locate symbol");
            if (idx >= 0) detail = out.substring(idx, Math.min(out.length(), idx + 200));
            failures.append("\n  ").append(binName).append(": exitCode=").append(code)
                    .append(" linkFail=").append(linkFail).append(" -> ").append(detail.trim());
        }
    }

    private static byte[] readAll(Process p) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = p.getInputStream().read(buf)) != -1) bos.write(buf, 0, n);
        return bos.toByteArray();
    }
}
