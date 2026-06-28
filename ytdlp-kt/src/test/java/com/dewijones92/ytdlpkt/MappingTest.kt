package com.dewijones92.ytdlpkt

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.yausername.youtubedl_android.mapper.VideoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deterministic (no network/device) test of the VideoInfo -> MediaInfo mapping that is the SDK's
 * core typed-layer logic, including audio/video/muxed format classification.
 */
class MappingTest {

    private val mapper = ObjectMapper()
        .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

    private val sampleJson = """
        {
          "id": "abc123",
          "title": "Test Video",
          "duration": 212,
          "uploader": "Test Channel",
          "webpage_url": "https://www.youtube.com/watch?v=abc123",
          "thumbnail": "https://i.ytimg.com/abc.jpg",
          "formats": [
            {"format_id":"18","ext":"mp4","width":640,"height":360,"vcodec":"avc1.42001E","acodec":"mp4a.40.2","abr":96,"filesize":1048576,"url":"https://x/18"},
            {"format_id":"140","ext":"m4a","width":0,"height":0,"vcodec":"none","acodec":"mp4a.40.2","abr":128,"filesize":524288,"url":"https://x/140"},
            {"format_id":"137","ext":"mp4","width":1920,"height":1080,"vcodec":"avc1.640028","acodec":"none","abr":0,"filesize":5242880,"url":"https://x/137"}
          ]
        }
    """.trimIndent()

    private fun parse(): MediaInfo =
        mapper.readValue(sampleJson, VideoInfo::class.java).toMediaInfo()

    @Test
    fun mapsTopLevelFields() {
        val info = parse()
        assertEquals("abc123", info.id)
        assertEquals("Test Video", info.title)
        assertEquals(212, info.durationSeconds)
        assertEquals("Test Channel", info.uploader)
        assertEquals("https://www.youtube.com/watch?v=abc123", info.webpageUrl)
        assertEquals("https://i.ytimg.com/abc.jpg", info.thumbnailUrl)
        assertEquals(3, info.formats.size)
    }

    @Test
    fun classifiesFormats() {
        val info = parse()
        assertEquals(listOf("18"), info.muxedFormats.map { it.formatId })
        assertEquals(listOf("140"), info.audioOnlyFormats.map { it.formatId })
        assertEquals(listOf("137"), info.videoOnlyFormats.map { it.formatId })
    }

    @Test
    fun mapsFormatDetails() {
        val muxed = parse().muxedFormats.single()
        assertEquals("mp4", muxed.ext)
        assertEquals(640, muxed.width)
        assertEquals(360, muxed.height)
        assertEquals(96, muxed.averageBitrateKbps)
        assertEquals(1048576L, muxed.fileSizeBytes)
        assertTrue(muxed.url!!.endsWith("/18"))
    }
}
