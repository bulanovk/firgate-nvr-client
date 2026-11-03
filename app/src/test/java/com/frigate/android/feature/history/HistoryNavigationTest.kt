package com.frigate.android.feature.history

import com.frigate.android.core.server.FrigateReview
import java.time.LocalDate
import java.time.ZoneId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HistoryNavigationTest {
    private val now = 1_710_000_000.0

    private fun review(
        id: String = "review-1",
        camera: String = "front_door",
        startTime: Double = now,
    ) = FrigateReview(
        id = id,
        camera = camera,
        startTime = startTime,
        endTime = startTime + 30.0,
        severity = "alert",
        hasBeenReviewed = false,
        thumbnailPath = null,
        objectLabels = listOf("person"),
        zones = listOf("porch"),
    )

    @Test
    fun openingAReviewOpensTheArchiveOfItsCamera() {
        val navigation = HistoryNavigation { now }

        navigation.openReview(review(camera = "back_door"))

        assertTrue(navigation.isOpen)
        assertEquals("back_door", navigation.cameraId)
        assertEquals("review-1", navigation.review?.id)
    }

    @Test
    fun openingAReviewSetsTheWindowToTheLastDay() {
        val navigation = HistoryNavigation { now }

        navigation.openReview(review())

        assertEquals(now - 86_400.0, navigation.range.after, 0.001)
        assertEquals(now, navigation.range.before, 0.001)
    }

    @Test
    fun theArchiveOpensFourSecondsBeforeTheEvent() {
        val anchor = historyAnchorSeconds(review(), HistoryRange.initial(now))

        assertEquals(now - 4.0, anchor, 0.001)
    }

    @Test
    fun withoutAnEventTheArchiveOpensAtTheLastMinuteOfTheWindow() {
        val range = HistoryRange(after = 0.0, before = 3_600.0)

        assertEquals(3_540.0, historyAnchorSeconds(null, range), 0.001)
    }

    @Test
    fun anEventOutsideOfTheWindowFallsBackToTheLastMinute() {
        val range = HistoryRange(after = 1_000.0, before = 3_600.0)

        assertEquals(3_540.0, historyAnchorSeconds(review(startTime = 500.0), range), 0.001)
        assertEquals(3_540.0, historyAnchorSeconds(review(startTime = 9_000.0), range), 0.001)
    }

    @Test
    fun closingTheArchiveReturnsToTheLiveScreen() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review())

        navigation.close()

        assertFalse(navigation.isOpen)
        assertNull(navigation.review)
        assertNull(navigation.cameraId)
    }

    @Test
    fun switchingTheCameraKeepsTheMomentOfTheDay() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review())

        navigation.changeCamera("back_door")

        assertEquals("back_door", navigation.cameraId)
        assertEquals(now, navigation.range.before, 0.001)
        assertEquals(now - 86_400.0, navigation.range.after, 0.001)
    }

    @Test
    fun theLiveButtonOfTheArchiveOpensTheLiveOfTheSelectedCamera() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review(camera = "front_door"))
        navigation.changeCamera("back_door")

        assertEquals("back_door", navigation.openLive())
        assertFalse(navigation.isOpen)
    }

    @Test
    fun theCalendarKeepsTheWindowOfTheSessionForTheLastDay() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review())
        navigation.changeRange(HistoryRange.daySeconds(LocalDate.of(2026, 10, 30), ZoneId.of("UTC")))

        navigation.restoreInitialRange()

        assertEquals(now - 86_400.0, navigation.range.after, 0.001)
        assertEquals(now, navigation.range.before, 0.001)
    }

    @Test
    fun thePanelAndTheFilterAreRememberedInTheSession() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review())

        navigation.changePanelMode(HistoryPanelMode.EVENTS)
        navigation.changeFilter(HistoryFilter(labels = setOf("person")))

        assertEquals(HistoryPanelMode.EVENTS, navigation.panelMode)
        assertEquals(setOf("person"), navigation.filter.labels)
    }

    @Test
    fun openingTheArchiveOfACameraWithoutAnEventStartsAtTheLastMinute() {
        val navigation = HistoryNavigation { now }

        navigation.openCamera("back_door")

        assertTrue(navigation.isOpen)
        assertNull(navigation.review)
        assertEquals("back_door", navigation.cameraId)
    }

    @Test
    fun thePositionAndThePlaybackSurviveLeavingTheArchive() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review())
        navigation.player.pause()
        navigation.seekTo(now - 10.0)

        navigation.close()
        navigation.openReview(review())

        assertEquals(now - 10.0, navigation.player.state.positionSeconds, 0.001)
        assertEquals(now - 10.0, navigation.screenModel.anchorSeconds, 0.001)
        assertFalse(navigation.player.state.playing)
    }

    @Test
    fun anotherEventMovesThePlayheadToItsStart() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review(id = "review-1"))
        navigation.seekTo(now - 10.0)

        navigation.openReview(review(id = "review-2", startTime = now - 100.0))

        assertEquals(now - 104.0, navigation.player.state.positionSeconds, 0.001)
        assertTrue(navigation.player.state.playing)
    }

    @Test
    fun switchingTheCameraKeepsThePlayheadOfTheSession() {
        val navigation = HistoryNavigation { now }
        navigation.openReview(review())
        navigation.seekTo(now - 10.0)

        navigation.changeCamera("back_door")

        assertEquals(now - 10.0, navigation.player.state.positionSeconds, 0.001)
        assertEquals(now - 10.0, navigation.screenModel.anchorSeconds, 0.001)
    }
}
