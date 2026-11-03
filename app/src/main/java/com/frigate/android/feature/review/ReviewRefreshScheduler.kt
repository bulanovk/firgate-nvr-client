package com.frigate.android.feature.review

/**
 * Keeps the earliest pending refresh deadline so that overlapping invalidations never postpone
 * a refetch that is already due.
 */
internal class ReviewRefreshScheduler(private val nowMillis: () -> Long = { System.currentTimeMillis() }) {
    private var deadlineMillis: Long? = null

    fun schedule(delayMillis: Long) {
        val deadline = nowMillis() + delayMillis.coerceAtLeast(0L)
        val current = deadlineMillis
        if (current == null || deadline < current) deadlineMillis = deadline
    }

    fun remainingMillis(): Long? {
        val deadline = deadlineMillis ?: return null
        val remaining = deadline - nowMillis()
        return if (remaining <= 0L) 0L else remaining
    }

    fun takeDue(): Boolean {
        val deadline = deadlineMillis ?: return false
        if (deadline - nowMillis() > 0L) return false
        deadlineMillis = null
        return true
    }

    fun clear() {
        deadlineMillis = null
    }
}
