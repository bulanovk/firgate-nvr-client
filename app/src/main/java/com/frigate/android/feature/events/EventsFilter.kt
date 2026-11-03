package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateReview
import com.frigate.android.core.server.FrigateReviewSummary
import java.time.LocalDate
import java.time.ZoneId

internal fun eventsRange(dayKey: String?, timezone: String, now: Double): EventsRange {
    if (dayKey == null) return EventsRange(now - 86_400, now, null)
    val zone = ZoneId.of(timezone)
    val day = LocalDate.parse(dayKey)
    return EventsRange(day.atStartOfDay(zone).toEpochSecond().toDouble(), day.plusDays(1).atStartOfDay(zone).toEpochSecond().toDouble(), dayKey)
}

internal fun reviewCounts(summary: FrigateReviewSummary, dayKey: String?, showReviewed: Boolean): Pair<Int, Int> {
    val counts = (if (dayKey == null) summary.last24Hours else summary.days[dayKey]) ?: return 0 to 0
    return if (showReviewed) counts.totalAlert to counts.totalDetection else
        (counts.totalAlert - counts.reviewedAlert).coerceAtLeast(0) to (counts.totalDetection - counts.reviewedDetection).coerceAtLeast(0)
}

internal fun filteredReviews(items: List<FrigateReview>, filter: EventsFilter): List<FrigateReview> {
    if (filter.type == EventsType.MOTION) return emptyList()
    val severity = if (filter.type == EventsType.ALERT) "alert" else "detection"
    return items.filter { (it.severity == severity || filter.includeOtherSeverity && it.severity in listOf("alert", "detection")) &&
        (filter.showReviewed || !it.hasBeenReviewed) }
}
