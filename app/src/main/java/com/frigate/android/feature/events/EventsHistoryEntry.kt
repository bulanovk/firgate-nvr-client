package com.frigate.android.feature.events

import com.frigate.android.feature.history.HistoryNavigation
import com.frigate.android.feature.history.HistoryRange

internal fun HistoryNavigation.openEvent(request: ReviewOpenRequest, now: Double) {
    val review = request.review
    val range = HistoryRange(
        minOf(request.range.after, review.startTime - HistoryRange.REVIEW_PADDING),
        maxOf(request.range.before, review.endTime ?: now),
    )
    openReview(review, range, restart = true)
}

internal fun HistoryNavigation.openMotion(cameraId: String, timestamp: Double, range: EventsRange, now: Double, grid: Boolean) {
    val anchor = if (grid) minOf(timestamp, now - 30) else timestamp
    openRecording(cameraId, anchor, HistoryRange(minOf(range.after, anchor), maxOf(range.before, anchor)))
}
