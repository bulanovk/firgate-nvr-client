package com.frigate.android.feature.events

import com.frigate.android.feature.history.HistoryNavigation
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class EventsHistoryEntryTest {
    @Test
    fun historicalReviewAlwaysRestartsAtPaddedStart() {
        val navigation = HistoryNavigation { 1800000000.0 }
        val review = eventReview("r").copy(startTime = 1710000100.0, endTime = 1710000200.0)
        val range = EventsRange(1710000100.0, 1710086500.0, "2024-03-09")
        navigation.openEvent(ReviewOpenRequest(review, range), 1800000000.0)
        assertEquals(1710000096.0, navigation.player.state.positionSeconds)
        assertEquals(1710000096.0, navigation.range.after)
        assertEquals("yard", navigation.cameraId)
        navigation.seekTo(1710000200.0)
        navigation.close()
        navigation.openEvent(ReviewOpenRequest(review, range), 1800000000.0)
        assertEquals(1710000096.0, navigation.player.state.positionSeconds)
        assertTrue(navigation.player.state.playing)
        navigation.openEvent(ReviewOpenRequest(review.copy(camera = "street"), range), 1800000000.0)
        assertEquals("street", navigation.cameraId)
    }

    @Test
    fun ongoingEventExtendsRangeToNowAndMotionHasNoFakeReviewOrPadding() {
        val navigation = HistoryNavigation { 300.0 }
        navigation.openEvent(ReviewOpenRequest(eventReview("r").copy(endTime = null), EventsRange(100.0, 200.0, null)), 300.0)
        assertEquals(300.0, navigation.range.before)
        navigation.openMotion("street", 290.0, EventsRange(100.0, 300.0, null), 300.0, grid = true)
        assertNull(navigation.review)
        assertEquals(270.0, navigation.player.state.positionSeconds)
        navigation.openMotion("yard", 290.0, EventsRange(100.0, 300.0, null), 300.0, grid = false)
        assertEquals(290.0, navigation.player.state.positionSeconds)
        assertEquals("yard", navigation.cameraId)
    }
}
