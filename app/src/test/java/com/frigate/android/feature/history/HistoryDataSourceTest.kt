package com.frigate.android.feature.history

import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.ServerProfile
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryDataSourceTest {
    private fun source(server: MockWebServer): HistoryDataSource =
        HistoryDataSource(FrigateClient(ServerProfile("Test", server.url("/frigate").toString()), "session=secret"))

    /** The window is deliberately not aligned to the segment grid. */
    private fun range(): HistoryRange = HistoryRange(after = 1_710_000_061.0, before = 1_710_003_600.0)

    @Test
    fun loadsTheReviewsOfTheCameraAndRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"r1","camera":"front_door","start_time":1710000000,"end_time":1710000030,"severity":"alert","has_been_reviewed":false,"data":{"objects":["person"]}}]"""))

            val reviews = source(server).reviews("front_door", range())

            assertEquals(1, reviews.size)
            assertEquals("r1", reviews.single().id)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/review?cameras=front_door&limit=2000&before=1710003600&after=1710000061",
                request.path,
            )
            assertEquals("session=secret", request.getHeader("Cookie"))
        }
    }

    @Test
    fun theReviewQueryIsBounded() = runBlocking {
        // The server has no default limit: an unbounded query over a day of a busy camera answers
        // with tens of megabytes of segments.
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))

            source(server).reviews("front_door", range())

            val request = server.takeRequest()
            assertTrue(request.path!!.contains("limit=$HISTORY_REVIEW_LIMIT"))
        }
    }

    @Test
    fun requestsMotionWithTheHalfSegmentScaleAndTheAlignedRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))

            source(server).motion("front_door", range(), segmentDuration = 30.0)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/review/activity/motion?cameras=front_door&before=1710003600&after=1710000060&scale=15",
                request.path,
            )
        }
    }

    @Test
    fun requestsUnavailableWindowsWithTheFullSegmentScale() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("[]"))

            source(server).unavailable("front_door", range(), segmentDuration = 15.0)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/recordings/unavailable?cameras=front_door&before=1710003600&after=1710000060&scale=15",
                request.path,
            )
        }
    }

    @Test
    fun requestsCoverageWithTheTimelinesOfTheAlignedRange() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"spans":[],"codecs_compatible":true,"streams":{}}"""))

            source(server).coverage("front_door", range(), segmentDuration = 30.0)

            val request = server.takeRequest()
            assertEquals(
                "/frigate/api/front_door/recordings/coverage?before=1710003600&after=1710000060&timelines=true",
                request.path,
            )
        }
    }
}
