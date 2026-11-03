package com.frigate.android.feature.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ReviewRefreshSchedulerTest {
    @Test
    fun keepsTheEarliestDeadlineWhenInvalidationsOverlap() {
        var now = 1_000L
        val scheduler = ReviewRefreshScheduler { now }

        scheduler.schedule(1_000L)
        scheduler.schedule(6_000L)

        assertEquals(1_000L, scheduler.remainingMillis())
        now += 1_000L
        assertEquals(0L, scheduler.remainingMillis())
    }

    @Test
    fun anEarlierInvalidationReplacesALaterDeadline() {
        var now = 1_000L
        val scheduler = ReviewRefreshScheduler { now }

        scheduler.schedule(6_000L)
        scheduler.schedule(0L)

        assertEquals(0L, scheduler.remainingMillis())
    }

    @Test
    fun aDueDeadlineIsConsumedOnlyOnce() {
        var now = 1_000L
        val scheduler = ReviewRefreshScheduler { now }

        scheduler.schedule(0L)

        assertTrue(scheduler.takeDue())
        assertFalse(scheduler.takeDue())
        assertNull(scheduler.remainingMillis())
    }

    @Test
    fun aDeadlineInTheFutureIsNotDue() {
        var now = 1_000L
        val scheduler = ReviewRefreshScheduler { now }

        scheduler.schedule(500L)

        assertFalse(scheduler.takeDue())
        assertEquals(500L, scheduler.remainingMillis())
    }

    @Test
    fun clearDropsEveryDeadline() {
        var now = 1_000L
        val scheduler = ReviewRefreshScheduler { now }

        scheduler.schedule(1_000L)
        scheduler.clear()

        assertNull(scheduler.remainingMillis())
    }
}
