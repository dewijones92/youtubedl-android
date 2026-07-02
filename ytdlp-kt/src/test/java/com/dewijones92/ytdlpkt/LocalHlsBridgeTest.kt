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
        assertFalse(stub.contains("#EXTINF"))
    }

    @Test
    fun seekArgsAddInputLevelSsBeforeEachInput() {
        val args = buildFfmpegArgs(
            "/bin/ffmpeg", "https://v.example/v", "https://a.example/a",
            4, "/out/seg%05d.m4s", "/out/raw.m3u8", 600,
        )
        // -ss must precede each -i (input-level fast range-seek, not output trimming)
        val firstSs = args.indexOf("-ss")
        assertTrue(firstSs in 1 until args.indexOf("-i"))
        assertEquals("600", args[firstSs + 1])
        assertEquals(2, args.count { it == "-ss" })
    }

    @Test
    fun gapEntriesCoverTheFullOffsetWithGapTags() {
        val gaps = gapEntries(10, 4) // 4 + 4 + 2
        assertEquals(3, gaps.split("#EXT-X-GAP").size - 1)
        assertTrue(gaps.contains("#EXTINF:4.000000,"))
        assertTrue(gaps.contains("#EXTINF:2.000000,"))
        // every gap entry: EXTINF, then GAP tag, then a URI that is never written to disk
        for (chunk in gaps.trim().split("\n").chunked(3)) {
            assertTrue(chunk[0].startsWith("#EXTINF:"))
            assertEquals("#EXT-X-GAP", chunk[1])
            assertTrue(chunk[2].startsWith("gap"))
        }
    }

    @Test
    fun gapMergePutsGapsBetweenHeaderAndSegments() {
        val raw = "#EXTM3U\n#EXT-X-VERSION:7\n#EXT-X-TARGETDURATION:6\n" +
            "#EXT-X-MEDIA-SEQUENCE:0\n#EXT-X-PLAYLIST-TYPE:EVENT\n" +
            "#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6.0,\nseg00000.m4s\n"
        val merged = gapMerge(raw, 8, 4)
        val gapIdx = merged.indexOf("#EXT-X-GAP")
        assertTrue(gapIdx > 0)
        // gaps sit after the header but before the MAP + real segments
        assertTrue(gapIdx < merged.indexOf("#EXT-X-MAP"))
        assertTrue(merged.indexOf("#EXT-X-PLAYLIST-TYPE") < gapIdx)
        assertTrue(merged.endsWith("seg00000.m4s\n"))
    }

    @Test
    fun stubWithOffsetCarriesTheGapPrefix() {
        val stub = stubPlaylist(4, 8)
        assertTrue(stub.contains("#EXT-X-GAP"))
        assertEquals(2, stub.split("#EXT-X-GAP").size - 1)
        assertFalse(stub.contains("EXT-X-ENDLIST"))
    }
}
