package com.frigate.android.feature.events

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.frigate.android.core.server.FrigateClient
import com.frigate.android.core.server.MotionPoint
import com.frigate.android.core.server.ServerProfile
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class EventsMotionUiTest {
    @get:Rule val compose = createComposeRule()
    @Test
    fun motionCameraKeepsItsSourceAspectInsteadOfStretchingToWidescreen() {
        val query = eventsFixture().query
        val profile = ServerProfile("Test", "http://127.0.0.1:1/")
        val playback = MotionPlaybackState(query.range.after)
        compose.setContent { Box(Modifier.size(360.dp, 500.dp)) {
            MotionScreen(playback, query, emptyList(), emptyList(), emptyList(), profile, "",
                ClientEventsRepository(FrigateClient(profile, "")), {}, { _, _, _ -> },
                cameraAspects = mapOf("yard" to (4.0 / 3)))
        } }
        val bounds = compose.onNodeWithContentDescription("Motion camera: yard").fetchSemanticsNode().boundsInRoot
        assertEquals(4f / 3, bounds.width / bounds.height, .02f)
    }

    @Test
    fun appliedMotionRegionsSurviveLeavingThePaneForHistory() {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                    if (request.path?.contains("/recordings?") == true)
                        okhttp3.mockwebserver.MockResponse().setBody("""[{"start_time":100,"end_time":200,"motion_heatmap":{"0":255}}]""")
                    else okhttp3.mockwebserver.MockResponse().setResponseCode(404)
            }
            val profile = ServerProfile("Test", server.url("/").toString())
            val query = eventsFixture().query
            val playback = MotionPlaybackState(query.range.after)
            val visible = androidx.compose.runtime.mutableStateOf(true)
            compose.setContent { if (visible.value) Box(Modifier.size(360.dp, 500.dp)) {
                MotionScreen(playback, query, listOf(MotionPoint(120.0, 1.0, "yard")), emptyList(), emptyList(),
                    profile, "", ClientEventsRepository(FrigateClient(profile, "")), {}, { _, _, _ -> })
            } }
            compose.onNodeWithContentDescription("Motion clips: yard").performClick()
            compose.onNodeWithContentDescription("Motion regions").performClick()
            compose.waitUntil(10000) {
                compose.onAllNodes(hasContentDescription("Region cell 1, 1") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Region cell 1, 1").performClick()
            compose.onNodeWithText("Apply").performClick()
            compose.runOnIdle { visible.value = false }
            compose.waitForIdle()
            compose.runOnIdle { visible.value = true }
            compose.onNodeWithContentDescription("Motion regions").performClick()
            compose.onNodeWithContentDescription("Region cell 1, 1").assertIsSelected()
        }
    }

    @Test
    fun motionTilesShowSharedWallClockTimeAndUpdateItAfterSeek() {
        val query = eventsFixture().query
        val playback = MotionPlaybackState(120.0)
        val profile = ServerProfile("Test", "http://127.0.0.1:1/")
        compose.setContent { Box(Modifier.size(360.dp, 500.dp)) {
            MotionScreen(playback, query, emptyList(), emptyList(), emptyList(), profile, "",
                ClientEventsRepository(FrigateClient(profile, "")), {}, { _, _, _ -> })
        } }
        compose.onNodeWithContentDescription("Motion timestamp: 00:02:00", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Motion time").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(50f) }
        compose.onNodeWithContentDescription("Motion timestamp: 00:02:30", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun seekPreservesSubsecondPrecisionForCurrentEpochTimestamps() {
        val start = 1793563200.0
        val query = eventsFixture().query.copy(range = EventsRange(start, start + 3600, null))
        val playback = MotionPlaybackState(start)
        val profile = ServerProfile("Test", "http://127.0.0.1:1/")
        compose.setContent { Box(Modifier.size(360.dp, 500.dp)) {
            MotionScreen(playback, query, emptyList(), emptyList(), emptyList(), profile, "",
                ClientEventsRepository(FrigateClient(profile, "")), {}, { _, _, _ -> })
        } }
        compose.onNodeWithContentDescription("Motion time").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(10.5f) }
        compose.runOnIdle { assertEquals(start + 10.5, playback.timestamp, .001) }
    }

    @Test
    fun motionHasRealControlsAndCameraPaneReturnsWithoutOpeningArchive() {
        val query = eventsFixture().query
        val playback = MotionPlaybackState(query.range.after)
        val profile = ServerProfile("Test", "http://127.0.0.1:1/")
        var opened: String? = null
        compose.setContent { Box(Modifier.size(360.dp, 500.dp)) {
            MotionScreen(playback, query, listOf(MotionPoint(120.0, 1.0, "yard")), emptyList(), emptyList(),
                profile, "", ClientEventsRepository(FrigateClient(profile, "")), {}, { camera, _, _ -> opened = camera })
        } }
        compose.onNodeWithContentDescription("Motion only").assertDoesNotExist()
        compose.onNodeWithContentDescription("Motion speed").performClick()
        compose.onNodeWithText("16x").performClick()
        compose.runOnIdle { assertEquals(16, playback.rate) }
        compose.onNodeWithContentDescription("Motion clips: yard").performClick()
        compose.onNodeWithContentDescription("Back to motion cameras").assertIsDisplayed().performClick()
        compose.runOnIdle { assertNull(opened); assertNull(playback.cameraId) }
    }
}

class EventsTimelineTest {
    @get:Rule val compose = createComposeRule()
    @Test
    fun dayTimelineDoesNotStackQuarterHourLabelsOnTopOfEachOther() {
        val model = EventsTimelineModel(EventsRange(0.0, 86400.0, null), emptyList(), emptyList())
        compose.setContent { Box(Modifier.size(120.dp, 400.dp)) { EventsTimeline(model, "UTC", {}, {}) } }
        val clockLabel = SemanticsMatcher("clock label") { node ->
            node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Text) &&
                node.config[androidx.compose.ui.semantics.SemanticsProperties.Text]
                    .any { it.text.matches(Regex("\\d{2}:\\d{2}")) }
        }
        val bounds = compose.onAllNodes(clockLabel).fetchSemanticsNodes().map { it.boundsInRoot }.sortedBy { it.top }
        assertTrue(bounds.size in 2..19)
        assertTrue(bounds.zipWithNext().all { (a, b) -> a.bottom <= b.top })
    }

    @Test
    fun overviewTapSeeksWithinTheSameTimeRange() {
        var seek: Double? = null
        val model = EventsTimelineModel(EventsRange(100.0, 200.0, null), emptyList(), emptyList())
        compose.setContent { Box(Modifier.size(120.dp, 400.dp)) { EventsTimeline(model, "UTC", {}, { seek = it }) } }
        compose.onNodeWithContentDescription("Timeline overview").performTouchInput {
            click(androidx.compose.ui.geometry.Offset(width / 2f, height / 4f))
        }
        compose.runOnIdle { assertEquals(175.0, seek!!, .5) }
    }

    @Test
    fun segmentSelectsActualReviewWithoutChangingFilters() {
        var id: String? = null
        val model = EventsTimelineModel(eventsFixture().query.range, eventsFixture().items, emptyList())
        compose.setContent { Box(Modifier.size(120.dp, 400.dp)) { EventsTimeline(model, "UTC", { id = it }, {}) } }
        compose.onNodeWithContentDescription("Timeline event: yard, 00:02:00").performClick()
        compose.runOnIdle { assertEquals("r", id) }
    }
}
