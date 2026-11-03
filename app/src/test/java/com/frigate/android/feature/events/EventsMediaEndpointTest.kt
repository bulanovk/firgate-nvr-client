package com.frigate.android.feature.events

import com.frigate.android.core.server.ServerProfile
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsMediaEndpointTest {
    private val profile = ServerProfile("Home", "https://example.com/frigate")

    @Test
    fun returnedServerPathsStayUnderConfiguredBasePath() {
        assertEquals("https://example.com/frigate/clips/previews/yard.mp4", eventsMediaUrl(profile, "/clips/previews/yard.mp4"))
        assertEquals("https://example.com/frigate/exports/yard.mp4", eventsMediaUrl(profile, "exports/yard.mp4"))
        assertEquals("https://example.com/frigate/clips/a.mp4", eventsMediaUrl(profile, "https://example.com/frigate/clips/a.mp4"))
    }

    @Test
    fun rejectsCrossOriginCredentialsTraversalAndBasePathEscape() {
        listOf("https://evil.example/a", "//evil.example/a", "../a", "/clips/../secret", "/clips/%2e%2e/secret", "/clips/%252e%252e/secret", "https://example.com/other/a", "https://u:p@example.com/frigate/a", "file:///a", "/clips/a?token=x", "/clips/a#fragment", "/clips/a\\b").forEach {
            assertNull(eventsMediaUrl(profile, it), it)
        }
    }
}
