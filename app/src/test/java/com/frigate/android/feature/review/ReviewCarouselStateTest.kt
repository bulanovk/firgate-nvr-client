package com.frigate.android.feature.review

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ReviewCarouselStateTest {
    @Test
    fun showsTheCardsWhenReviewsAreAvailable() {
        val state = ReviewUiState(items = listOf(review("r1")), loaded = true)

        assertEquals(ReviewCarouselMode.ITEMS, reviewCarouselMode(state))
    }

    @Test
    fun showsTheLoadingStateBeforeTheFirstResponse() {
        assertEquals(ReviewCarouselMode.LOADING, reviewCarouselMode(ReviewUiState()))
    }

    @Test
    fun showsTheErrorStateWhenTheRequestFailed() {
        val state = ReviewUiState(loaded = true, refreshFailed = true)

        assertEquals(ReviewCarouselMode.ERROR, reviewCarouselMode(state))
    }

    @Test
    fun hidesTheBlockWhenThereIsNothingToReview() {
        val state = ReviewUiState(loaded = true)

        assertEquals(ReviewCarouselMode.HIDDEN, reviewCarouselMode(state))
    }

    @Test
    fun keepsShowingCardsWhenARefreshFails() {
        val state = ReviewUiState(items = listOf(review("r1")), loaded = true, refreshFailed = true)

        assertEquals(ReviewCarouselMode.ITEMS, reviewCarouselMode(state))
    }

    @Test
    fun formatsTheAgeOfAReviewLikeThePwa() {
        val now = 1_700_000_000.0

        assertEquals("just now", reviewTimeAgo(now - 5, now))
        assertEquals("45s ago", reviewTimeAgo(now - 45, now))
        assertEquals("2m ago", reviewTimeAgo(now - 120, now))
        assertEquals("3h ago", reviewTimeAgo(now - 3 * 3600, now))
        assertEquals("2d ago", reviewTimeAgo(now - 2 * 86400, now))
    }

    @Test
    fun neverShowsANegativeAge() {
        assertEquals("just now", reviewTimeAgo(1_700_000_060.0, 1_700_000_000.0))
    }

    @Test
    fun summarizesTheDetectedObjects() {
        assertEquals("", reviewObjectSummary(emptyList()))
        assertEquals("", reviewObjectSummary(listOf("  ")))
        assertEquals("Person", reviewObjectSummary(listOf("person")))
        assertEquals("Person, Car", reviewObjectSummary(listOf("person", "car")))
        assertEquals("Person", reviewObjectSummary(listOf("person", "person")))
    }

    private fun review(id: String) = com.frigate.android.core.server.FrigateReview(
        id = id,
        camera = "cam",
        startTime = 1_700_000_000.0,
        endTime = 1_700_000_060.0,
        severity = "alert",
        hasBeenReviewed = false,
        thumbnailPath = null,
        objectLabels = listOf("person"),
    )
}
