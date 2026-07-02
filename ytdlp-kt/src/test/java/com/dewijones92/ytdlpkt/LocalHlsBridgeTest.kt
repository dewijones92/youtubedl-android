package com.dewijones92.ytdlpkt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Deterministic tests for the bridge's pure pieces (args + stub playlist). */
class LocalHlsBridgeTest {

    @Test
    fun buildsVideoPlusAudioArgs() {
        val args = buildFfmpegArgs(
            "/bin/ffmpeg", "https://v.example/v", "https://a.example/a",
            4, "/out/seg%05d.ts", "/out/index.m3u8",
        )
        assertEquals("/bin/ffmpeg", args[0])
        // stream copy into local HLS, both inputs mapped
        assertTrue(args.containsAll(listOf("-i", "https://v.example/v")))
        assertTrue(args.containsAll(listOf("-map", "0:v", "-map", "1:a")))
        assertEquals(listOf("-c", "copy"), args.subList(args.indexOf("-c"), args.indexOf("-c") + 2))
        assertEquals("event", args[args.indexOf("-hls_playlist_type") + 1])
        // fMP4 segments so VP9/AV1 video (not just h264) survives the mux
        assertEquals("fmp4", args[args.indexOf("-hls_segment_type") + 1])
        assertEquals("/out/index.m3u8", args.last())
        // must overwrite the stub playlist without prompting
        assertTrue(args.contains("-y"))
        assertTrue(args.contains("-nostdin"))
    }

    @Test
    fun buildsVideoOnlyArgsWithoutAudioMaps() {
        val args = buildFfmpegArgs(
            "/bin/ffmpeg", "https://v.example/v", null, 4, "/out/seg%05d.ts", "/out/index.m3u8",
        )
        assertTrue(args.containsAll(listOf("-map", "0")))
        assertFalse(args.contains("1:a"))
        assertEquals(1, args.count { it == "-i" })
    }

    @Test
    fun stubPlaylistIsValidSegmentlessEvent() {
        val stub = stubPlaylist(4)
        assertTrue(stub.startsWith("#EXTM3U\n"))
        assertTrue(stub.contains("#EXT-X-TARGETDURATION:4\n"))
        assertTrue(stub.contains("#EXT-X-PLAYLIST-TYPE:EVENT"))
        assertFalse(stub.contains("EXT-X-ENDLIST")) // must stay "live" so the player keeps polling
        assertFalse(stub.contains(".ts"))
    }
}
