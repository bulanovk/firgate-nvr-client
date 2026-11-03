package com.frigate.android.feature.events

import com.frigate.android.core.server.FrigateHttpException
import com.frigate.android.core.server.FrigateReview
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex

internal class EventsBusyException : IOException("An operation is already in progress")

internal class EventsActions(private val repository: EventsRepository, private val onUnauthorized: () -> Unit) {
    private val mutex = Mutex()

    suspend fun mark(reviews: List<FrigateReview>) {
        if (reviews.isEmpty()) return
        mark(reviews.map { it.id }, !reviews.all { it.hasBeenReviewed })
    }

    suspend fun mark(ids: List<String>, reviewed: Boolean) {
        if (ids.isEmpty()) return
        operation { repository.markViewed(ids.distinct(), reviewed) }
    }

    suspend fun delete(ids: List<String>) {
        if (ids.isEmpty()) return
        operation { repository.delete(ids.distinct()) }
    }

    suspend fun export(reviews: List<FrigateReview>, now: Double, case: ExportCaseChoice?): ExportBatchResult {
        if (reviews.isEmpty()) return ExportBatchResult(emptyMap(), emptyMap())
        require(reviews.size <= 50) { "Select at most 50 events to export" }
        return operation {
            if (reviews.size == 1) ExportBatchResult(mapOf(reviews.single().id to repository.exportSingle(reviews.single(), now)), emptyMap())
            else repository.exportBatch(reviews, now, case)
        }
    }

    private suspend fun <T> operation(block: suspend () -> T): T {
        if (!mutex.tryLock()) throw EventsBusyException()
        try {
            return block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: FrigateHttpException) {
            if (error.code == 401) onUnauthorized()
            throw error
        } finally { mutex.unlock() }
    }
}
