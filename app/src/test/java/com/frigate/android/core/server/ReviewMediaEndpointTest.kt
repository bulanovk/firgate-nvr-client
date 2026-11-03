package com.frigate.android.core.server

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ReviewMediaEndpointTest {
    private val profile = ServerProfile("Test", "https://frigate.example.com/frigate")

    @Test
    fun resolvesReviewThumbnailToProfileRelativeClipPath() {
        val url = ReviewMediaEndpoint.thumbnailUrl(
            profile,
            "/media/frigate/clips/review/thumb-back_door1-1710000000.5.webp",
        )

        assertEquals(
            "https://frigate.example.com/frigate/clips/review/thumb-back_door1-1710000000.5.webp",
            url,
        )
    }

    @Test
    fun rejectsThumbnailsOutsideTheReviewClipFolder() {
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "/media/frigate/clips/export/export-1.webp"))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "/media/frigate/clips/thumbs/thumb-1.webp"))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "/media/frigate/clips/review/../../etc/passwd"))
    }

    @Test
    fun rejectsForeignAndEncodedThumbnailLocations() {
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "https://evil.example.com/x.webp"))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "//evil.example.com/x.webp"))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "/media/frigate/clips/review/%2e%2e/%2e%2e/etc/passwd"))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, "/media/frigate/clips/review/"))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, ""))
        assertNull(ReviewMediaEndpoint.thumbnailUrl(profile, null))
    }

    @Test
    fun buildsVodPlaylistUrlWithoutApiPrefix() {
        val url = ReviewMediaEndpoint.vodPlaylistUrl(
            profile = profile,
            camera = "back_door1",
            after = 1710000000.0,
            before = 1710003600.0,
        )

        assertEquals(
            "https://frigate.example.com/frigate/vod/back_door1/start/1710000000/end/1710003600/master.m3u8",
            url,
        )
    }

    @Test
    fun buildsVodPlaylistUrlWithFractionalTimestampsAndEncodedCamera() {
        val url = ReviewMediaEndpoint.vodPlaylistUrl(
            profile = profile,
            camera = "front door/1",
            after = 1710000000.25,
            before = 1710003600.5,
        )

        assertEquals(
            "https://frigate.example.com/frigate/vod/front%20door%2F1/start/1710000000.25/end/1710003600.5/master.m3u8",
            url,
        )
    }
}
