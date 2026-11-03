package com.frigate.android.feature.live

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Go2RtcMseProtocolTest {
    @Test
    fun detailAudioOfferAddsOnlyDeviceSupportedAudioCodecs() {
        assertEquals("avc1.640029,mp4a.40.2,opus", Go2RtcMseProtocol.codecOffer(
            setOf("video/avc", "audio/mp4a-latm", "audio/opus"), includeAudio = true))
        assertEquals("avc1.640029", Go2RtcMseProtocol.codecOffer(setOf("video/avc"), includeAudio = true))
        assertEquals("", Go2RtcMseProtocol.codecOffer(setOf("audio/opus"), includeAudio = true))
        assertTrue(Go2RtcMseProtocol.isMimeTypeSupported("video/mp4; codecs=\"avc1.640029,mp4a.40.2\"", "avc1.640029,mp4a.40.2"))
    }

    @Test
    fun detailOffersFlacForGo2RtcPcmAudioOnlyWhenTheDeviceCanDecodeIt() {
        assertEquals("avc1.640029,flac", Go2RtcMseProtocol.codecOffer(
            setOf("video/avc", "audio/flac"), includeAudio = true))
        assertEquals("avc1.640029", Go2RtcMseProtocol.codecOffer(
            setOf("video/avc", "audio/flac"), includeAudio = false))
        assertTrue(Go2RtcMseProtocol.isMimeTypeSupported(
            "video/mp4; codecs=\"avc1.640029,flac\"", "avc1.640029,flac"))
    }

    @Test
    fun offersOnlySupportedVideoCandidates() {
        val offer = Go2RtcMseProtocol.videoCodecOffer(
            setOf("video/avc", "video/hevc", "audio/mp4a-latm"),
        )

        assertEquals("avc1.640029,hvc1.1.6.L153.B0", offer)
        assertTrue(!offer.contains("mp4a"))
        assertTrue(!offer.contains("opus"))
    }

    @Test
    fun returnsEmptyOfferWhenNoVideoDecoderIsSupported() {
        assertEquals("", Go2RtcMseProtocol.videoCodecOffer(setOf("audio/mp4a-latm")))
    }

    @Test
    fun parsesNegotiatedVideoOnlyMime() {
        assertEquals(
            MseControlMessage.Negotiated("video/mp4; codecs=\"avc1.640029\""),
            Go2RtcMseProtocol.parseControlMessage(
                """{"type":"mse","value":"video/mp4; codecs=\"avc1.640029\""}""",
            ),
        )
    }

    @Test
    fun parsesGo2RtcCodecMismatch() {
        assertEquals(
            MseControlMessage.ServerError("mse: codecs not matched"),
            Go2RtcMseProtocol.parseControlMessage(
                """{"type":"error","value":"mse: codecs not matched"}""",
            ),
        )
    }

    @Test
    fun rejectsNegotiatedCodecNotPresentInOffer() {
        assertTrue(
            !Go2RtcMseProtocol.isMimeTypeSupported(
                "video/mp4; codecs=\"hvc1.1.6.L153.B0\"",
                "avc1.640029",
            ),
        )
    }
}
