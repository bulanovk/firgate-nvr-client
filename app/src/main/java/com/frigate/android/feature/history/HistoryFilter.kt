package com.frigate.android.feature.history

import com.frigate.android.core.server.FrigateReview

/**
 * The filter of the archive view.
 *
 * The filter narrows the events by their labels, their zones and their severity. It never chooses
 * the camera: that is the job of the camera drawer.
 */
internal data class HistoryFilter(
    /** The explicitly selected labels; `null` means every label. */
    val labels: Set<String>? = null,
    /** The explicitly selected zones; `null` means every zone. */
    val zones: Set<String>? = null,
    /** The selected severities; the empty set keeps every severity. */
    val severities: Set<String> = ALL_SEVERITIES,
) {
    /** True when the event passes the filter. */
    fun matches(review: FrigateReview): Boolean {
        if (severities.isNotEmpty() && review.severity !in severities) return false
        if (labels != null && review.objectLabels.none { it in labels }) return false
        if (zones != null && review.zones.none { it in zones }) return false
        return true
    }

    /** The events of [reviews] that pass the filter. */
    fun applyTo(reviews: List<FrigateReview>): List<FrigateReview> = reviews.filter(::matches)

    companion object {
        val ALL_SEVERITIES: Set<String> = setOf("alert", "detection", "significant_motion")

        /**
         * The filter that is applied after the user confirms the draft.
         *
         * The draft may switch the labels back to all of them, but it cannot drop the last
         * explicitly selected label: an empty selection would mean "nothing at all" instead of
         * "everything", so the previous selection stays in place.
         */
        fun apply(current: HistoryFilter, draft: HistoryFilter): HistoryFilter = HistoryFilter(
            labels = draft.labels.orKeep(current.labels),
            zones = draft.zones.orKeep(current.zones),
            severities = draft.severities.ifEmpty { current.severities },
        )

        /** The filter of the initial view: every label, every zone, every severity. */
        fun reset(): HistoryFilter = HistoryFilter(labels = null, zones = null, severities = ALL_SEVERITIES)

        private fun Set<String>?.orKeep(current: Set<String>?): Set<String>? = when {
            this == null -> null
            isEmpty() -> current
            else -> this
        }
    }
}
