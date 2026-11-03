package com.frigate.android.feature.review

/** What the Live carousel shows right now. */
internal enum class ReviewCarouselMode { HIDDEN, LOADING, ERROR, ITEMS }

internal fun reviewCarouselMode(state: ReviewUiState): ReviewCarouselMode = when {
    state.items.isNotEmpty() -> ReviewCarouselMode.ITEMS
    state.refreshFailed -> ReviewCarouselMode.ERROR
    !state.loaded -> ReviewCarouselMode.LOADING
    else -> ReviewCarouselMode.HIDDEN
}

/** Dense age of a review segment, matching the PWA time ago format. */
internal fun reviewTimeAgo(startTime: Double, nowSeconds: Double): String {
    val elapsed = (nowSeconds - startTime).coerceAtLeast(0.0)
    return when {
        elapsed < 10 -> "just now"
        elapsed < 60 -> "${elapsed.toInt()}s ago"
        elapsed < 3600 -> "${(elapsed / 60).toInt()}m ago"
        elapsed < 86400 -> "${(elapsed / 3600).toInt()}h ago"
        else -> "${(elapsed / 86400).toInt()}d ago"
    }
}

/** Human readable list of the objects detected during a review segment. */
internal fun reviewObjectSummary(labels: List<String>): String = labels
    .map { it.trim() }
    .filter { it.isNotEmpty() }
    .distinct()
    .joinToString(", ") { label -> label.replaceFirstChar { character -> character.uppercase() } }
